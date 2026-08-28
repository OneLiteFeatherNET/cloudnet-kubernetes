package eu.cloudnetservice.modules.kubernetes.impl.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class KubernetesClientFactoryTest {

  @Test
  void clientBuildsWithTheExplicitJdkTransport() {
    // If the factory were left to the ServiceLoader, this would fail at runtime
    // inside a CloudNet module thread rather than here.
    try (var client = KubernetesClientFactory.create("cloudnet")) {
      assertNotNull(client);
      assertEquals("cloudnet", client.getNamespace());
    }
  }

  @Test
  void namespaceIsHonouredPerCall() {
    try (var client = KubernetesClientFactory.create("games")) {
      assertEquals("games", client.getNamespace());
    }
  }
}
