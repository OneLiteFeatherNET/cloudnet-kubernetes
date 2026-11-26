package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.language.I18n;
import eu.cloudnetservice.driver.network.HostAndPort;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.console.PodConsoleInput;
import eu.cloudnetservice.modules.kubernetes.impl.console.PodLogStream;
import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import eu.cloudnetservice.modules.kubernetes.impl.pod.ArgumentRewriter;
import eu.cloudnetservice.modules.kubernetes.impl.pod.PodSpecBuilder;
import eu.cloudnetservice.modules.kubernetes.impl.staging.ServiceDirectoryStager;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import eu.cloudnetservice.node.config.Configuration;
import eu.cloudnetservice.node.impl.service.InternalCloudServiceManager;
import eu.cloudnetservice.node.impl.service.defaults.JVMService;
import eu.cloudnetservice.node.impl.tick.DefaultTickLoop;
import eu.cloudnetservice.node.impl.version.ServiceVersionProvider;
import eu.cloudnetservice.node.service.ServiceConfigurationPreparer;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs a CloudNet service as a Kubernetes pod.
 *
 * <p>Extends {@link JVMService} for the same reason the docker module does: the whole
 * argument construction - JVM flags, javaagent, classpath, main class - is inherited
 * unchanged, and only {@code doStartProcess} and its four siblings are replaced.
 */
