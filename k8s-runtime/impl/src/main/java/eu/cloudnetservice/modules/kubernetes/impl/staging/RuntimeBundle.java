package eu.cloudnetservice.modules.kubernetes.impl.staging;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;

/**
 * The part of the node a pod needs besides its service directory: the wrapper jar, the
 * launcher libraries the JVM command line refers to and optionally a truststore.
 *
 * <p>Packed into a tar with paths relative to a bundle root. An init container unpacks
 * it into {@code /cloudnet/runtime}, which is where {@link
 * eu.cloudnetservice.modules.kubernetes.impl.pod.ArgumentRewriter} points the command line.
 *
 * <p>The object key is derived from the content, so every service running the same
 * runtime shares one object. Runtime bundles are shared, so they are never deleted
 * together with a service.
 */
public final class RuntimeBundle {

  public static final String WRAPPER_ENTRY = "wrapper.jar";
  public static final String LIBS_DIRECTORY = "libs";
  public static final String TRUSTSTORE_ENTRY = "cacerts";

  private static final String KEY_PREFIX = "runtime/";
  private static final String KEY_SUFFIX = ".tar";
  private static final String WILDCARD_SUFFIX = "/*";
  // Characters that separate paths inside a JVM argument: the classpath separator on
  // Linux, the option separator of -javaagent and the value separator of -D properties.
  private static final Pattern REFERENCE_SEPARATOR = Pattern.compile("[:=,]");

  /**
   * One file of the bundle.
   *
   * @param relativePath the path inside the bundle, always with forward slashes
   * @param source       the file on the node
   * @param sha256       hex encoded SHA-256 of the file content
   */
  public record Entry(String relativePath, Path source, String sha256) {

  }

  private final List<Entry> entries;
  private final String objectKey;

  private RuntimeBundle(List<Entry> entries) {
    this.entries = List.copyOf(entries);
    this.objectKey = KEY_PREFIX + contentHash(this.entries) + KEY_SUFFIX;
  }

  /**
   * Collects every file the JVM arguments reference below the node's library directory,
   * together with the wrapper jar. Classpath wildcards and directories are expanded the
   * way the JVM expands them. Paths outside the library directory are not collected: the
   * service directory travels in its own archive, and nothing else is referenced.
   *
   * @param arguments        the JVM command line as the node built it, with absolute paths
   * @param nodeLibDirectory the node's launcher library directory
   * @param nodeWrapperJar   the node's extracted wrapper jar
   * @param truststore       a JVM truststore to ship as {@code cacerts}, or null
   * @return the bundle, its entries sorted by relative path
   * @throws IOException if a referenced file cannot be read
   */
  public static RuntimeBundle collect(
    List<String> arguments,
    Path nodeLibDirectory,
    Path nodeWrapperJar,
    Path truststore
  ) throws IOException {
    var libRoot = nodeLibDirectory.toAbsolutePath().normalize();
    // A sorted map gives the entries their deterministic order.
    var files = new TreeMap<String, Path>();
    files.put(WRAPPER_ENTRY, nodeWrapperJar.toAbsolutePath().normalize());
    for (var argument : arguments) {
      for (var token : REFERENCE_SEPARATOR.split(argument)) {
        collectReference(token, libRoot, files);
      }
    }
    if (truststore != null) {
      files.put(TRUSTSTORE_ENTRY, truststore.toAbsolutePath().normalize());
    }

    var entries = new ArrayList<Entry>(files.size());
    for (var file : files.entrySet()) {
      entries.add(new Entry(file.getKey(), file.getValue(), sha256(file.getValue())));
    }
    return new RuntimeBundle(entries);
  }

  public List<Entry> entries() {
    return this.entries;
  }

  /**
   * The content-addressed object key: {@code runtime/<sha256>.tar}. The hash covers the
   * sorted pairs of relative path and file hash, so it changes with any shipped file.
   */
  public String objectKey() {
    return this.objectKey;
  }

  /** Writes the bundle as an uncompressed tar archive. */
  public void pack(OutputStream target) throws IOException {
    try (var tar = new TarArchiveOutputStream(target)) {
      tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
      for (var entry : this.entries) {
        tar.putArchiveEntry(new TarArchiveEntry(entry.source().toFile(), entry.relativePath()));
        Files.copy(entry.source(), tar);
        tar.closeArchiveEntry();
      }
    }
  }

  private static void collectReference(String token, Path libRoot, Map<String, Path> files) throws IOException {
    if (token.endsWith(WILDCARD_SUFFIX)) {
      // A wildcard matches the jars directly inside its directory, never deeper.
      var directory = belowLibRoot(token.substring(0, token.length() - WILDCARD_SUFFIX.length()), libRoot);
      if (directory != null && Files.isDirectory(directory)) {
        try (var children = Files.list(directory)) {
          children
            .filter(Files::isRegularFile)
            .filter(child -> child.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
            .forEach(child -> add(child, libRoot, files));
        }
      }
      return;
    }

    var path = belowLibRoot(token, libRoot);
    if (path == null) {
      return;
    }
    if (Files.isRegularFile(path)) {
      add(path, libRoot, files);
    } else if (Files.isDirectory(path)) {
      try (var walk = Files.walk(path)) {
        walk.filter(Files::isRegularFile).forEach(file -> add(file, libRoot, files));
      }
    }
    // Anything else is a classpath entry that does not exist. The JVM skips those too.
  }

  /** Absolute paths strictly below the library root; everything else is not ours to ship. */
  private static Path belowLibRoot(String token, Path libRoot) {
    if (token.isEmpty()) {
      return null;
    }
    var path = Path.of(token);
    if (!path.isAbsolute()) {
      return null;
    }
    var normalized = path.normalize();
    return normalized.startsWith(libRoot) && !normalized.equals(libRoot) ? normalized : null;
  }

  private static void add(Path file, Path libRoot, Map<String, Path> files) {
    var relative = libRoot.relativize(file).toString().replace('\\', '/');
    files.putIfAbsent(LIBS_DIRECTORY + "/" + relative, file);
  }

  private static String contentHash(List<Entry> entries) {
    var digest = newDigest();
    for (var entry : entries) {
      digest.update((entry.relativePath() + '\0' + entry.sha256() + '\n').getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String sha256(Path file) throws IOException {
    var digest = newDigest();
    try (var in = new DigestInputStream(Files.newInputStream(file), digest)) {
      in.transferTo(OutputStream.nullOutputStream());
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("every JVM provides SHA-256", exception);
    }
  }
}
