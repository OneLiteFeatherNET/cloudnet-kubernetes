package eu.cloudnetservice.modules.kubernetes.impl.console;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.server.mock.EnableKubernetesMockClient;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.mockwebserver.http.RecordedRequest;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

@EnableKubernetesMockClient
class PodLogStreamFirstConnectTest {

  static KubernetesClient client;
  static KubernetesMockServer server;

  @Test
  void firstConnectReadsTheWholeContainerLogWithoutTailLimit() throws Exception {
    var executor = Executors.newSingleThreadExecutor();
    var stream = new PodLogStream(client, "cloudnet", "cn-lobby-1-a3f9c2e1");
    try {
      stream.start(executor, line -> { });

      var logRequest = awaitLogRequest();
      assertNotNull(logRequest, "the stream must issue a log request for the pod");
      assertFalse(logRequest.getPath().contains("tailLines"),
        "the first connect must read from the container start, not a tail of it: " + logRequest.getPath());
    } finally {
      stream.stop();
      executor.shutdownNow();
    }
  }

  private static RecordedRequest awaitLogRequest() throws InterruptedException {
    // the stream first looks the pod up, then requests its log; skip any other request
    for (var i = 0; i < 20; i++) {
      var request = server.takeRequest(10, TimeUnit.SECONDS);
      if (request == null) {
        return null;
      }
      if (request.getPath().contains("/log")) {
        return request;
      }
    }
    return null;
  }
}
