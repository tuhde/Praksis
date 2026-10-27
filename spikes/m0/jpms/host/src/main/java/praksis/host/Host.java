package praksis.host;

import java.lang.module.Configuration;
import java.lang.module.ModuleFinder;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import praksis.api.Capability;

/**
 * Loads each "dir:rootModule" argument into its own ModuleLayer and invokes its capabilities.
 * Extra "key=value" arguments after "--" are passed as capability arguments.
 */
public class Host {
  public static void main(String[] args) throws Exception {
    Map<String, Object> capArgs = new java.util.HashMap<>();
    List<String> layers = new java.util.ArrayList<>();
    boolean rest = false;
    for (String a : args) {
      if (a.equals("--")) rest = true;
      else if (rest) {
        String[] kv = a.split("=", 2);
        capArgs.put(kv[0], kv[1].equals("true") ? Boolean.TRUE : kv[1]);
      } else layers.add(a);
    }

    for (String spec : layers) {
      String[] parts = spec.split(":", 2);
      ModuleFinder finder = ModuleFinder.of(Path.of(parts[0]));
      Configuration cf =
          ModuleLayer.boot().configuration().resolveAndBind(finder, ModuleFinder.of(), Set.of(parts[1]));
      ModuleLayer layer =
          ModuleLayer.defineModulesWithOneLoader(
                  cf, List.of(ModuleLayer.boot()), ClassLoader.getSystemClassLoader())
              .layer();
      System.out.println("layer " + parts[1] + ": " + layer.modules().size() + " modules");
      for (Capability c : ServiceLoader.load(layer, Capability.class)) {
        try {
          System.out.println("  " + c.name() + " -> " + c.invoke(capArgs));
        } catch (Throwable t) {
          Throwable root = t;
          while (root.getCause() != null) root = root.getCause();
          System.out.println("  " + c.name() + " FAILED: " + t + "  (root: " + root + ")");
        }
      }
    }
    System.out.println("memory: " + memory());
  }

  static String memory() throws java.io.IOException {
    StringBuilder sb = new StringBuilder();
    for (String line : java.nio.file.Files.readAllLines(Path.of("/proc/self/status"))) {
      if (line.startsWith("VmRSS") || line.startsWith("VmHWM")) sb.append(line.replaceAll("\\s+", " ")).append("  ");
    }
    return sb.toString();
  }
}
