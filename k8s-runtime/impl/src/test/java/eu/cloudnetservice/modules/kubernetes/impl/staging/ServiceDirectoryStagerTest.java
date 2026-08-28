package eu.cloudnetservice.modules.kubernetes.impl.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServiceDirectoryStagerTest {

  @TempDir
  Path source;

  private byte[] pack() throws Exception {
    var buffer = new ByteArrayOutputStream();
    ServiceDirectoryStager.pack(this.source, buffer);
    return buffer.toByteArray();
  }

  private List<String> entryNames() throws Exception {
    var names = new ArrayList<String>();
    try (var in = new TarArchiveInputStream(new ByteArrayInputStream(this.pack()))) {
      var entry = in.getNextEntry();
      while (entry != null) {
        names.add(entry.getName());
        entry = in.getNextEntry();
      }
    }
    return names;
  }

  @Test
  void packsNestedFilesWithRelativePaths() throws Exception {
    Files.createDirectories(this.source.resolve("plugins/Essentials"));
    Files.writeString(this.source.resolve("server.properties"), "server-port=25565");
    Files.writeString(this.source.resolve("plugins/Essentials/config.yml"), "locale: de");

    var names = this.entryNames();

    assertTrue(names.contains("server.properties"), names.toString());
    assertTrue(names.contains("plugins/Essentials/config.yml"), names.toString());
  }

  @Test
  void preservesFileContent() throws Exception {
    Files.writeString(this.source.resolve("eula.txt"), "eula=true");

    try (var in = new TarArchiveInputStream(new ByteArrayInputStream(this.pack()))) {
      var entry = in.getNextEntry();
      assertEquals("eula.txt", entry.getName());
      assertEquals("eula=true", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  @Test
  void skipsTheWrapperJarWhichTheImageAlreadyProvides() throws Exception {
    Files.writeString(this.source.resolve("wrapper.jar"), "binary");
    Files.writeString(this.source.resolve("server.properties"), "x=1");

    var names = this.entryNames();

    assertTrue(names.contains("server.properties"));
    assertFalse(names.contains("wrapper.jar"), "wrapper.jar must not be staged");
  }

  @Test
  void skipsTheWrapperDirectoryBecauseTheConnectionKeyTravelsAsASecret() throws Exception {
    Files.createDirectories(this.source.resolve(".wrapper"));
    Files.writeString(this.source.resolve(".wrapper/wrapper.json"), "{\"connectionKey\":\"secret\"}");
    Files.writeString(this.source.resolve("server.properties"), "x=1");

    var names = this.entryNames();

    assertTrue(names.contains("server.properties"));
    assertFalse(names.contains(".wrapper/wrapper.json"),
      "the connection key must never travel in the staging archive");
  }

  @Test
  void emptyDirectoryProducesAValidEmptyArchive() throws Exception {
    assertTrue(this.entryNames().isEmpty());
  }
}
