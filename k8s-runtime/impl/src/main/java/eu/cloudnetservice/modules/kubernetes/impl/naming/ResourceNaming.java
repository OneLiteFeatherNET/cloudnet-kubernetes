package eu.cloudnetservice.modules.kubernetes.impl.naming;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

/**
 * Derives Kubernetes resource names from CloudNet service identities.
 *
 * <p>All names produced here are valid DNS-1123 labels, because they end up as pod
 * names and, through {@code spec.hostname}, as DNS records.
 */
public final class ResourceNaming {

  private static final int MAX_DNS_1123 = 63;
  private static final int SHORT_ID_LENGTH = 8;

  private ResourceNaming() {
  }

  public static String podName(String serviceName, UUID serviceId) {
    var shortId = serviceId.toString().substring(0, SHORT_ID_LENGTH);
    return clamp("cn-" + sanitize(serviceName) + "-" + shortId);
  }

  /**
   * Value of a Kubernetes label, such as {@code cloudnet.eu/task}. Label values are
   * case sensitive and may contain {@code [A-Za-z0-9_.-]}, so unlike a DNS name the
   * task name keeps its case; only the characters outside that set are replaced.
   * Values must start and end alphanumeric and stay within 63 characters.
   */
  public static String labelValue(String raw) {
    var cleaned = raw.replaceAll("[^A-Za-z0-9_.-]", "-")
      .replaceAll("^[^A-Za-z0-9]+", "")
      .replaceAll("[^A-Za-z0-9]+$", "");
    if (cleaned.isEmpty()) {
      return "task-" + shortHash(raw);
    }
    if (cleaned.length() <= MAX_DNS_1123) {
      return cleaned;
    }

    var suffix = "-" + shortHash(cleaned);
    var head = cleaned.substring(0, MAX_DNS_1123 - suffix.length()).replaceAll("[^A-Za-z0-9]+$", "");
    return head + suffix;
  }

  public static String secretName(String podName) {
    return clamp(podName + "-wrapper");
  }

  /** Object key of the staging archive a pod unpacks its service directory from. */
  public static String stagingKey(String podName) {
    return "services/" + podName + ".tar";
  }

  /**
   * Deliberately derived from the service name only. Including the uniqueId would
   * hand a static service a fresh, empty volume on every start - mirroring how
   * {@code AbstractService#resolveServicePath} treats static versus dynamic services.
   */
  public static String pvcName(String serviceName) {
    return clamp("cn-" + sanitize(serviceName) + "-data");
  }

  public static String podFqdn(String podName, String subdomain, String namespace, String clusterDomain) {
    return podName + "." + subdomain + "." + namespace + ".svc." + clusterDomain;
  }

  private static String sanitize(String raw) {
    var lower = raw.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
    return lower.replaceAll("-{2,}", "-").replaceAll("^-+", "").replaceAll("-+$", "");
  }

  private static String clamp(String candidate) {
    if (candidate.length() <= MAX_DNS_1123) {
      return candidate;
    }

    // Truncating alone could collide; a stable hash keeps distinct inputs distinct.
    var suffix = "-" + shortHash(candidate);
    var head = candidate.substring(0, MAX_DNS_1123 - suffix.length()).replaceAll("-+$", "");
    return head + suffix;
  }

  private static String shortHash(String input) {
    try {
      var digest = MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest).substring(0, SHORT_ID_LENGTH);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 unavailable", exception);
    }
  }
}
