package eu.cloudnetservice.modules.kubernetes.impl.config;

import eu.cloudnetservice.node.service.CloudService;
import eu.cloudnetservice.node.service.ServiceConfigurationPreparer;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Wraps the environment's own preparer and rewrites the bind address afterwards.
 *
 * <p>Decorating rather than replacing keeps every environment specific step CloudNet
 * performs - eula.txt, forwarding secrets, version specific fixes - and only corrects
 * the one value that is wrong inside a pod.
 */
public final class KubernetesBindPreparer implements ServiceConfigurationPreparer {

  private static final Logger LOGGER = LoggerFactory.getLogger(KubernetesBindPreparer.class);

  private final ServiceConfigurationPreparer delegate;

  public KubernetesBindPreparer(ServiceConfigurationPreparer delegate) {
    this.delegate = delegate;
  }

  @Override
  public void configure(CloudService cloudService) {
    this.delegate.configure(cloudService);

    var directory = cloudService.directory();
    var port = cloudService.serviceConfiguration().port();
    try {
      BindAddressRewriter.rewriteServerProperties(directory.resolve("server.properties"));
      BindAddressRewriter.rewriteVelocityToml(directory.resolve("velocity.toml"), port);
    } catch (IOException exception) {
      LOGGER.error("unable to rewrite bind address for {}", cloudService.serviceId().name(), exception);
      throw new UncheckedIOException(exception);
    }
  }
}
