package eu.cloudnetservice.modules.kubernetes.impl.listener;

import eu.cloudnetservice.driver.event.EventListener;
import eu.cloudnetservice.node.cluster.NodeServer;
import eu.cloudnetservice.node.event.service.CloudServiceNodeSelectEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pins services using our runtime to a node that actually provides the factory.
 *
 * <p>CloudNet's node selection filters by availability, draining, allowed nodes and
 * memory - but not by whether the target node registered the requested runtime. A
 * service landing elsewhere fails there with "No service factory for runtime", which
 * in a mixed cluster is a startup error with no useful message attached.
 */
public final class NodeSelectListener {

  private static final Logger LOGGER = LoggerFactory.getLogger(NodeSelectListener.class);

  private final String factoryName;
  private final NodeServer localNode;

  public NodeSelectListener(String factoryName, NodeServer localNode) {
    this.factoryName = factoryName;
    this.localNode = localNode;
  }

  /** Split out from the handler so the decision is testable without a node. */
  public static boolean handles(String runtime, String factoryName) {
    return factoryName.equals(runtime);
  }

  @EventListener
  public void handleNodeSelect(CloudServiceNodeSelectEvent event) {
    if (!handles(event.configuration().runtime(), this.factoryName)) {
      return;
    }

    LOGGER.debug("pinning service with runtime {} to the local node", this.factoryName);
    event.nodeServer(this.localNode);
  }
}
