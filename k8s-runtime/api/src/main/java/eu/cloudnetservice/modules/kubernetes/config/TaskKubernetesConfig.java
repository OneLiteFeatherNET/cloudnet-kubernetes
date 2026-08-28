package eu.cloudnetservice.modules.kubernetes.config;

import java.util.Map;

/**
 * Per-task overrides, stored as the {@code k8sConfig} property on a ServiceTask.
 *
 * <p>Mirrors how the docker module keeps {@code dockerConfig} on a task, including
 * the resolution order: the service configuration wins over the task.
 */
public record TaskKubernetesConfig(
  String image,
  Map<String, String> nodeSelector,
  String tolerationKey,
  Integer cpuRequest
) {

  public static final String PROPERTY_KEY = "k8sConfig";
}