public final class KubernetesService extends JVMService {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesService.class);
  private static final Path WRAPPER_CONFIG_PATH = Path.of(".wrapper", "wrapper.json");
  private static final String WRAPPER_MOUNT = "/cloudnet/wrapper/wrapper.json";

  /**
   * A pod in Pending is a state docker never had. Without a grace window the watchdog
   * would stop a service that is merely still being scheduled.
   */
  private static final Duration STARTUP_GRACE = Duration.ofMinutes(3);

  private final KubernetesClient client;
  private final KubernetesConfiguration kubernetesConfig;
  private final PodSpecBuilder podSpecBuilder;
  private final PodStateCache stateCache;
  private final ServiceDirectoryStager stager;
  private final ExecutorService executor;
  private final Clock clock;
  private final String nodeUniqueId;
  private final String ownerConfigMapName;
  private final String ownerConfigMapUid;
  private final String taskImage;
  private final KubernetesServiceLogCache kubernetesLogCache;

  private volatile String podName;
  private volatile Instant startedAt;
  private volatile String stagingKey;
  private volatile PodConsoleInput console;
  private volatile PodLogStream logStream;

  public KubernetesService(
    I18n i18n,
    DefaultTickLoop tickLoop,
    Configuration nodeConfig,
    ServiceConfiguration configuration,
    InternalCloudServiceManager manager,
    EventManager eventManager,
    ServiceVersionProvider versionProvider,
    ServiceConfigurationPreparer serviceConfigurationPreparer,
    KubernetesClient client,
    KubernetesConfiguration kubernetesConfig,
    PodSpecBuilder podSpecBuilder,
    PodStateCache stateCache,
    ServiceDirectoryStager stager,
    ExecutorService executor,
    Clock clock,
    String nodeUniqueId,
    String ownerConfigMapName,
    String ownerConfigMapUid,
    String taskImage
  ) {
    var logCache = new KubernetesServiceLogCache(nodeConfig, configuration.serviceId());
    super(
      i18n,
      tickLoop,
      nodeConfig,
      configuration,
      manager,
      eventManager,
      logCache,
      versionProvider,
      serviceConfigurationPreparer);

    this.kubernetesLogCache = logCache;
    this.client = client;
    this.kubernetesConfig = kubernetesConfig;
    this.podSpecBuilder = podSpecBuilder;
    this.stateCache = stateCache;
    this.stager = stager;
    this.executor = executor;
    this.clock = clock;
    this.nodeUniqueId = nodeUniqueId;
    this.ownerConfigMapName = ownerConfigMapName;
    this.ownerConfigMapUid = ownerConfigMapUid;
    this.taskImage = taskImage;
  }

  @Override
  public String runtime() {
    return this.kubernetesConfig.factoryName();
  }

  /**
   * Answered from the informer cache. The watchdog calls this once per second for
   * every local service, so an API call here would be an outage waiting to happen.
   */
  @Override
  public boolean alive() {
    // Still scheduling or pulling is not dead; a Failed or Succeeded pod is, even early on.
    return this.stateCache.alive(
      this.serviceId().uniqueId().toString(),
      this.startedAt,
      STARTUP_GRACE);
  }

  @Override
  public void runCommand(String command) {
    var current = this.console;
    if (current == null) {
      return;
    }
    try {
      current.runCommand(command);
    } catch (IllegalStateException exception) {
      LOGGER.debug("unable to deliver console command to {}", this.podName, exception);
    }
  }

  @Override
  protected void doStartProcess(List<String> arguments, Path wrapperPath, Path applicationFilePath) {
    var name = ResourceNaming.podName(this.serviceId().name(), this.serviceId().uniqueId());
    this.podName = name;
    this.startedAt = this.clock.instant();

    try {
      // 1) Hand the directory prepareService() just built over to the pod. Bind mounts
      //    with identical host and container paths, which is how the docker module
      //    solves this, cannot exist across hosts.
      this.stagingKey = this.stager.upload(this.serviceDirectory, "services/" + name + ".tar");

      // 2) The connection key ships separately as a secret. This works because the
      //    wrapper reads its config path from an overridable system property.
      this.createWrapperSecret(name);

      // 3) The inherited command line points at absolute node paths for the
      //    launcher libraries, the wrapper jar and the service directory. None of
      //    those exist in the pod, so they are mapped onto the image layout.
      var command = new ArrayList<>(ArgumentRewriter.rewrite(
        arguments, LIB_PATH, wrapperPath, this.serviceDirectory));
      command.add(1, "-Dcloudnet.wrapper.config.path=" + WRAPPER_MOUNT);

      var pod = this.podSpecBuilder.build(new PodSpecBuilder.PodRequest(
        this.serviceId().name(),
        this.serviceId().uniqueId(),
        this.serviceId().taskName(),
        this.serviceConfiguration().processConfig().maxHeapMemorySize(),
        this.serviceConfiguration().port(),
        this.nodeUniqueId,
        this.ownerConfigMapName,
        this.ownerConfigMapUid,
        this.taskImage,
        this.stagingKey,
        command));

      this.client.pods().inNamespace(this.kubernetesConfig.namespace()).resource(pod).create();

      // 4) Console last, so a failed pod creation leaves no dangling streams.
      this.console = new PodConsoleInput(this.client, this.kubernetesConfig.namespace(), name);
      this.logStream = new PodLogStream(this.client, this.kubernetesConfig.namespace(), name);
      this.logStream.start(this.executor, this.kubernetesLogCache::acceptLine);
    } catch (Exception exception) {
      this.cleanupResources(name);
      throw new IllegalStateException("unable to start pod " + name, exception);
    }
  }

  private void createWrapperSecret(String name) throws Exception {
    var wrapperJson = Files.readAllBytes(this.serviceDirectory.resolve(WRAPPER_CONFIG_PATH));
    var secret = new SecretBuilder()
      .withNewMetadata()
        .withName(ResourceNaming.secretName(name))
        .withNamespace(this.kubernetesConfig.namespace())
        .withLabels(Map.of(
          PodStateCache.LABEL_MANAGED_BY, this.nodeUniqueId,
          PodStateCache.LABEL_SERVICE_ID, this.serviceId().uniqueId().toString()))
        .addNewOwnerReference()
          .withApiVersion("v1")
          .withKind("ConfigMap")
          .withName(this.ownerConfigMapName)
          .withUid(this.ownerConfigMapUid)
          .withController(false)
          .withBlockOwnerDeletion(false)
        .endOwnerReference()
      .endMetadata()
      .withType("Opaque")
      .withData(Map.of("wrapper.json", Base64.getEncoder().encodeToString(wrapperJson)))
      .build();

    this.client.secrets().inNamespace(this.kubernetesConfig.namespace()).resource(secret).create();
  }

  /**
   * Console first, then SIGTERM as a backstop. SIGTERM alone saves the world but does
   * not disable plugins cleanly, so the in-game stop command still matters.
   */
  @Override
  protected void stopProcess() {
    this.runCommand("end");
    this.runCommand("stop");

    var stream = this.logStream;
    if (stream != null) {
      stream.stop();
      this.logStream = null;
    }

    var input = this.console;
    if (input != null) {
      input.close();
      this.console = null;
    }

    var name = this.podName;
    if (name != null) {
      this.client.pods()
        .inNamespace(this.kubernetesConfig.namespace())
        .withName(name)
        .withGracePeriod(this.kubernetesConfig.terminationGracePeriodSeconds())
        .delete();
    }
  }

  @Override
  public void doDelete() {
    // super runs the deployments and removes the local directory first.
    super.doDelete();
    this.cleanupResources(this.podName);
    this.podName = null;
  }

  private void cleanupResources(String name) {
    if (name != null) {
      try {
        var namespace = this.kubernetesConfig.namespace();
        this.client.pods().inNamespace(namespace).withName(name).delete();
        this.client.secrets().inNamespace(namespace).withName(ResourceNaming.secretName(name)).delete();
      } catch (Exception exception) {
        LOGGER.debug("unable to remove kubernetes resources for {}", name, exception);
      }
    }

    var key = this.stagingKey;
    if (key != null) {
      try {
        this.stager.delete(key);
      } catch (Exception exception) {
        LOGGER.debug("unable to remove staging object {}", key, exception);
      }
      this.stagingKey = null;
    }
  }

  /**
   * 127.0.0.1 points at the pod itself, so the inherited loopback rewrite would leave
   * the wrapper with nowhere to connect. The node's cluster DNS name is used instead.
   */
  @Override
  protected HostAndPort selectConnectListener(List<HostAndPort> listeners) {
    return new HostAndPort(
      this.kubernetesConfig.nodeServiceDns(),
      this.kubernetesConfig.nodeListenerPort());
  }
}
