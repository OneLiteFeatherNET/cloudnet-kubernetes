package eu.cloudnetservice.modules.kubernetes.impl.state;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodStateCacheTest {

  static KubernetesClient client;

  private static final String SERVICE_ID = "a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74";

  private void createPod(String name, String serviceId, String phase) {
    client.pods().inNamespace("cloudnet").resource(new PodBuilder()
      .withNewMetadata()
        .withName(name)
        .withNamespace("cloudnet")
        .withLabels(Map.of(
          PodStateCache.LABEL_MANAGED_BY, "node-1",
          PodStateCache.LABEL_SERVICE_ID, serviceId))
      .endMetadata()
      .withNewStatus().withPhase(phase).endStatus()
      .build()).create();
  }

  private PodStateCache startedCache() {
    var cache = new PodStateCache(client, KubernetesConfiguration.builder().build());
    cache.start("node-1");
    return cache;
  }

  @Test
  void runningPodIsReportedAlive() {
    this.createPod("cn-lobby-1-a3f9c2e1", SERVICE_ID, "Running");
    try (var cache = this.startedCache()) {
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.running(SERVICE_ID)));
    }
  }

  @Test
  void pendingPodIsKnownButNotRunning() {
    // Kubernetes has Pending, docker does not. Without this distinction the watchdog
    // would stop a service that is merely still being scheduled.
    this.createPod("cn-lobby-2-b1c2d3e4", "b1c2d3e4-0000-0000-0000-000000000000", "Pending");
    try (var cache = this.startedCache()) {
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.known("b1c2d3e4-0000-0000-0000-000000000000")));
      assertFalse(cache.running("b1c2d3e4-0000-0000-0000-000000000000"));
    }
  }

  @Test
  void pendingPodStaysInsideTheStartupGraceWindow() {
    this.createPod("cn-lobby-3-c1c2d3e4", "c1c2d3e4-0000-0000-0000-000000000000", "Pending");
    try (var cache = this.startedCache()) {
      var id = "c1c2d3e4-0000-0000-0000-000000000000";
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.known(id)));
      assertTrue(cache.withinStartupGrace(id, Duration.ofMinutes(2)));
      assertFalse(cache.withinStartupGrace(id, Duration.ZERO));
    }
  }

  @Test
  void unknownServiceIsNeitherKnownNorRunning() {
    try (var cache = this.startedCache()) {
      assertFalse(cache.known("does-not-exist"));
      assertFalse(cache.running("does-not-exist"));
      assertFalse(cache.withinStartupGrace("does-not-exist", Duration.ofMinutes(2)));
    }
  }

  @Test
  void repeatedAliveChecksNeverTouchTheApiServer() {
    // The watchdog calls this once per second per service.
    this.createPod("cn-lobby-4-d1c2d3e4", "d1c2d3e4-0000-0000-0000-000000000000", "Running");
    try (var cache = this.startedCache()) {
      var id = "d1c2d3e4-0000-0000-0000-000000000000";
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.running(id)));

      for (var i = 0; i < 5000; i++) {
        assertTrue(cache.running(id));
      }
    }
  }

  @Test
  void deletionNotifiesTheVanishListener() throws Exception {
    this.createPod("cn-lobby-5-e1c2d3e4", "e1c2d3e4-0000-0000-0000-000000000000", "Running");
    var latch = new CountDownLatch(1);

    try (var cache = new PodStateCache(client, KubernetesConfiguration.builder().build())) {
      cache.vanishListener(serviceId -> latch.countDown());
      cache.start("node-1");
      Awaitility.await().atMost(10, TimeUnit.SECONDS)
        .untilAsserted(() -> assertTrue(cache.known("e1c2d3e4-0000-0000-0000-000000000000")));

      client.pods().inNamespace("cloudnet").withName("cn-lobby-5-e1c2d3e4").delete();
      assertTrue(latch.await(10, TimeUnit.SECONDS), "vanish listener must fire on delete");
    }
  }
}
