package eu.cloudnetservice.modules.kubernetes.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodResourcesTest {

  private static final String NAMESPACE = "cloudnet";
  private static final String POD = "cn-lobby-1-a3f9c2e1";

  KubernetesClient client;

  private PodResources resources(List<String> stagedKeys) {
    return new PodResources(this.client, NAMESPACE, stagedKeys::add);
  }

  private void createSecret(String name) {
    this.client.secrets().inNamespace(NAMESPACE).resource(new SecretBuilder()
      .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
      .build()).create();
  }

  private void createPod(String name) {
    this.client.pods().inNamespace(NAMESPACE).resource(new PodBuilder()
      .withNewMetadata().withName(name).withNamespace(NAMESPACE).endMetadata()
      .build()).create();
  }

  @Test
  void removingTheWrapperSecretDeletesTheSecretOfThatPod() {
    this.createSecret(ResourceNaming.secretName(POD));

    this.resources(new ArrayList<>()).removeSecretAndStaging(POD);

    assertNull(this.client.secrets().inNamespace(NAMESPACE).withName(ResourceNaming.secretName(POD)).get(),
      "the wrapper secret of a stopped pod must be removed so the next start can create it again");
  }

  @Test
  void removingTheSecretAlsoDeletesTheStagingObjectOfThatPod() {
    var stagedKeys = new ArrayList<String>();

    this.resources(stagedKeys).removeSecretAndStaging(POD);

    assertEquals(List.of("services/" + POD + ".tar"), stagedKeys,
      "the staging archive of a stopped pod must be removed exactly once");
  }

  @Test
  void removingResourcesThatAreAlreadyGoneDoesNotFail() {
    assertDoesNotThrow(() -> this.resources(new ArrayList<>()).removeSecretAndStaging(POD),
      "a second cleanup of the same pod must be harmless");
  }

  @Test
  void awaitingAPodThatDoesNotExistReturnsImmediately() {
    assertDoesNotThrow(() -> this.resources(new ArrayList<>()).awaitGone(POD, Duration.ofSeconds(30)),
      "no previous pod means nothing to wait for");
  }

  @Test
  void awaitingAPodReturnsOnlyOnceThePodIsGone() {
    this.createPod(POD);

    this.resources(new ArrayList<>()).awaitGone(POD, Duration.ofSeconds(30));

    assertNull(this.client.pods().inNamespace(NAMESPACE).withName(POD).get(),
      "awaitGone must not return while the previous pod is still present");
  }
}
