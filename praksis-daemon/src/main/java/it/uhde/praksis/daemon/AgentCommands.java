// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@code praksis agent ...}: what the maintenance agent has staged. Nothing here changes a layer:
 * {@code export} copies the staged files into a directory that the person names (their own checkout
 * of the layer), where they commit and push them as they would any change.
 */
final class AgentCommands {
  private AgentCommands() {}

  static int run(
      DaemonConfig config, Layers layers, List<String> args, PrintWriter out, PrintWriter err)
      throws DaemonException {
    AgentStore store = new AgentStore(config.dataDirectory().resolve(Builtins.MODULE));
    String command = args.isEmpty() ? "list" : args.get(0);
    try {
      switch (command) {
        case "list" -> {
          List<AgentStore.Staged> all = store.list();
          if (all.isEmpty()) {
            out.println("Nothing is staged.");
          }
          for (AgentStore.Staged s : all) {
            out.printf(
                "%s  %s  rule %s  %s  %s%n",
                s.id(), s.layer(), s.rule(), String.join(", ", s.files()), s.reason());
          }
          return 0;
        }
        case "show" -> {
          Optional<AgentStore.Staged> staged = one(store, args, err);
          return staged.isEmpty() ? 1 : show(config, layers, store, staged.get(), out, err);
        }
        case "export" -> {
          if (args.size() != 3) {
            err.println("usage: agent export <id> <directory>");
            return 2;
          }
          Optional<AgentStore.Staged> staged = one(store, args.subList(0, 2), err);
          return staged.isEmpty() ? 1 : export(store, staged.get(), Path.of(args.get(2)), out, err);
        }
        case "drop" -> {
          Optional<AgentStore.Staged> staged = one(store, args, err);
          if (staged.isEmpty()) {
            return 1;
          }
          store.drop(staged.get());
          out.println("Dropped " + staged.get().id() + ".");
          return 0;
        }
        default -> {
          err.println(
              "usage: agent list | agent show <id> | agent export <id> <directory> | agent drop <id>");
          return 2;
        }
      }
    } catch (IOException e) {
      err.println("praksis: " + e.getMessage());
      return 1;
    }
  }

  private static Optional<AgentStore.Staged> one(
      AgentStore store, List<String> args, PrintWriter err) throws IOException {
    if (args.size() < 2) {
      err.println("this needs the id of a staged change (praksis agent list)");
      return Optional.empty();
    }
    Optional<AgentStore.Staged> staged = store.find(args.get(1));
    if (staged.isEmpty()) {
      err.println("nothing is staged with the id '" + args.get(1) + "'");
    }
    return staged;
  }

  /** Prints the change and runs the checks on the layer with it, as they are now. */
  private static int show(
      DaemonConfig config,
      Layers layers,
      AgentStore store,
      AgentStore.Staged staged,
      PrintWriter out,
      PrintWriter err)
      throws IOException, DaemonException {
    out.printf("%s for rule %s of layer %s%n", staged.id(), staged.rule(), staged.layer());
    out.println("Why: " + (staged.reason().isEmpty() ? "(not said)" : staged.reason()));
    Map<String, String> files = store.files(staged);
    files.forEach(
        (path, content) -> {
          out.println();
          out.println("--- " + path);
          out.println(content.stripTrailing());
        });
    out.println();
    Path source = null;
    for (var s : layers.sources(layers.readLock())) {
      if (s.name().equals(staged.layer())) {
        source = s.directory();
      }
    }
    if (source == null) {
      err.println("the layer '" + staged.layer() + "' is not there any more");
      return 1;
    }
    Path candidate = Files.createTempDirectory("praksis-agent-");
    try (LoadedModules modules =
        Builtins.add(
            ModuleLoader.load(
                config.moduleRoots(),
                config.lockfile(),
                config.moduleSettings(),
                config.enabledModules()),
            new Agent())) {
      Agent.copy(source, candidate);
      for (var e : files.entrySet()) {
        Path target = candidate.resolve(e.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
      }
      StringWriter errors = new StringWriter();
      Admin.Checked checked =
          Admin.check(
              config,
              layers,
              new Admin.Check.Options(
                  false, false, 0, Map.of(staged.layer(), candidate.toString())),
              new PrintWriter(errors),
              modules);
      if (checked == null) {
        err.println(errors.toString().strip());
        return 1;
      }
      Admin.print(checked, out);
      out.println();
      out.println(
          "To take it: praksis agent export "
              + staged.id()
              + " <your checkout of "
              + staged.layer()
              + ">");
      return checked.report().ok() ? 0 : 1;
    } catch (ModuleLoadException e) {
      err.println("cannot check against the modules: " + e.getMessage());
      return 1;
    } finally {
      Agent.delete(candidate);
    }
  }

  private static int export(
      AgentStore store, AgentStore.Staged staged, Path directory, PrintWriter out, PrintWriter err)
      throws IOException {
    if (!Files.isDirectory(directory)) {
      err.println(directory + " is not a directory");
      return 1;
    }
    Map<String, String> files = store.files(staged);
    for (String path : files.keySet()) {
      if (Files.exists(directory.resolve(path))) {
        err.println(directory.resolve(path) + " is there already: nothing was written");
        return 1;
      }
    }
    for (var e : files.entrySet()) {
      Path target = directory.resolve(e.getKey()).normalize();
      if (!target.startsWith(directory.normalize())) {
        err.println("refused: " + e.getKey());
        return 1;
      }
      Files.createDirectories(target.getParent());
      Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
      out.println("wrote " + target);
    }
    out.println(
        "Look at the files, then commit and push them yourself; Praksis does neither. When they are in the layer, `praksis layers update` brings them in.");
    return 0;
  }
}
