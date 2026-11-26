package eu.cloudnetservice.modules.kubernetes.impl.state;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.informers.ResourceEventHandler;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Serves pod state from a shared informer instead of individual API calls.
 *
 * <p>This is not an optimisation. CloudNet's watchdog calls {@code alive()} once per
 * second for every local service; an uncached call per service per second would
 * overload the API server on any real network.
 */
public final class PodStateCache implements AutoCloseable {

  public static final String LABEL_MANAGED_BY = "cloudnet.eu/managed-by";
  public static final String LABEL_SERVICE_ID = "cloudnet.eu/service-id";

  private static final long RESYNC_MILLIS = 30_000L;

  private final KubernetesClient client;
  private final KubernetesConfiguration config;
  private final Clock clock;
  private final Map<String, Pod> byServiceId = new ConcurrentHashMap<>();
  private final Map<String, Instant> firstSeen = new ConcurrentHashMap<>();

  private volatile SharedIndexInformer<Pod> informer;
  private volatile Consumer<String> vanishListener = serviceId -> { };

  public PodStateCache(KubernetesClient client, KubernetesConfiguration config, Clock clock) {
    this.client = client;
    this.config = config;
    this.clock = clock;
  }

  public void vanishListener(Consumer<String> listener) {
    this.vanishListener = listener;
  }

  public void start(String nodeUniqueId) {
    this.informer = this.client.pods()
      .inNamespace(this.config.namespace())
      .withLabel(LABEL_MANAGED_BY, nodeUniqueId)
      .inform(new ResourceEventHandler<>() {
        @Override
        public void onAdd(Pod pod) {
          index(pod);
        }

        @Override
        public void onUpdate(Pod oldPod, Pod newPod) {
          index(newPod);
        }

        @Override
        public void onDelete(Pod pod, boolean unknownFinalState) {
          forget(pod);
        }
      }, RESYNC_MILLIS);
  }

  /** O(1), no network. Safe to call on the watchdog tick. */
  public boolean running(String serviceId) {
    var pod = this.byServiceId.get(serviceId);
    return pod != null
      && pod.getStatus() != null
      && "Running".equals(pod.getStatus().getPhase())
      && pod.getMetadata().getDeletionTimestamp() == null;
  }

  /**
   * Whether a pod exists at all, regardless of phase. Kubernetes has a Pending phase
   * that docker does not; without this distinction the watchdog would kill a service
   * that is merely still being scheduled.
   */
  public boolean known(String serviceId) {
    return this.byServiceId.containsKey(serviceId);
  }

  /**
   * True while a known pod is still inside its startup grace window, so the caller
   * can treat it as alive even though it has not reached Running yet.
   */
  public boolean withinStartupGrace(String serviceId, Duration grace) {
    var seen = this.firstSeen.get(serviceId);
    return seen != null && this.clock.instant().isBefore(seen.plus(grace));
  }

  /** True once the pod has reached a terminal phase. Such a pod will never run again. */
  public boolean terminal(String serviceId) {
    var pod = this.byServiceId.get(serviceId);
    if (pod == null || pod.getStatus() == null) {
      return false;
    }
    var phase = pod.getStatus().getPhase();
    return "Failed".equals(phase) || "Succeeded".equals(phase);
  }

  /**
   * Liveness as the watchdog sees it.
   *
   * <p>A running pod is alive. A pod in a terminal phase is dead, whatever the grace
   * window says. Otherwise the service is alive while it is still starting: a known pod
   * within the grace window since it was first seen, or - until the informer has
   * delivered the pod at all - within the grace window since {@code startedAt}.
   */
  public boolean alive(String serviceId, Instant startedAt, Duration grace) {
    if (running(serviceId)) {
      return true;
    }
    if (terminal(serviceId)) {
      return false;
    }
    if (known(serviceId)) {
      return withinStartupGrace(serviceId, grace);
    }
    return startedAt != null && this.clock.instant().isBefore(startedAt.plus(grace));
  }

  public Set<String> knownServiceIds() {
    return Set.copyOf(this.byServiceId.keySet());
  }

  /** Informer callback for add and update. Package-private so tests can drive it directly. */
  void index(Pod pod) {
    var id = serviceId(pod);
    if (id != null) {
      this.byServiceId.put(id, pod);
      this.firstSeen.putIfAbsent(id, this.clock.instant());
    }
  }

  /** Informer callback for delete. Package-private so tests can drive it directly. */
  void forget(Pod pod) {
    var id = serviceId(pod);
    if (id != null) {
      this.byServiceId.remove(id);
      this.firstSeen.remove(id);
      this.vanishListener.accept(id);
    }
  }

  private static String serviceId(Pod pod) {
    var labels = pod.getMetadata().getLabels();
    return labels == null ? null : labels.get(LABEL_SERVICE_ID);
  }

  @Override
  public void close() {
    var current = this.informer;
    this.informer = null;
    if (current != null) {
      current.close();
    }
    this.byServiceId.clear();
    this.firstSeen.clear();
  }
}
