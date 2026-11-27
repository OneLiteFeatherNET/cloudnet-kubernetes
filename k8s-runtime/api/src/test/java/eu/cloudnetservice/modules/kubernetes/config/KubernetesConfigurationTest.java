package eu.cloudnetservice.modules.kubernetes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
  void stagingImageDefaultIsPinnedInsteadOfLatest() {
    // :latest changes under the module without a release; the tag must be explicit.
    var image = KubernetesConfiguration.builder().build().stagingImage();

    assertEquals("docker.io/rclone/rclone:1.72.0", image);
    assertFalse(image.endsWith(":latest"), "the staging image must not float: " + image);
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
  void podsRunAStockJreImageByDefault() {
    // The runtime comes from the node, so no image of ours has to match the node version.
    assertEquals("eclipse-temurin:25-jre", KubernetesConfiguration.builder().build().serviceImage());
  }

  @Test
  void noTruststoreIsShippedByDefault() {
    var config = KubernetesConfiguration.builder().build();

    assertNull(config.truststorePath(), "the pods then use the JDK default trust store");
    assertNull(config.truststorePassword());
  }

  @Test
  void truststoreAndItsPasswordAreTakenFromTheBuilder() {
    var config = KubernetesConfiguration.builder()
      .truststorePath("/opt/cloudnet/certs/cacerts")
      .truststorePassword("changeit")
      .build();

    assertEquals("/opt/cloudnet/certs/cacerts", config.truststorePath());
    assertEquals("changeit", config.truststorePassword());
  }

  @Test
  void stagingDefaultsPointAtCephRgwWithPathStyle() {
    var staging = StagingConfiguration.builder().build();

    assertTrue(staging.endpoint().contains("rook-ceph-rgw"), staging.endpoint());
    // Ceph RGW needs path style; virtual-host style requires wildcard DNS.
    assertTrue(staging.pathStyleAccess());
  }
}
