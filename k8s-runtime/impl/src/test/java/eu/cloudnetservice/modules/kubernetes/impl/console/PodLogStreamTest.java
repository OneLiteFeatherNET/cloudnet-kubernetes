package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient(crud = true)
class PodLogStreamTest {

  static KubernetesClient client;

  @Test
  void stopTerminatesTheReconnectLoop() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var stream = new PodLogStream(client, "cloudnet", "cn-lobby-1-a3f9c2e1");

    stream.start(executor, line -> { });
    stream.stop();

    executor.shutdown();
    assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS),
      "the reconnect loop must exit after stop()");
  }

  @Test
  void stopIsIdempotent() {
    var stream = new PodLogStream(client, "cloudnet", "cn-lobby-1-a3f9c2e1");
    assertDoesNotThrow(() -> {
      stream.stop();
      stream.stop();
    });
  }

  @Test
  void stopBeforeStartIsSafe() {
    assertDoesNotThrow(() -> new PodLogStream(client, "cloudnet", "never-started").stop());
  }
}
