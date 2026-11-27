package eu.cloudnetservice.modules.kubernetes.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class TaskKubernetesConfigTest {

  @Test
  void serviceValueWinsOverTaskValuePerField() {
    var service = new TaskKubernetesConfig("ghcr.io/example/service:1", null, null, null, null, null);
    var task = new TaskKubernetesConfig("ghcr.io/example/task:1", null, "gpu", 4, 8192, "high");

    var resolved = service.overlay(task);

    assertEquals("ghcr.io/example/service:1", resolved.image(), "the service image must win");
    assertEquals("gpu", resolved.tolerationKey(), "unset service fields must fall back to the task");
    assertEquals(4, resolved.cpuRequest());
    assertEquals(8192, resolved.memoryMiB());
    assertEquals("high", resolved.priorityClassName());
  }

  @Test
  void taskNodeSelectorIsUsedWhenServiceHasNone() {
    var task = new TaskKubernetesConfig(null, Map.of("pool", "gameservers"), null, null, null, null);

    var resolved = new TaskKubernetesConfig(null, null, null, null, null, null).overlay(task);

    assertEquals(Map.of("pool", "gameservers"), resolved.nodeSelector());
  }

  @Test
  void missingFallbackLeavesTheValueUntouched() {
    var service = new TaskKubernetesConfig("img", null, null, 2, 1024, "p");

    assertEquals(service, service.overlay(null));
  }

  @Test
  void nothingSetOnEitherSideStaysNull() {
    var empty = new TaskKubernetesConfig(null, null, null, null, null, null);

    var resolved = empty.overlay(empty);

    assertNull(resolved.image());
    assertNull(resolved.priorityClassName());
    assertNull(resolved.memoryMiB());
  }
}
