package eu.cloudnetservice.modules.kubernetes.impl.staging;

import eu.cloudnetservice.modules.kubernetes.config.StagingConfiguration;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Moves the service directory the node prepared locally into object storage, so an
 * init container can unpack it inside the pod.
 *
 * <p>The docker module gets away with a bind mount that maps a host path onto an
 * identical container path. That cannot exist when the pod runs on another host, so
 * the archive travels instead.
 */
public final class ServiceDirectoryStager implements AutoCloseable {

  /**
   * The wrapper jar and the wrapper config never travel in the archive: the image
   * already carries the wrapper, and the connection key ships as a secret.
   */
  private static final Set<String> EXCLUDED_FILES = Set.of("wrapper.jar");
  private static final String EXCLUDED_PREFIX = ".wrapper/";
  /** Prefix of the per-pod service archives; runtime bundles live under another prefix. */
  private static final String SERVICE_KEY_PREFIX = "services/";

  private final StagingConfiguration config;
  private final S3Client s3;

  public ServiceDirectoryStager(StagingConfiguration config) {
    this(config, S3Client.builder()
      .endpointOverride(URI.create(config.endpoint()))
      .forcePathStyle(config.pathStyleAccess())
      .region(Region.US_EAST_1)
      .credentialsProvider(StaticCredentialsProvider.create(
        AwsBasicCredentials.create(config.accessKey(), config.secretKey())))
      .build());
  }

  ServiceDirectoryStager(StagingConfiguration config, S3Client s3) {
    this.config = config;
    this.s3 = s3;
  }

  public static void pack(Path source, OutputStream target) throws IOException {
    try (var tar = new TarArchiveOutputStream(target)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      try (Stream<Path> walk = Files.walk(source)) {
        walk.filter(Files::isRegularFile).forEach(file -> {
          var relative = source.relativize(file).toString().replace('\\', '/');
          if (EXCLUDED_FILES.contains(relative) || relative.startsWith(EXCLUDED_PREFIX)) {
            return;
          }
          try {
            tar.putArchiveEntry(new TarArchiveEntry(file.toFile(), relative));
            Files.copy(file, tar);
            tar.closeArchiveEntry();
          } catch (IOException exception) {
            throw new UncheckedIOException(exception);
          }
        });
      } catch (UncheckedIOException exception) {
        throw exception.getCause();
      }
    }
  }

  public String upload(Path serviceDirectory, String objectKey) throws IOException {
    var archive = Files.createTempFile("cloudnet-staging-", ".tar");
    try {
      try (var out = Files.newOutputStream(archive)) {
        pack(serviceDirectory, out);
      }
      this.s3.putObject(
        PutObjectRequest.builder().bucket(this.config.bucket()).key(objectKey).build(),
        RequestBody.fromFile(archive));
      return objectKey;
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  /**
   * Uploads the runtime bundle unless an object with its content-derived key exists
   * already. Two services with the same runtime find the same object, so the second one
   * uploads nothing. Concurrent uploads of identical content are harmless.
   *
   * @return the object key the pod has to fetch
   */
  public String ensureRuntime(RuntimeBundle bundle) throws IOException {
    var objectKey = bundle.objectKey();
    if (this.exists(objectKey)) {
      return objectKey;
    }

    var archive = Files.createTempFile("cloudnet-runtime-", ".tar");
    try {
      try (var out = Files.newOutputStream(archive)) {
        bundle.pack(out);
      }
      this.s3.putObject(
        PutObjectRequest.builder().bucket(this.config.bucket()).key(objectKey).build(),
        RequestBody.fromFile(archive));
      return objectKey;
    } finally {
      Files.deleteIfExists(archive);
    }
  }

  private boolean exists(String objectKey) {
    try {
      this.s3.headObject(HeadObjectRequest.builder().bucket(this.config.bucket()).key(objectKey).build());
      return true;
    } catch (S3Exception exception) {
      if (exception.statusCode() == 404) {
        return false;
      }
      throw exception;
    }
  }

  /**
   * Removes the archive of a single service. Runtime bundles are shared by every service
   * with the same runtime, so they are refused here rather than deleted by accident.
   */
  public void delete(String objectKey) {
    if (!objectKey.startsWith(SERVICE_KEY_PREFIX)) {
      throw new IllegalArgumentException("only service archives may be deleted, refusing " + objectKey);
    }
    this.s3.deleteObject(
      DeleteObjectRequest.builder().bucket(this.config.bucket()).key(objectKey).build());
  }

  @Override
  public void close() {
    this.s3.close();
  }
}
