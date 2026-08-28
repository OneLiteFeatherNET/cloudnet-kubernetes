package eu.cloudnetservice.modules.kubernetes.impl;

import eu.cloudnetservice.driver.service.ServiceId;
import eu.cloudnetservice.node.config.Configuration;
import eu.cloudnetservice.node.impl.service.defaults.log.AbstractServiceLogCache;

/**
 * Feeds lines pulled from the Kubernetes log API into CloudNet's console fan-out.
 *
 * <p>The log API does not separate stdout from stderr, so the error stream flag is
 * always false. Minecraft servers log through Log4j to stdout, so little is lost, but
 * it is a deliberate regression against a locally spawned process.
 */
public final class KubernetesServiceLogCache extends AbstractServiceLogCache {

  public KubernetesServiceLogCache(Configuration configuration, ServiceId associatedServiceId) {
    super(configuration, associatedServiceId);
  }

  public void acceptLine(String line) {
    super.handleItem(line, false);
  }
}
