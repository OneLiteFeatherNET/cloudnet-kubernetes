package eu.cloudnetservice.modules.kubernetes.impl.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

// The arguments carry POSIX paths and split on ':', the classpath separator of Linux nodes.
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "node paths are POSIX paths")
class RuntimeBundleTest {

  @TempDir
  Path root;

  private Path libs;
  private Path wrapper;

  @BeforeEach
  void setUp() throws IOException {
    this.libs = Files.createDirectories(this.root.resolve("launcher/libs"));
    this.wrapper = this.write(this.root.resolve("temp/caches/wrapper.jar"), "wrapper");
  }

  private Path write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    return Files.writeString(file, content);
  }

  private RuntimeBundle collect(List<String> arguments) throws IOException {
    return RuntimeBundle.collect(arguments, this.libs, this.wrapper, null);
  }

  private List<String> entryNames(RuntimeBundle bundle) {
    return bundle.entries().stream().map(RuntimeBundle.Entry::relativePath).toList();
  }

  @Test
  void wrapperJarIsShippedAsTheWrapperEntry() throws IOException {
    var bundle = this.collect(List.of());

    var wrapperEntry = bundle.entries().stream()
      .filter(entry -> entry.relativePath().equals("wrapper.jar"))
      .findFirst();
    assertTrue(wrapperEntry.isPresent(), "wrapper.jar must be part of every bundle: " + entryNames(bundle));
    assertEquals(this.wrapper, wrapperEntry.get().source());
  }

  @Test
  void classpathJarsAreShippedRelativeToTheLibDirectory() throws IOException {
    var first = this.write(this.libs.resolve("guava.jar"), "guava");
    var second = this.write(this.libs.resolve("sub/netty.jar"), "netty");

    var bundle = this.collect(List.of("-cp", first + ":" + second));

    assertTrue(entryNames(bundle).contains("libs/guava.jar"), entryNames(bundle).toString());
    assertTrue(entryNames(bundle).contains("libs/sub/netty.jar"), entryNames(bundle).toString());
  }

  @Test
  void javaAgentOptionsAfterTheEqualsSignDoNotHideTheAgentJar() throws IOException {
    var agent = this.write(this.libs.resolve("agent.jar"), "agent");

    var bundle = this.collect(List.of("-javaagent:" + agent + "=mode=fast"));

    assertTrue(entryNames(bundle).contains("libs/agent.jar"), entryNames(bundle).toString());
  }

  @Test
  void wildcardClasspathEntryShipsTheJarsOfThatDirectoryOnly() throws IOException {
    this.write(this.libs.resolve("plugins/a.jar"), "a");
    this.write(this.libs.resolve("plugins/readme.txt"), "text");
    this.write(this.libs.resolve("plugins/nested/b.jar"), "b");

    var bundle = this.collect(List.of("-cp", this.libs.resolve("plugins") + "/*"));

    assertTrue(entryNames(bundle).contains("libs/plugins/a.jar"), entryNames(bundle).toString());
    assertFalse(entryNames(bundle).contains("libs/plugins/readme.txt"),
      "a wildcard only matches jars: " + entryNames(bundle));
    assertFalse(entryNames(bundle).contains("libs/plugins/nested/b.jar"),
      "a wildcard does not descend into subdirectories: " + entryNames(bundle));
  }

  @Test
  void directoryClasspathEntryShipsEveryFileBeneathIt() throws IOException {
    this.write(this.libs.resolve("classes/app/Main.class"), "main");

    var bundle = this.collect(List.of("-cp", this.libs.resolve("classes").toString()));

    assertTrue(entryNames(bundle).contains("libs/classes/app/Main.class"), entryNames(bundle).toString());
  }

  @Test
  void pathsOutsideTheLibDirectoryAreNotShipped() throws IOException {
    var serviceJar = this.write(this.root.resolve("services/Lobby-1/server.jar"), "server");

    var bundle = this.collect(List.of("-cp", serviceJar.toString()));

    assertFalse(entryNames(bundle).stream().anyMatch(name -> name.endsWith("server.jar")),
      "the service directory travels in its own archive: " + entryNames(bundle));
  }

  @Test
  void relativeArgumentsAreNeverResolvedAgainstTheWorkingDirectory() throws IOException {
    var bundle = this.collect(List.of("java", "-Xmx1G", "Main"));

    assertEquals(List.of("wrapper.jar"), entryNames(bundle));
  }

  @Test
  void missingClasspathEntriesAreSkippedLikeTheJvmSkipsThem() throws IOException {
    var bundle = this.collect(List.of("-cp", this.libs.resolve("missing.jar").toString()));

    assertEquals(List.of("wrapper.jar"), entryNames(bundle));
  }

  @Test
  void objectKeyIsContentAddressedUnderTheRuntimePrefix() throws IOException {
    this.write(this.libs.resolve("a.jar"), "a");

    var key = this.collect(List.of("-cp", this.libs.resolve("a.jar").toString())).objectKey();

    assertTrue(key.matches("runtime/[0-9a-f]{64}\\.tar"), key);
  }

  @Test
  void identicalContentYieldsTheSameKeyFromAnotherDirectory() throws IOException {
    this.write(this.libs.resolve("a.jar"), "a");
    var first = this.collect(List.of("-cp", this.libs.resolve("a.jar").toString())).objectKey();

    var otherLibs = Files.createDirectories(this.root.resolve("other/launcher/libs"));
    var otherWrapper = this.write(this.root.resolve("other/temp/caches/wrapper.jar"), "wrapper");
    this.write(otherLibs.resolve("a.jar"), "a");
    var second = RuntimeBundle.collect(
      List.of("-cp", otherLibs.resolve("a.jar").toString()), otherLibs, otherWrapper, null).objectKey();

    assertEquals(first, second, "the key depends on content, not on where the node keeps it");
  }

  @Test
  void keyChangesWhenAShippedLibraryChanges() throws IOException {
    var jar = this.write(this.libs.resolve("a.jar"), "version 1");
    var before = this.collect(List.of("-cp", jar.toString())).objectKey();

    Files.writeString(jar, "version 2");
    var after = this.collect(List.of("-cp", jar.toString())).objectKey();

    assertNotEquals(before, after, "a changed library must never reuse the stale bundle");
  }

  @Test
  void keyDoesNotDependOnTheOrderOfClasspathEntries() throws IOException {
    var first = this.write(this.libs.resolve("a.jar"), "a");
    var second = this.write(this.libs.resolve("b.jar"), "b");
    var forward = this.collect(List.of("-cp", first + ":" + second)).objectKey();

    var reversed = this.collect(List.of("-cp", second + ":" + first)).objectKey();

    assertEquals(forward, reversed);
  }

  @Test
  void truststoreIsShippedAsCacerts() throws IOException {
    var truststore = this.write(this.root.resolve("truststore/cacerts"), "trusted");

    var bundle = RuntimeBundle.collect(List.of(), this.libs, this.wrapper, truststore);

    assertTrue(entryNames(bundle).contains("cacerts"), entryNames(bundle).toString());
    assertEquals(truststore, bundle.entries().stream()
      .filter(entry -> entry.relativePath().equals("cacerts"))
      .findFirst().orElseThrow().source());
  }

  @Test
  void noCacertsEntryWithoutATruststore() throws IOException {
    var bundle = this.collect(List.of());

    assertFalse(entryNames(bundle).contains("cacerts"), entryNames(bundle).toString());
  }

  @Test
  void keyChangesWhenTheTruststoreChanges() throws IOException {
    var truststore = this.write(this.root.resolve("truststore/cacerts"), "trusted");
    var before = RuntimeBundle.collect(List.of(), this.libs, this.wrapper, truststore).objectKey();

    Files.writeString(truststore, "trusted and one more CA");
    var after = RuntimeBundle.collect(List.of(), this.libs, this.wrapper, truststore).objectKey();

    assertNotEquals(before, after, "the hash must cover the truststore, or a new CA would never reach the pod");
  }

  @Test
  void keyWithATruststoreDiffersFromTheKeyWithout() throws IOException {
    var truststore = this.write(this.root.resolve("truststore/cacerts"), "trusted");

    var without = this.collect(List.of()).objectKey();
    var with = RuntimeBundle.collect(List.of(), this.libs, this.wrapper, truststore).objectKey();

    assertNotEquals(without, with);
  }

  @Test
  void packedArchiveHoldsTheWrapperAndLibrariesUnderRelativePaths() throws Exception {
    var jar = this.write(this.libs.resolve("a.jar"), "a");
    var bundle = this.collect(List.of("-cp", jar.toString()));

    var buffer = new ByteArrayOutputStream();
    bundle.pack(buffer);

    var names = new ArrayList<String>();
    try (var in = new TarArchiveInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
      var entry = in.getNextEntry();
      while (entry != null) {
        names.add(entry.getName());
        entry = in.getNextEntry();
      }
    }
    assertEquals(List.of("libs/a.jar", "wrapper.jar"), names);
  }
}
