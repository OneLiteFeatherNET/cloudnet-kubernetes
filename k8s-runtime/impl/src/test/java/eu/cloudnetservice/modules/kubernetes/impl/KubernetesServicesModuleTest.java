package eu.cloudnetservice.modules.kubernetes.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

import eu.cloudnetservice.driver.event.EventManager;
import eu.cloudnetservice.modules.kubernetes.impl.listener.NodeSelectListener;
import eu.cloudnetservice.node.cluster.NodeServer;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class KubernetesServicesModuleTest {

  private static final String FACTORY = "k8s-jvm";

  /** Identity is all the assertions need; any call on the stub is a test failure. */
  private static NodeServer localNodeStub() {
    return (NodeServer) Proxy.newProxyInstance(
      NodeServer.class.getClassLoader(),
      new Class<?>[]{NodeServer.class},
      (proxy, method, args) -> {
        throw new UnsupportedOperationException("unexpected call to " + method.getName());
      });
  }

  /** Records the listener instances handed to registerListener(Object). */
  private static final class RecordingEventManager {

    final List<Object> registered = new ArrayList<>();
    final EventManager proxy = (EventManager) Proxy.newProxyInstance(
      EventManager.class.getClassLoader(),
      new Class<?>[]{EventManager.class},
      (proxy, method, args) -> {
        if (method.getName().equals("registerListener") && !(args[0] instanceof Class<?>)) {
          this.registered.add(args[0]);
          return proxy;
        }
        throw new UnsupportedOperationException("unexpected call to " + method.getName());
      });
  }

  @Test
  void registrationHandsExactlyOneNodeSelectListenerToTheEventManager() {
    var events = new RecordingEventManager();
    KubernetesServicesModule.registerNodeSelectListener(events.proxy, FACTORY, localNodeStub());

    assertEquals(1, events.registered.size(),
      "the node select listener must be registered once with the event manager");
    assertInstanceOf(NodeSelectListener.class, events.registered.get(0),
      "the registered listener must be the node select listener");
  }

  @Test
  void registrationReturnsTheListenerItRegistered() {
    var events = new RecordingEventManager();
    var listener = KubernetesServicesModule.registerNodeSelectListener(events.proxy, FACTORY, localNodeStub());

    assertSame(events.registered.get(0), listener,
      "the module keeps the returned reference so it can unregister the same instance on stop");
  }
}
