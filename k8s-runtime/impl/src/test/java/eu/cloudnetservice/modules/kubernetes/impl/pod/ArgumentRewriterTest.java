package eu.cloudnetservice.modules.kubernetes.impl.pod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ArgumentRewriterTest {

  private static final Path LIBS = Path.of("/opt/cloudnet/launcher/libs");
  private static final Path WRAPPER = Path.of("/opt/cloudnet/temp/caches/wrapper.jar");
  private static final Path SERVICE = Path.of("/opt/cloudnet/temp/services/Lobby-1_a3f9c2e1");

  private List<String> rewrite(List<String> args) {
    return ArgumentRewriter.rewrite(args, LIBS, WRAPPER, SERVICE);
  }

  @Test
  void replacesTheLauncherBinaryWithPlainJava() {
    assertEquals("java", this.rewrite(List.of("/usr/lib/jvm/temurin-25/bin/java", "-Xmx1G")).getFirst());
  }

  @Test
  void mapsTheLibraryDirectoryOntoTheImagePath() {
    var result = this.rewrite(List.of("java", "-cp",
      "/opt/cloudnet/launcher/libs/guava.jar:/opt/cloudnet/launcher/libs/netty.jar"));

    assertEquals("/cloudnet/libs/guava.jar:/cloudnet/libs/netty.jar", result.get(2));
  }

  @Test
  void mapsTheWrapperAgentOntoTheImagePath() {
    var result = this.rewrite(List.of("java", "-javaagent:/opt/cloudnet/temp/caches/wrapper.jar"));

    assertEquals("-javaagent:/cloudnet/wrapper.jar", result.get(1));
  }

  @Test
  void mapsTheServiceDirectoryOntoTheMountPath() {
    var result = this.rewrite(List.of("java", "-cp",
      "/opt/cloudnet/temp/services/Lobby-1_a3f9c2e1/server.jar"));

    assertEquals("/cloudnet/service/server.jar", result.get(2));
  }

  @Test
  void handlesAFullCommandLineWithAllThreePrefixes() {
    var result = this.rewrite(List.of(
      "/usr/lib/jvm/temurin-25/bin/java",
      "-Xmx4096M",
      "-javaagent:/opt/cloudnet/temp/caches/wrapper.jar",
      "-Dservice.bind.host=0.0.0.0",
      "-cp",
      "/opt/cloudnet/launcher/libs/a.jar:/opt/cloudnet/temp/services/Lobby-1_a3f9c2e1/server.jar",
      "net.minecraft.Main"));

    var joined = String.join(" ", result);
    assertFalse(joined.contains("/opt/cloudnet"), "no node path may survive: " + joined);
    assertTrue(joined.contains("/cloudnet/libs/a.jar"));
    assertTrue(joined.contains("/cloudnet/service/server.jar"));
    assertTrue(joined.contains("-javaagent:/cloudnet/wrapper.jar"));
    // Untouched arguments stay untouched.
    assertTrue(joined.contains("-Xmx4096M"));
    assertTrue(joined.contains("net.minecraft.Main"));
  }

  @Test
  void leavesUnrelatedArgumentsAlone() {
    var result = this.rewrite(List.of("java", "-Dfile.encoding=UTF-8", "--enable-preview"));

    assertEquals("-Dfile.encoding=UTF-8", result.get(1));
    assertEquals("--enable-preview", result.get(2));
  }

  @Test
  void emptyArgumentListDoesNotThrow() {
    assertTrue(this.rewrite(List.of()).isEmpty());
  }
}
