package eu.cloudnetservice.modules.kubernetes.impl.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Forces a server to listen on all interfaces inside its pod.
 *
 * <p>CloudNet's own preparers write the resolved host address straight into
 * server.properties. With the pod FQDN in there, the address would have to resolve at
 * bind time - a race against CoreDNS that publishNotReadyAddresses usually, but not
 * reliably, wins. The FQDN stays in the service snapshot, which is what proxies read
 * through the bridge; only the bind address changes.
 */
public final class BindAddressRewriter {

  public static final String BIND_ALL_INTERFACES = "0.0.0.0";

  private BindAddressRewriter() {
  }

  public static void rewriteServerProperties(Path serverProperties) throws IOException {
    var properties = new Properties();
    if (Files.exists(serverProperties)) {
      try (var in = Files.newInputStream(serverProperties)) {
        properties.load(in);
      }
    }

    properties.setProperty("server-ip", BIND_ALL_INTERFACES);

    try (var out = Files.newOutputStream(serverProperties)) {
      properties.store(out, "bind address rewritten by CloudNet-Kubernetes");
    }
  }

  /**
   * Velocity keeps its bind address in a TOML table. Rewriting the single {@code bind}
   * line keeps the rest of the file, including comments, untouched.
   */
  public static void rewriteVelocityToml(Path velocityToml, int port) throws IOException {
    if (Files.notExists(velocityToml)) {
      return;
    }

    var rewritten = Files.readAllLines(velocityToml).stream()
      .map(line -> line.stripLeading().startsWith("bind")
        ? "bind = \"" + BIND_ALL_INTERFACES + ":" + port + "\""
        : line)
      .toList();
    Files.write(velocityToml, rewritten);
  }
}
