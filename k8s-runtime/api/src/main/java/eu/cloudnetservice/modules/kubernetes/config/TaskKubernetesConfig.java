package eu.cloudnetservice.modules.kubernetes.config;

import java.util.Map;

/**
 * Per-task overrides, stored as the {@code k8sConfig} property on a ServiceTask.
 *
 * <p>Mirrors how the docker module keeps {@code dockerConfig} on a task, including
 * the resolution order: the service configuration wins over the task.
 *
 * <p>Every component is optional. Null always means "use the module default", and a
 * configuration stored before a component existed deserialises it as null.
 *
 * @param image             the service image, or null for the module default
 * @param nodeSelector      labels a node must carry to host the pod, or null
 * @param tolerationKey     taint key tolerated with operator Exists and effect NoSchedule, or null
 * @param cpuRequest        the cpu request in whole cores, or null for the module default
 * @param memoryMiB         the memory request and limit in MiB, or null to derive it from the heap
 * @param priorityClassName the pod priority class, or null for none
 */
public record TaskKubernetesConfig(
  String image,
  Map<String, String> nodeSelector,
  String tolerationKey,
  Integer cpuRequest,
  Integer memoryMiB,
  String priorityClassName
) {

  public static final String PROPERTY_KEY = "k8sConfig";

  /**
   * Resolves this configuration against a fallback, field by field: a value set here
   * wins, an unset one is taken from the fallback. A null fallback changes nothing.
   *
   * @param fallback the configuration to take unset values from, possibly null
   * @return the resolved configuration
   */
  public TaskKubernetesConfig overlay(TaskKubernetesConfig fallback) {
    if (fallback == null) {
      return this;
    }
    return new TaskKubernetesConfig(
      this.image != null ? this.image : fallback.image,
      this.nodeSelector != null ? this.nodeSelector : fallback.nodeSelector,
      this.tolerationKey != null ? this.tolerationKey : fallback.tolerationKey,
      this.cpuRequest != null ? this.cpuRequest : fallback.cpuRequest,
      this.memoryMiB != null ? this.memoryMiB : fallback.memoryMiB,
      this.priorityClassName != null ? this.priorityClassName : fallback.priorityClassName);
  }
}
