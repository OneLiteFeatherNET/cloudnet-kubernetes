package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup reconciliation: removes pods this node created but no local service claims,
 * together with their wrapper secret and staging archive.
 *
 * <p>Adoption is impossible - the connection key lived only in the previous process and
 * the network channel is gone - so an orphan is only ever cleaned up, never reused.
 */
final class OrphanReaper {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrphanReaper.class);

  private final KubernetesClient client;
  private final String namespace;
  private final PodResources resources;

  OrphanReaper(KubernetesClient client, String namespace, PodResources resources) {
    this.client = client;
    this.namespace = namespace;
    this.resources = resources;
  }

  /**
   * @param nodeUniqueId the node whose pods are considered
   * @param isClaimed    whether a local service with the given service id still exists
   */
  void reap(String nodeUniqueId, Predicate<String> isClaimed) {
    var pods = this.client.pods()
      .inNamespace(this.namespace)
      .withLabel(PodStateCache.LABEL_MANAGED_BY, nodeUniqueId)
      .list()
      .getItems();

    for (var pod : pods) {
      var serviceId = serviceId(pod);
      if (serviceId == null || isClaimed.test(serviceId)) {
        continue;
      }

      var name = pod.getMetadata().getName();
      LOGGER.warn("removing orphaned pod {} - no local service claims it", name);
      this.client.pods().inNamespace(this.namespace).withName(name).delete();
      this.resources.removeSecretAndStaging(name);
    }
  }

  private static String serviceId(Pod pod) {
    var labels = pod.getMetadata().getLabels();
    return labels == null ? null : labels.get(PodStateCache.LABEL_SERVICE_ID);
  }
}
