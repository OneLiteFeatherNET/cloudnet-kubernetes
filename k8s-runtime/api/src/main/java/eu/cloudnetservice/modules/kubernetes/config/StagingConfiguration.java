package eu.cloudnetservice.modules.kubernetes.config;

/**
 * Object storage used to hand the prepared service directory to a pod.
 *
 * <p>The node packs the directory it built locally and drops it here; an init
 * container pulls and unpacks it. This replaces the identical-path bind mount the
 * docker module relies on, which cannot exist across hosts.
 */
public record StagingConfiguration(
  String endpoint,
  String bucket,
  boolean pathStyleAccess,
  String accessKey,
  String secretKey,
  String credentialsSecretName
) {

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {

    private String endpoint = "http://rook-ceph-rgw-cloudnet-store.rook-ceph.svc";
    private String bucket = "cloudnet-staging";
    // Ceph RGW needs path style access; virtual host style would require wildcard DNS.
    private boolean pathStyleAccess = true;
    private String accessKey = "";
    private String secretKey = "";
    private String credentialsSecretName = "cloudnet-staging-credentials";

    public Builder endpoint(String endpoint) {
      this.endpoint = endpoint;
      return this;
    }

    public Builder bucket(String bucket) {
      this.bucket = bucket;
      return this;
    }

    public Builder pathStyleAccess(boolean pathStyleAccess) {
      this.pathStyleAccess = pathStyleAccess;
      return this;
    }

    public Builder accessKey(String accessKey) {
      this.accessKey = accessKey;
      return this;
    }

    public Builder secretKey(String secretKey) {
      this.secretKey = secretKey;
      return this;
    }

    public Builder credentialsSecretName(String credentialsSecretName) {
      this.credentialsSecretName = credentialsSecretName;
      return this;
    }

    public StagingConfiguration build() {
      return new StagingConfiguration(
        this.endpoint, this.bucket, this.pathStyleAccess,
        this.accessKey, this.secretKey, this.credentialsSecretName);
    }
  }
}
