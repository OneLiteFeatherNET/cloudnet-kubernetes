package eu.cloudnetservice.modules.kubernetes.impl.staging;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.StagingConfiguration;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

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

  @Test
  void uploadsTheRuntimeBundleWhenItsObjectIsMissing() throws Exception {
    var s3 = new FakeS3();
    var bundle = this.runtimeBundle();

    var key = new ServiceDirectoryStager(this.staging(), s3.client()).ensureRuntime(bundle);

    assertEquals(bundle.objectKey(), key);
    assertEquals(List.of(bundle.objectKey()), s3.uploads);
  }

  @Test
  void skipsTheUploadWhenTheRuntimeBundleExistsAlready() throws Exception {
    var s3 = new FakeS3();
    var bundle = this.runtimeBundle();
    s3.objects.add(bundle.objectKey());

    new ServiceDirectoryStager(this.staging(), s3.client()).ensureRuntime(bundle);

    assertTrue(s3.uploads.isEmpty(), "shared runtime bundles must not be uploaded twice: " + s3.uploads);
  }

  @Test
  void deletingARuntimeBundleIsRefused() throws Exception {
    var s3 = new FakeS3();
    var stager = new ServiceDirectoryStager(this.staging(), s3.client());

    assertThrows(IllegalArgumentException.class,
      () -> stager.delete("runtime/0123456789abcdef.tar"),
      "runtime bundles are shared by many services and must survive every cleanup");
    assertTrue(s3.deletions.isEmpty(), "no object may be deleted: " + s3.deletions);
  }

  @Test
  void deletingAServiceArchiveIsAllowed() throws Exception {
    var s3 = new FakeS3();

    new ServiceDirectoryStager(this.staging(), s3.client()).delete("services/cn-lobby-1-a3f9c2e1.tar");

    assertEquals(List.of("services/cn-lobby-1-a3f9c2e1.tar"), s3.deletions);
  }

  private StagingConfiguration staging() {
    return StagingConfiguration.builder().bucket("cloudnet-staging").build();
  }

  private RuntimeBundle runtimeBundle() throws Exception {
    var wrapper = Files.writeString(this.source.resolve("wrapper.jar"), "wrapper");
    return RuntimeBundle.collect(List.of(), this.source.resolve("libs"), wrapper, null);
  }

  /** Records calls on a minimal in-memory bucket, without a mocking library. */
  private static final class FakeS3 {

    final Set<String> objects = new HashSet<>();
    final List<String> uploads = new ArrayList<>();
    final List<String> deletions = new ArrayList<>();

    S3Client client() {
      return (S3Client) Proxy.newProxyInstance(
        S3Client.class.getClassLoader(),
        new Class<?>[]{S3Client.class},
        (proxy, method, args) -> this.handle(method, args));
    }

    private Object handle(Method method, Object[] args) {
      return switch (method.getName()) {
        case "headObject" -> {
          var key = ((HeadObjectRequest) args[0]).key();
          if (!this.objects.contains(key)) {
            throw S3Exception.builder().statusCode(404).message("not found").build();
          }
          yield HeadObjectResponse.builder().build();
        }
        case "putObject" -> {
          var key = ((PutObjectRequest) args[0]).key();
          this.objects.add(key);
          this.uploads.add(key);
          yield PutObjectResponse.builder().build();
        }
        case "deleteObject" -> {
          this.deletions.add(((DeleteObjectRequest) args[0]).key());
          yield DeleteObjectResponse.builder().build();
        }
        case "close" -> null;
        default -> throw new UnsupportedOperationException(method.getName());
      };
    }
  }
}
