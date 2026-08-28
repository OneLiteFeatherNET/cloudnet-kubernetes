package eu.cloudnetservice.modules.kubernetes.impl.console;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Follows a pod's stdout, reconnecting on every break.
 *
 * <p>Log rotation (kubelet default 10 MiB) and the four hour idle timeout end the
 * stream as a matter of course. The reconnect loop is therefore required behaviour,
 * not hardening.
 *
 * <p>Note that the Kubernetes log API does not separate stdout from stderr, so the
 * error stream flag is always false downstream. Minecraft servers log through Log4j
 * to stdout, so little is lost - but it is a deliberate regression against a local
 * process.
 */
public final class PodLogStream {

  private static final Logger LOGGER = LoggerFactory.getLogger(PodLogStream.class);
  private static final Duration INITIAL_BACKOFF = Duration.ofMillis(250);
  private static final Duration MAX_BACKOFF = Duration.ofSeconds(5);

  private final KubernetesClient client;
  private final String namespace;
  private final String podName;
  private final LogLineParser parser = new LogLineParser();
  private final AtomicBoolean running = new AtomicBoolean(true);

  private volatile LogWatch watch;

  public PodLogStream(KubernetesClient client, String namespace, String podName) {
    this.client = client;
    this.namespace = namespace;
    this.podName = podName;
  }

  public void start(ExecutorService executor, Consumer<String> sink) {
    executor.submit(() -> this.loop(sink));
  }

  private void loop(Consumer<String> sink) {
    var backoff = INITIAL_BACKOFF;
    while (this.running.get()) {
      try {
        var loggable = this.client.pods()
          .inNamespace(this.namespace)
          .withName(this.podName)
          .usingTimestamps();

        // sinceTime and tailingLines return different Loggable subtypes, so this
        // cannot be folded into an Optional chain.
        var seen = this.parser.lastSeen().orElse(null);
        var tailed = seen != null
          ? loggable.sinceTime(DateTimeFormatter.ISO_INSTANT.format(seen))
          : loggable.tailingLines(0);

        try (var current = tailed.watchLog()) {
          this.watch = current;
          backoff = INITIAL_BACKOFF;

          var reader = new BufferedReader(
            new InputStreamReader(current.getOutput(), StandardCharsets.UTF_8), 8192);
          String line;
          while ((line = reader.readLine()) != null && this.running.get()) {
            this.parser.accept(line).ifPresent(parsed -> sink.accept(parsed.message()));
          }
        }
      } catch (Exception exception) {
        if (!this.running.get()) {
          return;
        }
        LOGGER.debug("log stream for {} broke, reconnecting in {}", this.podName, backoff, exception);
      }

      if (!this.running.get()) {
        return;
      }

      LockSupport.parkNanos(backoff.toNanos());
      var doubled = backoff.multipliedBy(2);
      backoff = doubled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : doubled;
    }
  }

  public void stop() {
    this.running.set(false);
    var current = this.watch;
    this.watch = null;
    if (current != null) {
      try {
        current.close();
      } catch (Exception ignored) {
        // irrelevant while tearing down
      }
    }
  }
}
