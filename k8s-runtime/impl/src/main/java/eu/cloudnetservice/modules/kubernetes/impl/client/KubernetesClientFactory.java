package eu.cloudnetservice.modules.kubernetes.impl.client;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.jdkhttp.JdkHttpClientFactory;

public final class KubernetesClientFactory {

  private KubernetesClientFactory() {
  }

  /**
   * Builds a client that works both in-cluster and from the outside.
   *
   * <p>{@code ConfigBuilder} finds the service account under
   * /var/run/secrets/kubernetes.io/serviceaccount by itself and falls back to
   * KUBECONFIG or ~/.kube/config, so no branching is needed here.
   *
   * <p>The HTTP client factory is set explicitly on purpose. fabric8 would otherwise
   * resolve it through a ServiceLoader against the thread context classloader, which
   * inside a CloudNet module thread is not the ModuleURLClassLoader that holds our
   * shaded classes.
   */
  public static KubernetesClient create(String namespace) {
    var config = new ConfigBuilder().withNamespace(namespace).build();
    return new KubernetesClientBuilder()
      .withHttpClientFactory(new JdkHttpClientFactory())
      .withConfig(config)
      .build();
  }
}
