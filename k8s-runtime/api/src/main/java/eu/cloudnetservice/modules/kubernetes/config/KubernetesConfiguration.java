package eu.cloudnetservice.modules.kubernetes.config;

/**
 * Module wide configuration, stored as {@code config.json} in the module data folder.
 */
public record KubernetesConfiguration(
  String factoryName,
  String namespace,
  String clusterDomain,
  String subdomain,
  String serviceImage,
  String nodeServiceDns,
  int nodeListenerPort,
  int terminationGracePeriodSeconds,
  long activeDeadlineSeconds,
  double memoryOverheadFactor,
  int memoryOverheadFloorMib,
  int defaultCpuRequest,
  StagingConfiguration staging
) {

  public static Builder builder() {
    return new Builder();
  }

  /**
   * Heap plus non-heap reserve. Setting the container limit equal to the heap is the
   * classic mistake: metaspace, thread stacks, direct buffers and netty allocations
   * live outside it and the container gets OOM killed instead of the JVM throwing.
   */
  public int memoryLimitMib(int maxHeapMemoryMib) {
    var overhead = Math.max(
      this.memoryOverheadFloorMib,
      (int) (maxHeapMemoryMib * (this.memoryOverheadFactor - 1.0d)));
    return maxHeapMemoryMib + overhead;
  }

  public static final class Builder {

    private String factoryName = "k8s-jvm";
    private String namespace = "cloudnet";
    private String clusterDomain = "cluster.local";
    private String subdomain = "cloudnet-services";
    private String serviceImage = "ghcr.io/onelitefeathernet/cloudnet-service:4.0.0-RC17";
    private String nodeServiceDns = "cloudnet-node.cloudnet.svc.cluster.local";
    private int nodeListenerPort = 1410;
    // Must exceed CloudNet's own processTerminationTimeoutSeconds, otherwise
    // Kubernetes SIGKILLs the server while it is still writing level.dat.
    private int terminationGracePeriodSeconds = 120;
    private long activeDeadlineSeconds = 86400L;
    private double memoryOverheadFactor = 1.35d;
    private int memoryOverheadFloorMib = 512;
    private int defaultCpuRequest = 2;
    private StagingConfiguration staging = StagingConfiguration.builder().build();

    public Builder factoryName(String factoryName) {
      this.factoryName = factoryName;
      return this;
    }

    public Builder namespace(String namespace) {
      this.namespace = namespace;
      return this;
    }

    public Builder clusterDomain(String clusterDomain) {
      this.clusterDomain = clusterDomain;
      return this;
    }

    public Builder subdomain(String subdomain) {
      this.subdomain = subdomain;
      return this;
    }

    public Builder serviceImage(String serviceImage) {
      this.serviceImage = serviceImage;
      return this;
    }

    public Builder nodeServiceDns(String nodeServiceDns) {
      this.nodeServiceDns = nodeServiceDns;
      return this;
    }

    public Builder nodeListenerPort(int nodeListenerPort) {
      this.nodeListenerPort = nodeListenerPort;
      return this;
    }

    public Builder terminationGracePeriodSeconds(int seconds) {
      this.terminationGracePeriodSeconds = seconds;
      return this;
    }

    public Builder activeDeadlineSeconds(long seconds) {
      this.activeDeadlineSeconds = seconds;
      return this;
    }

    public Builder staging(StagingConfiguration staging) {
      this.staging = staging;
      return this;
    }

    public KubernetesConfiguration build() {
      return new KubernetesConfiguration(
        this.factoryName, this.namespace, this.clusterDomain, this.subdomain,
        this.serviceImage, this.nodeServiceDns, this.nodeListenerPort,
        this.terminationGracePeriodSeconds, this.activeDeadlineSeconds,
        this.memoryOverheadFactor, this.memoryOverheadFloorMib, this.defaultCpuRequest,
        this.staging);
    }
  }
}
