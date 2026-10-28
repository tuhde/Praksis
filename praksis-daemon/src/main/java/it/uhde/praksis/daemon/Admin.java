// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetValidator;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/**
 * Commands that work on the home directory without a running daemon:
 *
 * <pre>
 * layers status                          which commit each layer is pinned to
 * layers update [--dry-run] [name...]    move pins to the latest commit, if the result is valid
 * effective                              the ruleset that would run, and where each rule comes from
 * </pre>
 */
final class Admin {
  private Admin() {}

  /** Runs a command and returns the exit code: 0 done, 1 failed, 2 wrong usage. */
  static int run(
      DaemonConfig config,
      GitSource git,
      Function<Ruleset, List<Problem>> validator,
      List<String> args,
      PrintWriter out,
      PrintWriter err) {
    Layers layers = new Layers(config, git);
    try {
      if (args.equals(List.of("layers", "status"))) {
        return status(config, layers, out);
      }
      if (args.size() >= 2 && args.get(0).equals("layers") && args.get(1).equals("update")) {
        boolean dryRun = false;
        Set<String> names = new LinkedHashSet<>();
        for (String a : args.subList(2, args.size())) {
          if (a.equals("--dry-run")) {
            dryRun = true;
          } else {
            names.add(a);
          }
        }
        return update(layers, names, dryRun, validator, out, err);
      }
      if (args.equals(List.of("effective"))) {
        return effective(config, layers, out, err);
      }
    } catch (DaemonException e) {
      err.println(e.getMessage());
      return 1;
    }
    err.println("usage: layers status | layers update [--dry-run] [name...] | effective");
    return 2;
  }

  private static int status(DaemonConfig config, Layers layers, PrintWriter out)
      throws DaemonException {
    var pins = layers.readLock();
    if (config.layers().isEmpty()) {
      out.println("no layers configured: the directory " + config.ruleset() + " is the ruleset");
      return 0;
    }
    for (LayerConfig layer : config.layers()) {
      if (!layer.isGit()) {
        out.println(layer.name() + ": directory " + layer.path().get() + " (not pinned)");
        continue;
      }
      var pin = pins.get(layer.name());
      String where = layer.git().get() + " @ " + layer.ref().get();
      if (pin == null) {
        out.println(layer.name() + ": " + where + " - not pinned yet, run: praksis layers update");
      } else {
        boolean stale =
            !pin.git().equals(layer.git().get()) || !pin.ref().equals(layer.ref().get());
        boolean present =
            java.nio.file.Files.isDirectory(
                config.snapshots().resolve(layer.name()).resolve(pin.commit()));
        out.println(
            layer.name()
                + ": "
                + where
                + " - pinned to "
                + Layers.abbreviate(pin.commit())
                + (stale ? " (the configuration changed since, run: praksis layers update)" : "")
                + (present ? "" : " (files not on disk, fetched when needed)"));
      }
    }
    return 0;
  }

  private static int update(
      Layers layers,
      Set<String> names,
      boolean dryRun,
      Function<Ruleset, List<Problem>> validator,
      PrintWriter out,
      PrintWriter err)
      throws DaemonException {
    Layers.Update result = layers.update(names, dryRun, validator);
    if (!result.problems().isEmpty()) {
      err.println("Not updated: the ruleset that would result has problems.");
      result.problems().forEach(p -> err.println("  " + p));
      err.println("The pins are unchanged.");
      return 1;
    }
    result.report().forEach(out::println);
    return 0;
  }

  private static int effective(DaemonConfig config, Layers layers, PrintWriter out, PrintWriter err)
      throws DaemonException {
    LayerStack.Effective e;
    try {
      e = layers.effective();
    } catch (RulesetException ex) {
      err.println("The layers cannot be put together:");
      ex.problems().forEach(p -> err.println("  " + p));
      return 1;
    }
    out.println("ruleset: " + e.ruleset().name() + " (" + e.ruleset().rules().size() + " rules)");
    out.println("rules, in the order they fire:");
    for (LayerStack.Origin o : e.origins()) {
      out.println("  " + o.ruleId());
      out.println("      " + o.hash());
      out.println("      " + String.join("; ", o.history()));
    }
    if (!e.disabled().isEmpty()) {
      out.println("disabled:");
      for (LayerStack.Disabled d : e.disabled()) {
        out.println(
            "  "
                + d.ruleId()
                + " by "
                + d.layer()
                + d.reason().map(r -> " (" + r + ")").orElse(""));
      }
    }
    if (!e.ruleset().params().isEmpty()) {
      out.println("parameters:");
      e.ruleset()
          .params()
          .forEach(
              p ->
                  out.println(
                      "  "
                          + p.name()
                          + " ("
                          + p.type().id()
                          + ") = "
                          + p.defaultValue().map(String::valueOf).orElse("(no value)")));
    }
    return 0;
  }

  /** Checks a ruleset against the loaded modules, as the daemon does when it starts. */
  static Function<Ruleset, List<Problem>> validatorFor(DaemonConfig config) {
    return ruleset -> {
      try (LoadedModules modules =
          ModuleLoader.load(
              config.modules(),
              config.lockfile(),
              config.moduleSettings(),
              config.enabledModules())) {
        ExpressionCompiler compiler = Daemon.compiler(config);
        return new ArrayList<>(new RulesetValidator(compiler, modules.catalog()).validate(ruleset));
      } catch (ModuleLoadException | DaemonException ex) {
        return List.of(
            new Problem("modules", "cannot check the ruleset against them: " + ex.getMessage()));
      }
    };
  }
}
