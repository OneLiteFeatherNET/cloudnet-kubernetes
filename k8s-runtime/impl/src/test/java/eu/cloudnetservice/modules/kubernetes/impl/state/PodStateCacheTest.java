package eu.cloudnetservice.modules.kubernetes.impl.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The cache is driven through its informer callbacks directly. No API server, no
 * wall clock: the time source is a fixed clock that each test moves explicitly.
 */
class PodStateCacheTest {

  private static final String SERVICE_ID = "a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74";

  /** Time only moves when a test says so. */
  private static final class ManualClock extends Clock {

    private Instant now = Instant.parse("2026-11-26T10:00:00Z");

    void advance(Duration duration) {
      this.now = this.now.plus(duration);
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return this.now;
    }
  }

  private static Pod pod(String name, String serviceId, String phase) {
    return new PodBuilder()
      .withNewMetadata()
        .withName(name)
        .withNamespace("cloudnet")
        .withLabels(Map.of(
          PodStateCache.LABEL_MANAGED_BY, "node-1",
          PodStateCache.LABEL_SERVICE_ID, serviceId))
      .endMetadata()
      .withNewStatus().withPhase(phase).endStatus()
      .build();
  }

  /** Never started, so the client is never dereferenced. */
  private static PodStateCache cache(ManualClock clock) {
    return new PodStateCache(null, KubernetesConfiguration.builder().build(), clock);
  }

  @Test
  void runningPodIsReportedAlive() {
    var cache = cache(new ManualClock());
    cache.index(pod("cn-lobby-1-a3f9c2e1", SERVICE_ID, "Running"));

    assertTrue(cache.running(SERVICE_ID), "a Running pod must be reported as running");
  }

  @Test
  void pendingPodIsKnownButNotRunning() {
    // Kubernetes has Pending, docker does not. Without this distinction the watchdog
    // would stop a service that is merely still being scheduled.
    var cache = cache(new ManualClock());
    cache.index(pod("cn-lobby-2-b1c2d3e4", SERVICE_ID, "Pending"));

    assertTrue(cache.known(SERVICE_ID), "a Pending pod must be known to the cache");
    assertFalse(cache.running(SERVICE_ID), "a Pending pod must not be reported as running");
  }

  @Test
  void pendingPodIsInsideTheStartupGraceWindowUntilItExpires() {
    var clock = new ManualClock();
    var cache = cache(clock);
    cache.index(pod("cn-lobby-3-c1c2d3e4", SERVICE_ID, "Pending"));

    clock.advance(Duration.ofMinutes(2));
    assertTrue(cache.withinStartupGrace(SERVICE_ID, Duration.ofMinutes(3)),
      "two minutes into a three minute grace window the pod must still count as starting");

    clock.advance(Duration.ofMinutes(2));
    assertFalse(cache.withinStartupGrace(SERVICE_ID, Duration.ofMinutes(3)),
      "four minutes into a three minute grace window the grace must have expired");
  }

  @Test
  void startupGraceIsMeasuredFromTheFirstSightingOfThePod() {
    var clock = new ManualClock();
    var cache = cache(clock);
    clock.advance(Duration.ofMinutes(10));
    cache.index(pod("cn-lobby-6-f1c2d3e4", SERVICE_ID, "Pending"));

    assertTrue(cache.withinStartupGrace(SERVICE_ID, Duration.ofMinutes(1)),
      "the grace window must start when the cache first sees the pod, not at process start");
  }

  @Test
  void unknownServiceIsNeitherKnownNorRunning() {
    var cache = cache(new ManualClock());

    assertFalse(cache.known("does-not-exist"));
    assertFalse(cache.running("does-not-exist"));
    assertFalse(cache.withinStartupGrace("does-not-exist", Duration.ofMinutes(2)));
  }

  @Test
  void runningCheckIsAnsweredFromTheCacheAlone() {
    // The watchdog calls this once per second per service. The cache has no client at
    // all in this test, so any api call would fail with a NullPointerException.
    var cache = cache(new ManualClock());
    cache.index(pod("cn-lobby-4-d1c2d3e4", SERVICE_ID, "Running"));

    for (var i = 0; i < 5000; i++) {
      assertTrue(cache.running(SERVICE_ID), "a Running pod must stay running on every check");
    }
  }

  @Test
  void deletionNotifiesTheVanishListener() {
    var cache = cache(new ManualClock());
    List<String> vanished = new ArrayList<>();
    cache.vanishListener(vanished::add);
    cache.index(pod("cn-lobby-5-e1c2d3e4", SERVICE_ID, "Running"));

    cache.forget(pod("cn-lobby-5-e1c2d3e4", SERVICE_ID, "Running"));

    assertEquals(List.of(SERVICE_ID), vanished, "the vanish listener must fire once with the service id");
    assertFalse(cache.known(SERVICE_ID), "a deleted pod must no longer be known");
  }
}
