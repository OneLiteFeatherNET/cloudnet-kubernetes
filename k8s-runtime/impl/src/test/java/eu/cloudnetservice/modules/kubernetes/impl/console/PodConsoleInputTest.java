package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodConsoleInputTest {

  static KubernetesClient client;

  @Test
  void closingWithoutEverAttachingIsSafe() {
    // Lazy attach: with no console command issued, no connection may have been opened.
    var console = new PodConsoleInput(client, "cloudnet", "cn-lobby-1-a3f9c2e1");
    assertDoesNotThrow(console::close);
  }

  @Test
  void closeIsIdempotent() {
    var console = new PodConsoleInput(client, "cloudnet", "cn-lobby-1-a3f9c2e1");
    assertDoesNotThrow(() -> {
      console.close();
      console.close();
    });
  }

  @Test
  void commandAgainstMissingPodFailsAfterRetry() {
    var console = new PodConsoleInput(client, "cloudnet", "does-not-exist");
    assertThrows(IllegalStateException.class, () -> console.runCommand("stop"));
  }
}
