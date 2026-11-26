package eu.cloudnetservice.modules.kubernetes.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class OrphanReaperTest {

  private static final String NAMESPACE = "cloudnet";
  private static final String NODE = "node-1";
  private static final String ORPHAN_POD = "cn-lobby-1-a3f9c2e1";
  private static final String ORPHAN_SERVICE_ID = "a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74";
  private static final String CLAIMED_POD = "cn-lobby-2-b1c2d3e4";
  private static final String CLAIMED_SERVICE_ID = "b1c2d3e4-0000-0000-0000-000000000000";

  KubernetesClient client;

  private void createPodWithSecret(String podName, String serviceId) {
    this.client.pods().inNamespace(NAMESPACE).resource(new PodBuilder()
      .withNewMetadata()
        .withName(podName)
        .withNamespace(NAMESPACE)
        .withLabels(Map.of(
          PodStateCache.LABEL_MANAGED_BY, NODE,
          PodStateCache.LABEL_SERVICE_ID, serviceId))
      .endMetadata()
      .build()).create();
    this.client.secrets().inNamespace(NAMESPACE).resource(new SecretBuilder()
      .withNewMetadata().withName(ResourceNaming.secretName(podName)).withNamespace(NAMESPACE).endMetadata()
      .build()).create();
  }

  private List<String> reap(List<String> stagedKeys) {
    var resources = new PodResources(this.client, NAMESPACE, stagedKeys::add);
    new OrphanReaper(this.client, NAMESPACE, resources)
      .reap(NODE, serviceId -> serviceId.equals(CLAIMED_SERVICE_ID));
    return stagedKeys;
  }

  @Test
  void orphanPodIsDeleted() {
    this.createPodWithSecret(ORPHAN_POD, ORPHAN_SERVICE_ID);

    this.reap(new ArrayList<>());

    assertNull(this.client.pods().inNamespace(NAMESPACE).withName(ORPHAN_POD).get(),
      "a pod no local service claims must be removed");
  }

  @Test
  void orphanPodsWrapperSecretIsDeleted() {
    this.createPodWithSecret(ORPHAN_POD, ORPHAN_SERVICE_ID);

    this.reap(new ArrayList<>());

    assertNull(this.client.secrets().inNamespace(NAMESPACE).withName(ResourceNaming.secretName(ORPHAN_POD)).get(),
      "the wrapper secret of an orphaned pod must not outlive it");
  }

  @Test
  void orphanPodsStagingArchiveIsDeleted() {
    this.createPodWithSecret(ORPHAN_POD, ORPHAN_SERVICE_ID);

    var stagedKeys = this.reap(new ArrayList<>());

    assertEquals(List.of(ResourceNaming.stagingKey(ORPHAN_POD)), stagedKeys,
      "the staging archive of an orphaned pod must be removed");
  }

  @Test
  void podClaimedByALocalServiceIsKept() {
    this.createPodWithSecret(CLAIMED_POD, CLAIMED_SERVICE_ID);

    this.reap(new ArrayList<>());

    assertNotNull(this.client.pods().inNamespace(NAMESPACE).withName(CLAIMED_POD).get(),
      "a pod a local service still claims must not be touched");
  }
}
