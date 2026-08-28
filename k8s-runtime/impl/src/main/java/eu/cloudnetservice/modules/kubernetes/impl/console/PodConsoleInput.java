package eu.cloudnetservice.modules.kubernetes.impl.console;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Writes to the stdin of PID 1 inside a pod.
 *
 * <p>attach, never exec: exec starts a new process whose stdin is not the server's,
 * so a "stop" sent through it would never arrive.
 *
 * <p>The attach is opened lazily on the first console command and rebuilt after a
 * break. That makes the kubelet's four hour streamingConnectionIdleTimeout a
 * non-issue without sending keepalive bytes, which the server would read as empty
 * commands.
 */
public final class PodConsoleInput implements AutoCloseable {

  private static final Logger LOGGER = LoggerFactory.getLogger(PodConsoleInput.class);
  private static final int ATTEMPTS = 2;

  private final KubernetesClient client;
  private final String namespace;
  private final String podName;

  private volatile ExecWatch attach;
  private volatile OutputStream stdin;

  public PodConsoleInput(KubernetesClient client, String namespace, String podName) {
    this.client = client;
    this.namespace = namespace;
    this.podName = podName;
  }

  public synchronized void runCommand(String command) {
    for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
      try {
        this.ensureAttached();
        this.stdin.write((command + "\n").getBytes(StandardCharsets.UTF_8));
        this.stdin.flush();
        return;
      } catch (Exception exception) {
        LOGGER.debug("stdin write to {} failed on attempt {}", this.podName, attempt, exception);
        this.drop();
        if (attempt == ATTEMPTS - 1) {
          throw new IllegalStateException("stdin unreachable for pod " + this.podName, exception);
        }
      }
    }
  }

  private void ensureAttached() {
    if (this.stdin != null) {
      return;
    }

    this.attach = this.client.pods()
      .inNamespace(this.namespace)
      .withName(this.podName)
      .redirectingInput()
      .usingListener(new ExecListener() {
        @Override
        public void onFailure(Throwable throwable, Response response) {
          drop();
        }

        @Override
        public void onClose(int code, String reason) {
          drop();
        }
      })
      .attach();
    this.stdin = this.attach.getInput();
  }

  private synchronized void drop() {
    this.stdin = null;
    var current = this.attach;
    this.attach = null;
    if (current != null) {
      try {
        current.close();
      } catch (Exception ignored) {
        // irrelevant while tearing down
      }
    }
  }

  @Override
  public void close() {
    this.drop();
  }
}
