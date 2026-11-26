package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.driver.document.DocumentFactory;
import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.driver.language.I18n;
import eu.cloudnetservice.driver.module.ModuleLifeCycle;
import eu.cloudnetservice.driver.module.ModuleTask;
import eu.cloudnetservice.driver.module.driver.DriverModule;
import eu.cloudnetservice.driver.provider.ServiceTaskProvider;
import eu.cloudnetservice.driver.registry.Service;
import eu.cloudnetservice.modules.kubernetes.config.KubernetesConfiguration;
import eu.cloudnetservice.modules.kubernetes.impl.client.KubernetesClientFactory;
import eu.cloudnetservice.modules.kubernetes.impl.listener.NodeSelectListener;
import eu.cloudnetservice.modules.kubernetes.impl.pod.PodSpecBuilder;
import eu.cloudnetservice.modules.kubernetes.impl.staging.ServiceDirectoryStager;
import eu.cloudnetservice.modules.kubernetes.impl.state.PodStateCache;
import eu.cloudnetservice.node.cluster.NodeServer;
import eu.cloudnetservice.node.cluster.NodeServerProvider;
import eu.cloudnetservice.node.config.Configuration;
import eu.cloudnetservice.node.impl.tick.DefaultTickLoop;
import eu.cloudnetservice.node.impl.version.ServiceVersionProvider;
import eu.cloudnetservice.node.service.CloudServiceManager;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.inject.Singleton;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Singleton
public final class KubernetesServicesModule extends DriverModule {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesServicesModule.class);

  private KubernetesConfiguration configuration;
  private KubernetesClient client;
  private PodStateCache stateCache;
  private ServiceDirectoryStager stager;
  private ExecutorService executor;
  private NodeSelectListener nodeSelectListener;

  /** Higher order runs first, so the configuration is present before the factory. */
  @ModuleTask(order = 32)
  public void loadConfiguration() {
    this.configuration = this.readConfig(
      KubernetesConfiguration.class,
      () -> KubernetesConfiguration.builder().build(),
      DocumentFactory.json());
  }

  @ModuleTask(order = 22)
  public void registerServiceFactory(
    @Service I18n i18n,
    DefaultTickLoop tickLoop,
    Configuration nodeConfig,
    CloudServiceManager serviceManager,
    NodeServerProvider nodeServerProvider,
    EventManager eventManager,
    ServiceVersionProvider versionProvider,
    ServiceTaskProvider serviceTaskProvider
  ) {
    var nodeUniqueId = nodeConfig.identity().uniqueId();
    this.client = KubernetesClientFactory.create(this.configuration.namespace());
    this.executor = Executors.newVirtualThreadPerTaskExecutor();
    this.stager = new ServiceDirectoryStager(this.configuration.staging());

    var owner = this.ensureOwnerConfigMap(nodeUniqueId);

    var clock = Clock.systemUTC();
    this.stateCache = new PodStateCache(this.client, this.configuration, clock);
    this.stateCache.start(nodeUniqueId);
    this.reconcileOrphanedPods(serviceManager, nodeUniqueId);

    var factory = new KubernetesLocalCloudServiceFactory(
      i18n,
      tickLoop,
      nodeConfig,
      eventManager,
      versionProvider,
      serviceTaskProvider,
      this.client,
      this.configuration,
      new PodSpecBuilder(this.configuration),
      this.stateCache,
      this.stager,
      this.executor,
      clock,
      nodeUniqueId,
      owner.getMetadata().getName(),
      owner.getMetadata().getUid());

    serviceManager.addCloudServiceFactory(this.configuration.factoryName(), factory);
    this.nodeSelectListener = registerNodeSelectListener(
      eventManager, this.configuration.factoryName(), nodeServerProvider.localNode());
    LOGGER.info("registered kubernetes runtime '{}' in namespace {}",
      this.configuration.factoryName(), this.configuration.namespace());
  }

  /**
   * Pins services of our runtime to this node. Without it CloudNet may hand a service
   * to a node that never registered the factory.
   */
  static NodeSelectListener registerNodeSelectListener(
    EventManager eventManager,
    String factoryName,
    NodeServer localNode
  ) {
    var listener = new NodeSelectListener(factoryName, localNode);
    eventManager.registerListener(listener);
    return listener;
  }

  /**
   * Owner anchor for every pod and secret this node creates.
   *
   * <p>A ConfigMap rather than the node pod itself: a rolling update replaces the node
   * pod and changes its UID, at which point garbage collection would take every running
   * gameserver down mid-match. The ConfigMap survives restarts and is only removed when
   * the node leaves the cluster for good.
   */
  private ConfigMap ensureOwnerConfigMap(String nodeUniqueId) {
    var name = "cloudnet-node-" + nodeUniqueId.toLowerCase() + "-owner";
    var namespace = this.configuration.namespace();

    var existing = this.client.configMaps().inNamespace(namespace).withName(name).get();
    if (existing != null) {
      return existing;
    }

    return this.client.configMaps().inNamespace(namespace).resource(new ConfigMapBuilder()
      .withNewMetadata()
        .withName(name)
        .withNamespace(namespace)
        .withLabels(Map.of(
          "cloudnet.eu/component", "node-owner",
          PodStateCache.LABEL_MANAGED_BY, nodeUniqueId))
      .endMetadata()
      .build()).create();
  }

  /**
   * Startup reconciliation: pods carrying our label that no local service claims are
   * removed, along with their secret and staging archive.
   */
  private void reconcileOrphanedPods(CloudServiceManager serviceManager, String nodeUniqueId) {
    var resources = new PodResources(this.client, this.configuration.namespace(), this.stager::delete);
    new OrphanReaper(this.client, this.configuration.namespace(), resources).reap(
      nodeUniqueId,
      serviceId -> serviceManager.localCloudServices().stream()
        .anyMatch(service -> service.serviceId().uniqueId().toString().equals(serviceId)));
  }

  @ModuleTask(lifecycle = ModuleLifeCycle.STOPPED)
  public void unregisterServiceFactory(CloudServiceManager serviceManager, EventManager eventManager) {
    if (this.nodeSelectListener != null) {
      eventManager.unregisterListener(this.nodeSelectListener);
      this.nodeSelectListener = null;
    }
    if (this.configuration != null) {
      serviceManager.removeCloudServiceFactory(this.configuration.factoryName());
    }
    if (this.stateCache != null) {
      this.stateCache.close();
    }
    if (this.stager != null) {
      this.stager.close();
    }
    if (this.executor != null) {
      this.executor.shutdownNow();
    }
    if (this.client != null) {
      this.client.close();
    }
  }
}
