package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.driver.document.property.DocProperty;
import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.language.I18n;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.registry.Service;
import eu.cloudnetservice.driver.service.ServiceConfiguration;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.config.TaskKubernetesConfig;
import eu.cloudnetservice.modules.kubernetes.impl.config.KubernetesBindPreparer;
import eu.cloudnetservice.modules.kubernetes.impl.naming.ResourceNaming;
import eu.cloudnetservice.modules.kubernetes.impl.pod.PodSpecBuilder;
import eu.cloudnetservice.modules.kubernetes.impl.staging.ServiceDirectoryStager;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import eu.cloudnetservice.node.config.Configuration;
import eu.cloudnetservice.node.impl.service.InternalCloudServiceManager;
import eu.cloudnetservice.node.impl.service.defaults.factory.BaseLocalCloudServiceFactory;
import eu.cloudnetservice.node.impl.tick.DefaultTickLoop;
import eu.cloudnetservice.node.impl.version.ServiceVersionProvider;
import eu.cloudnetservice.node.service.CloudService;
import eu.cloudnetservice.node.service.CloudServiceManager;
import io.fabric8.kubernetes.client.KubernetesClient;
import java.util.concurrent.ExecutorService;

public final class KubernetesLocalCloudServiceFactory extends BaseLocalCloudServiceFactory {

  static final DocProperty<TaskKubernetesConfig> TASK_CONFIG =
    DocProperty.property(TaskKubernetesConfig.PROPERTY_KEY, TaskKubernetesConfig.class);

  private final I18n i18n;
  private final DefaultTickLoop mainThread;
  private final EventManager eventManager;
  private final ServiceTaskProvider serviceTaskProvider;
  private final KubernetesClient client;
  private final KubernetesConfiguration kubernetesConfig;
  private final PodSpecBuilder podSpecBuilder;
  private final PodStateCache stateCache;
  private final ServiceDirectoryStager stager;
  private final ExecutorService executor;
  private final String nodeUniqueId;
  private final String ownerConfigMapName;
  private final String ownerConfigMapUid;

  public KubernetesLocalCloudServiceFactory(
    @Service I18n i18n,
    DefaultTickLoop tickLoop,
    Configuration nodeConfig,
    EventManager eventManager,
    ServiceVersionProvider versionProvider,
    ServiceTaskProvider serviceTaskProvider,
    KubernetesClient client,
    KubernetesConfiguration kubernetesConfig,
    PodSpecBuilder podSpecBuilder,
    PodStateCache stateCache,
    ServiceDirectoryStager stager,
    ExecutorService executor,
    String nodeUniqueId,
    String ownerConfigMapName,
    String ownerConfigMapUid
  ) {
    super(nodeConfig, versionProvider);
    this.i18n = i18n;
    this.mainThread = tickLoop;
    this.eventManager = eventManager;
    this.serviceTaskProvider = serviceTaskProvider;
    this.client = client;
    this.kubernetesConfig = kubernetesConfig;
    this.podSpecBuilder = podSpecBuilder;
    this.stateCache = stateCache;
    this.stager = stager;
    this.executor = executor;
    this.nodeUniqueId = nodeUniqueId;
    this.ownerConfigMapName = ownerConfigMapName;
    this.ownerConfigMapUid = ownerConfigMapUid;
  }

  @Override
  public String name() {
    return this.kubernetesConfig.factoryName();
  }

  /**
   * The node resolves a host address for the service; in our case that has to be the
   * pod's future DNS name, which hostname + subdomain make predictable before the pod
   * exists. Overriding this rather than validateConfiguration keeps the node id and
   * environment resolution the base class performs.
   */
  @Override
  protected String resolveHostAddress(ServiceConfiguration serviceConfiguration) {
    var podName = ResourceNaming.podName(
      serviceConfiguration.serviceId().name(),
      serviceConfiguration.serviceId().uniqueId());
    return ResourceNaming.podFqdn(
      podName,
      this.kubernetesConfig.subdomain(),
      this.kubernetesConfig.namespace(),
      this.kubernetesConfig.clusterDomain());
  }

  /**
   * The inherited implementation probes ports by binding a ServerSocket in the node's
   * network namespace. Every pod has its own IP, so collisions cannot occur and the
   * probe would only ever produce false positives.
   */
  @Override
  protected int findFreeServicePort(
    CloudServiceManager manager,
    ServiceConfiguration configuration,
    String hostAddress
  ) {
    var port = configuration.port();
    return port > 0 ? port : 25565;
  }

  @Override
  public CloudService createCloudService(CloudServiceManager manager, ServiceConfiguration configuration) {
    var config = this.validateConfiguration(manager, configuration);
    var preparer = manager.servicePreparer(config.serviceId().environment());

    return new KubernetesService(
      this.i18n,
      this.mainThread,
      this.configuration,
      config,
      (InternalCloudServiceManager) manager,
      this.eventManager,
      this.versionProvider,
      // Decorated so the bind address is corrected after the environment's own setup.
      new KubernetesBindPreparer(preparer),
      this.client,
      this.kubernetesConfig,
      this.podSpecBuilder,
      this.stateCache,
      this.stager,
      this.executor,
      this.nodeUniqueId,
      this.ownerConfigMapName,
      this.ownerConfigMapUid,
      this.resolveTaskImage(config));
  }

  /** Service configuration wins over the task, mirroring the docker module. */
  private String resolveTaskImage(ServiceConfiguration configuration) {
    var fromService = configuration.readProperty(TASK_CONFIG);
    if (fromService != null && fromService.image() != null) {
      return fromService.image();
    }

    var task = this.serviceTaskProvider.serviceTask(configuration.serviceId().taskName());
    if (task != null) {
      var fromTask = task.readProperty(TASK_CONFIG);
      if (fromTask != null) {
        return fromTask.image();
      }
    }
    return null;
  }
}
