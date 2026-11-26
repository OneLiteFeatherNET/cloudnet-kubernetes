package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The per-pod resources next to the pod itself: the wrapper secret and the staging
 * archive. Both are keyed by the pod name, so a static service that starts again under
 * the same name needs them gone first.
 */
final class PodResources {

  private static final Logger LOGGER = LoggerFactory.getLogger(PodResources.class);

  private final KubernetesClient client;
  private final String namespace;
  private final Consumer<String> stagingDeleter;

  PodResources(KubernetesClient client, String namespace, Consumer<String> stagingDeleter) {
    this.client = client;
    this.namespace = namespace;
    this.stagingDeleter = stagingDeleter;
  }

  /**
   * Removes the wrapper secret and the staging archive of a pod. Failures are logged
   * rather than thrown: a leftover object costs storage, a failed stop costs the service.
   */
  void removeSecretAndStaging(String podName) {
    try {
      this.client.secrets()
        .inNamespace(this.namespace)
        .withName(ResourceNaming.secretName(podName))
        .delete();
    } catch (KubernetesClientException exception) {
      LOGGER.debug("unable to remove wrapper secret of {}", podName, exception);
    }

    try {
      this.stagingDeleter.accept(ResourceNaming.stagingKey(podName));
    } catch (RuntimeException exception) {
      LOGGER.debug("unable to remove staging object of {}", podName, exception);
    }
  }

  /**
   * Makes sure no pod with this name exists before a new one is created under it. A pod
   * that is still terminating holds its name, so the create call would fail with 409.
   * The wait is a bounded watch on the pod, not a poll loop.
   */
  void awaitGone(String podName, Duration timeout) {
    var pod = this.client.pods().inNamespace(this.namespace).withName(podName);
    if (pod.get() == null) {
      return;
    }

    pod.delete();
    var remaining = pod.waitUntilCondition(Objects::isNull, timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (remaining != null) {
      throw new IllegalStateException("pod " + podName + " is still present after " + timeout);
    }
  }
}
