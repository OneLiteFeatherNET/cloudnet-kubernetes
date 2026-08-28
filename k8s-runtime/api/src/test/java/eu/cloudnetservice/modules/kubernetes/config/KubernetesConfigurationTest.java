package eu.cloudnetservice.modules.kubernetes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class KubernetesConfigurationTest {

  @Test
  void defaultsMatchTheSpecification() {
    var config = KubernetesConfiguration.builder().build();

    assertEquals("k8s-jvm", config.factoryName());
    assertEquals("cloudnet", config.namespace());
    assertEquals("cluster.local", config.clusterDomain());
    assertEquals("cloudnet-services", config.subdomain());
    assertEquals(120, config.terminationGracePeriodSeconds());
    assertEquals(86400L, config.activeDeadlineSeconds());
    assertEquals(1.35d, config.memoryOverheadFactor());
  }

  @Test
  void memoryLimitAddsNonHeapOverheadWithFloor() {
    var config = KubernetesConfiguration.builder().build();

    // 4096 * 0.35 = 1433, above the 512 floor
    assertEquals(5529, config.memoryLimitMib(4096));
    // 512 * 0.35 = 179, so the floor applies
    assertEquals(1024, config.memoryLimitMib(512));
  }

  @Test
  void activeDeadlineIsNeverUnlimited() {
    // The kubelet-side backstop must survive node, module and informer all being dead.
    assertTrue(KubernetesConfiguration.builder().build().activeDeadlineSeconds() > 0);
  }

  @Test
  void gracePeriodExceedsCloudNetProcessTerminationTimeout() {
    // Otherwise Kubernetes SIGKILLs the server mid-save.
    assertTrue(KubernetesConfiguration.builder().build().terminationGracePeriodSeconds() >= 60);
  }

  @Test
  void builderOverridesAreApplied() {
    var config = KubernetesConfiguration.builder()
      .factoryName("k8s-prod")
      .namespace("games")
      .nodeListenerPort(1420)
      .build();

    assertEquals("k8s-prod", config.factoryName());
    assertEquals("games", config.namespace());
    assertEquals(1420, config.nodeListenerPort());
  }

  @Test
  void stagingDefaultsPointAtCephRgwWithPathStyle() {
    var staging = StagingConfiguration.builder().build();

    assertTrue(staging.endpoint().contains("rook-ceph-rgw"), staging.endpoint());
    // Ceph RGW needs path style; virtual-host style requires wildcard DNS.
    assertTrue(staging.pathStyleAccess());
  }
}
