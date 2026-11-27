package eu.cloudnetservice.modules.kubernetes.impl.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NodeSelectListenerTest {

  @Test
  void claimsServicesUsingOurRuntime() {
    assertTrue(NodeSelectListener.handles("k8s-jvm", "k8s-jvm"));
  }

  @Test
  void ignoresTheDefaultJvmRuntime() {
    assertFalse(NodeSelectListener.handles("jvm", "k8s-jvm"));
  }

  @Test
  void ignoresTheDockerRuntime() {
    assertFalse(NodeSelectListener.handles("docker-jvm", "k8s-jvm"));
  }

  @Test
  void respectsARenamedFactory() {
    assertTrue(NodeSelectListener.handles("k8s-prod", "k8s-prod"));
    assertFalse(NodeSelectListener.handles("k8s-jvm", "k8s-prod"));
  }
}
