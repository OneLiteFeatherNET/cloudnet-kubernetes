package eu.cloudnetservice.modules.kubernetes.impl.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BindAddressRewriterTest {

  @TempDir
  Path dir;

  private Properties rewriteAndRead(String initial) throws Exception {
    var file = this.dir.resolve("server.properties");
    Files.writeString(file, initial);
    BindAddressRewriter.rewriteServerProperties(file);

    var properties = new Properties();
    try (var in = Files.newInputStream(file)) {
      properties.load(in);
    }
    return properties;
  }

  @Test
  void bindsToAllInterfacesInsteadOfTheFqdn() throws Exception {
    var properties = this.rewriteAndRead(
      "server-ip=cn-lobby-1-a3f9c2e1.cloudnet-services.cloudnet.svc.cluster.local\n"
        + "server-port=25565\n");

    assertEquals("0.0.0.0", properties.getProperty("server-ip"));
  }

  @Test
  void leavesThePortUntouched() throws Exception {
    assertEquals("25580",
      this.rewriteAndRead("server-ip=1.2.3.4\nserver-port=25580\n").getProperty("server-port"));
  }

  @Test
  void preservesUnrelatedKeys() throws Exception {
    var properties = this.rewriteAndRead(
      "server-ip=1.2.3.4\nmotd=A Minecraft Server\nmax-players=40\n");

    assertEquals("A Minecraft Server", properties.getProperty("motd"));
    assertEquals("40", properties.getProperty("max-players"));
  }

  @Test
  void addsTheKeyWhenItIsMissingEntirely() throws Exception {
    assertEquals("0.0.0.0", this.rewriteAndRead("motd=hello\n").getProperty("server-ip"));
  }

  @Test
  void velocityBindIsRewrittenWithoutTouchingTheRest() throws Exception {
    var file = this.dir.resolve("velocity.toml");
    Files.writeString(file,
      "config-version = \"2.7\"\n"
        + "bind = \"cn-proxy-1.cloudnet-services.cloudnet.svc.cluster.local:25577\"\n"
        + "motd = \"<blue>Welcome\"\n"
        + "player-info-forwarding-mode = \"modern\"\n");

    BindAddressRewriter.rewriteVelocityToml(file, 25577);
    var content = Files.readString(file);

    assertTrue(content.contains("bind = \"0.0.0.0:25577\""), content);
    assertTrue(content.contains("player-info-forwarding-mode = \"modern\""), content);
    assertTrue(content.contains("<blue>Welcome"), content);
  }

  @Test
  void missingVelocityTomlIsNotAnError() throws Exception {
    BindAddressRewriter.rewriteVelocityToml(this.dir.resolve("absent.toml"), 25577);
  }
}
