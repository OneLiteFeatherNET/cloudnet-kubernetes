package eu.cloudnetservice.modules.kubernetes.impl.pod;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Rewrites the JVM command line from node paths to container paths.
 *
 * <p>{@code JVMService} builds {@code -cp} and {@code -javaagent} from absolute paths
 * on the node: the launcher library directory, the extracted wrapper jar and the
 * service directory. The docker module only survives this because it bind-mounts each
 * host path onto an identical container path, which is impossible once the pod runs
 * on another machine.
 *
 * <p>The image carries the launcher libraries and the wrapper at fixed locations, so
 * the three prefixes are substituted instead.
 */
public final class ArgumentRewriter {

  public static final String CONTAINER_LIB_PATH = "/cloudnet/libs";
  public static final String CONTAINER_WRAPPER_JAR = "/cloudnet/wrapper.jar";
  public static final String CONTAINER_SERVICE_DIR = "/cloudnet/service";

  private ArgumentRewriter() {
  }

  /**
   * @param nodeLibPath     the node's launcher/libs directory, absolute
   * @param nodeWrapperJar  the node's extracted wrapper jar, absolute
   * @param serviceDirectory the node's directory for this service, absolute
   */
  public static List<String> rewrite(
    List<String> arguments,
    Path nodeLibPath,
    Path nodeWrapperJar,
    Path serviceDirectory
  ) {
    var libPrefix = nodeLibPath.toAbsolutePath().toString();
    var wrapperPath = nodeWrapperJar.toAbsolutePath().toString();
    var servicePrefix = serviceDirectory.toAbsolutePath().toString();

    var rewritten = new ArrayList<String>(arguments.size());
    for (var argument : arguments) {
      // Order matters: the wrapper jar is a single file and must be replaced before
      // any directory prefix it might sit under.
      var value = argument
        .replace(wrapperPath, CONTAINER_WRAPPER_JAR)
        .replace(libPrefix, CONTAINER_LIB_PATH)
        .replace(servicePrefix, CONTAINER_SERVICE_DIR);
      rewritten.add(value);
    }

    // The launcher binary is resolved from PATH inside the container.
    if (!rewritten.isEmpty()) {
      rewritten.set(0, "java");
    }
    return rewritten;
  }
}
