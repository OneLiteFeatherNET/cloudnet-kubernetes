package eu.cloudnetservice.modules.kubernetes.impl.pod;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Translates a CloudNet service into a bare pod.
 *
 * <p>Bare pod rather than Deployment, Job or StatefulSet: every one of those brings a
 * second controller into a lifecycle CloudNet already owns, and a ReplicaSet treats
 * pods as fungible - which is the opposite of "this pod carries forty players".
 */
public final class PodSpecBuilder {

  private final KubernetesConfiguration config;

  public PodSpecBuilder(KubernetesConfiguration config) {
    this.config = config;
  }

  public record PodRequest(
    String serviceName,
    UUID serviceId,
    String taskName,
    int maxHeapMemoryMib,
    int port,
    String nodeUniqueId,
    String ownerConfigMapName,
    String ownerConfigMapUid,
    String image,
    String stagingKey,
    List<String> command
  ) {
  }

  public Pod build(PodRequest request) {
    var podName = ResourceNaming.podName(request.serviceName(), request.serviceId());
    var memory = new Quantity(this.config.memoryLimitMib(request.maxHeapMemoryMib()) + "Mi");
    var image = request.image() != null ? request.image() : this.config.serviceImage();

    return new PodBuilder()
      .withNewMetadata()
        .withName(podName)
        .withNamespace(this.config.namespace())
        .withLabels(Map.of(
          "cloudnet.eu/component", "service",
          "cloudnet.eu/task", request.taskName(),
          "cloudnet.eu/service-id", request.serviceId().toString(),
          "cloudnet.eu/managed-by", request.nodeUniqueId()))
        // Anchored to a long lived ConfigMap, never to the node pod: a rolling update
        // gives the node pod a new UID, and garbage collection would then take every
        // gameserver down mid-match.
        .addNewOwnerReference()
          .withApiVersion("v1")
          .withKind("ConfigMap")
          .withName(request.ownerConfigMapName())
          .withUid(request.ownerConfigMapUid())
          .withController(false)
          .withBlockOwnerDeletion(false)
        .endOwnerReference()
      .endMetadata()
      .withNewSpec()
        // A dead service stays dead; CloudNet creates a new one. Always would restart
        // the container with a connection key the node has already retired.
        .withRestartPolicy("Never")
        // Kubelet-side backstop that still fires when node, module and informer are
        // all gone.
        .withActiveDeadlineSeconds(this.config.activeDeadlineSeconds())
        .withTerminationGracePeriodSeconds((long) this.config.terminationGracePeriodSeconds())
        // hostname + subdomain against a single headless service yields a predictable
        // FQDN, known before the pod exists - which is what prepareService() needs.
        .withHostname(podName)
        .withSubdomain(this.config.subdomain())
        .withAutomountServiceAccountToken(false)
        .withEnableServiceLinks(false)
        .withNewSecurityContext()
          .withRunAsNonRoot(true)
          .withRunAsUser(1000L)
          .withRunAsGroup(1000L)
          .withFsGroup(1000L)
          .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
        .endSecurityContext()
        .addNewVolume()
          .withName("wrapper-config")
          .withNewSecret()
            .withSecretName(ResourceNaming.secretName(podName))
            .withDefaultMode(0400)
          .endSecret()
        .endVolume()
        .addNewVolume()
          .withName("service-dir")
          .withNewEmptyDir().endEmptyDir()
        .endVolume()
        // Unpacks the archive the node uploaded after prepareService(). This runs
        // before the main container, and therefore before the wrapper's 30 second
        // authentication window starts - the transfer never eats into that budget.
        .addNewInitContainer()
          .withName("fetch-service-directory")
          .withImage(this.config.stagingImage())
          .withImagePullPolicy("IfNotPresent")
          .withCommand("/bin/sh", "-c",
            "set -e; "
              + "mc alias set staging \"$S3_ENDPOINT\" \"$S3_ACCESS_KEY\" \"$S3_SECRET_KEY\"; "
              + "mc cp \"staging/" + this.config.staging().bucket() + "/" + request.stagingKey()
              + "\" /tmp/service.tar; "
              + "tar -xf /tmp/service.tar -C /cloudnet/service; "
              + "rm -f /tmp/service.tar")
          .addNewEnv()
            .withName("S3_ENDPOINT")
            .withValue(this.config.staging().endpoint())
          .endEnv()
          .addNewEnv()
            .withName("S3_ACCESS_KEY")
            .withNewValueFrom()
              .withNewSecretKeyRef("accessKey", this.config.staging().credentialsSecretName(), false)
            .endValueFrom()
          .endEnv()
          .addNewEnv()
            .withName("S3_SECRET_KEY")
            .withNewValueFrom()
              .withNewSecretKeyRef("secretKey", this.config.staging().credentialsSecretName(), false)
            .endValueFrom()
          .endEnv()
          .addNewVolumeMount()
            .withName("service-dir")
            .withMountPath("/cloudnet/service")
          .endVolumeMount()
          .withNewSecurityContext()
            .withAllowPrivilegeEscalation(false)
            .withNewCapabilities().withDrop("ALL").endCapabilities()
          .endSecurityContext()
        .endInitContainer()
        .addNewContainer()
          .withName("service")
          .withImage(image)
          // Never Always: pull time eats the wrapper's 30 second auth budget.
          .withImagePullPolicy("IfNotPresent")
          .withCommand(request.command())
          // stdin true is required for attach. stdinOnce MUST stay false - true makes
          // the runtime close stdin on first detach, killing the console for good.
          // tty true would multiplex stdout/stderr and translate CRLF.
          .withStdin(true)
          .withStdinOnce(false)
          .withTty(false)
          .withWorkingDir("/cloudnet/service")
          .addNewPort()
            .withName("minecraft")
            .withContainerPort(request.port())
            .withProtocol("TCP")
          .endPort()
          .withNewResources()
            .addToRequests("memory", memory)
            .addToRequests("cpu", new Quantity(String.valueOf(this.config.defaultCpuRequest())))
            // Memory request equals limit for deterministic behaviour. No CPU limit:
            // CFS throttling produces exactly the micro stutter players read as lag,
            // and chunk generation spikes must be allowed to burst.
            .addToLimits("memory", memory)
          .endResources()
          .addNewVolumeMount()
            .withName("wrapper-config")
            .withMountPath("/cloudnet/wrapper")
            .withReadOnly(true)
          .endVolumeMount()
          .addNewVolumeMount()
            .withName("service-dir")
            .withMountPath("/cloudnet/service")
          .endVolumeMount()
          .withNewSecurityContext()
            .withAllowPrivilegeEscalation(false)
            .withNewCapabilities().withDrop("ALL").endCapabilities()
          .endSecurityContext()
        .endContainer()
      .endSpec()
      .build();
  }
}
