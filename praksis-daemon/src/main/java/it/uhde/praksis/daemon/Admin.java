// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.engine.Replay;
import it.uhde.praksis.engine.RuleTestRunner;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Permissions;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetValidator;
import it.uhde.praksis.ruleset.Verifier;
import it.uhde.praksis.store.Store;
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
 * layers update [--dry-run] [--accept-new-access] [name...]
 *                                        move pins to the latest commit, if the result is valid
 *                                        and gives no rule access that no grant covers
 * effective                              the ruleset that would run, and where each rule comes from
 * verify [--replay] [-n N] [--update] [--layer name=dir|@ref]...
 *                                        the checks on that ruleset (or on a candidate: the
 *                                        pins moved with --update, layers replaced by a directory
 *                                        or a ref), on its tests and on policy.toml; --replay
 *                                        also runs stored events through both and shows the
 *                                        differences
 * test                                   run the tests (tests/*.toml) of every layer
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
    return run(config, git, validator, Layers.AccessCheck.NONE, args, out, err);
  }

  /** As above, with the check of the access that rulesets need against the grants. */
  static int run(
      DaemonConfig config,
      GitSource git,
      Function<Ruleset, List<Problem>> validator,
      Layers.AccessCheck access,
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
        boolean acceptNewAccess = false;
        Set<String> names = new LinkedHashSet<>();
        for (String a : args.subList(2, args.size())) {
          if (a.equals("--dry-run")) {
            dryRun = true;
          } else if (a.equals("--accept-new-access")) {
            acceptNewAccess = true;
          } else {
            names.add(a);
          }
        }
        return update(layers, names, dryRun, acceptNewAccess, validator, access, out, err);
      }
      if (args.equals(List.of("effective"))) {
        return effective(config, layers, out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("verify")) {
        return verify(config, layers, args.subList(1, args.size()), out, err);
      }
      if (args.equals(List.of("test"))) {
        return test(config, layers, out, err);
      }
    } catch (DaemonException e) {
      err.println(e.getMessage());
      return 1;
    }
    err.println(
        "usage: layers status | layers update [--dry-run] [--accept-new-access] [name...] | effective | verify [--replay] [-n N] [--update] [--layer name=dir|@ref] | test");
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
      boolean acceptNewAccess,
      Function<Ruleset, List<Problem>> validator,
      Layers.AccessCheck access,
      PrintWriter out,
      PrintWriter err)
      throws DaemonException {
    Layers.Update result = layers.update(names, dryRun, acceptNewAccess, validator, access);
    if (result.refused()) {
      result.report().forEach(err::println);
      return 1;
    }
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
      Provenance provenance = layers.provenance(o);
      out.println("      " + provenance.describe());
      provenance.reasoning().lines().limit(6).forEach(l -> out.println("        | " + l));
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

  /**
   * Runs the checks on the ruleset that would run, or on a candidate, with its tests and
   * policy.toml. For a candidate, access that is new compared with the ruleset in use and that no
   * grant covers is an error. With {@code --replay}, stored events go through both rulesets and the
   * events that they treat differently are listed.
   */
  private static int verify(
      DaemonConfig config, Layers layers, List<String> options, PrintWriter out, PrintWriter err)
      throws DaemonException {
    boolean replay = false;
    boolean update = false;
    int count = 200;
    java.util.Map<String, String> replacements = new java.util.LinkedHashMap<>();
    for (int i = 0; i < options.size(); i++) {
      String o = options.get(i);
      switch (o) {
        case "--replay" -> replay = true;
        case "--update" -> update = true;
        case "-n", "--layer" -> {
          if (i + 1 >= options.size()) {
            err.println("usage: verify [--replay] [-n N] [--update] [--layer name=dir|@ref]");
            return 2;
          }
          String value = options.get(++i);
          if (o.equals("-n")) {
            try {
              count = Integer.parseInt(value);
            } catch (NumberFormatException ex) {
              err.println("-n needs a number: " + value);
              return 2;
            }
          } else {
            int eq = value.indexOf('=');
            if (eq < 1) {
              err.println("--layer needs name=directory or name=@ref: " + value);
              return 2;
            }
            replacements.put(value.substring(0, eq), value.substring(eq + 1));
          }
        }
        default -> {
          err.println("usage: verify [--replay] [-n N] [--update] [--layer name=dir|@ref]");
          return 2;
        }
      }
    }
    boolean isCandidate = update || !replacements.isEmpty();
    LayerStack.Effective current;
    Layers.Candidate candidate;
    try {
      current = layers.effective();
      candidate =
          isCandidate
              ? layers.candidate(replacements, update)
              : new Layers.Candidate(layers.sources(layers.readLock()), current);
    } catch (RulesetException ex) {
      err.println("The layers cannot be put together:");
      ex.problems().forEach(p -> err.println("  " + p));
      return 1;
    }
    LayerStack.Effective e = candidate.effective();
    Verifier.Report report;
    Replay.Report replayed = null;
    try (LoadedModules modules =
        ModuleLoader.load(
            config.modules(),
            config.lockfile(),
            config.moduleSettings(),
            config.enabledModules())) {
      List<Verifier.Finding> findings =
          new ArrayList<>(
              new Verifier(Daemon.compiler(config), modules.catalog())
                  .verify(e.ruleset())
                  .findings());
      try {
        Policy policy = Policy.load(config.policy());
        policy
            .check(modules.catalog(), config.layerNames())
            .forEach(p -> findings.add(policyFinding(p)));
        Permissions.Needs needs = Permissions.of(e, modules.catalog());
        List<Permissions.Requirement> isNew =
            isCandidate
                ? Permissions.diff(Permissions.of(current, modules.catalog()), needs).added()
                : List.of();
        for (Permissions.Requirement r : Permissions.uncovered(needs.requirements(), policy)) {
          findings.add(
              new Verifier.Finding(
                  isNew.contains(r) ? Verifier.Severity.ERROR : Verifier.Severity.WARNING,
                  "access",
                  String.join(", ", needs.rules().get(r)),
                  (isNew.contains(r) ? "new access that no grant covers: " : "no grant covers ")
                      + r.describe()));
        }
      } catch (RulesetException ex) {
        ex.problems().forEach(p -> findings.add(policyFinding(p)));
      }
      findings.addAll(testFindings(config, candidate.sources(), e, modules));
      report = new Verifier.Report(findings);
      if (replay) {
        replayed = replay(config, current, e, count, modules, err);
        if (replayed == null) {
          return 1;
        }
      }
    } catch (ModuleLoadException ex) {
      err.println("cannot check the ruleset against the modules: " + ex.getMessage());
      return 1;
    }
    out.printf(
        "%s %s (%d rules): %d error(s), %d warning(s)%n",
        isCandidate ? "Candidate" : "Ruleset",
        e.ruleset().name(),
        e.ruleset().rules().size(),
        report.errors(),
        report.warnings());
    for (Verifier.Finding f : report.findings()) {
      out.printf("  %-7s %-16s %s: %s%n", f.severity(), f.check(), f.where(), f.message());
    }
    if (replayed != null) {
      out.printf(
          "Replay of %d stored event(s): %d treated differently%n",
          replayed.events(), replayed.differences().size());
      int shown = 0;
      for (Replay.Difference d : replayed.differences()) {
        if (shown++ == 20) {
          out.printf("  ... and %d more%n", replayed.differences().size() - 20);
          break;
        }
        out.printf("  event %s (%s)%n", d.event().id(), d.event().type());
        d.current().forEach(l -> out.println("    now:       " + l));
        if (d.current().isEmpty()) {
          out.println("    now:       (nothing)");
        }
        d.candidate().forEach(l -> out.println("    candidate: " + l));
        if (d.candidate().isEmpty()) {
          out.println("    candidate: (nothing)");
        }
      }
    }
    return report.ok() ? 0 : 1;
  }

  /** Stored events through the ruleset in use and the candidate; null (reported) if impossible. */
  private static Replay.Report replay(
      DaemonConfig config,
      LayerStack.Effective current,
      LayerStack.Effective candidate,
      int count,
      LoadedModules modules,
      PrintWriter err)
      throws DaemonException {
    if (!java.nio.file.Files.exists(config.database())) {
      return new Replay.Report(0, List.of());
    }
    try (Store store = Store.open(config.database())) {
      return Replay.compare(
          current.ruleset(),
          candidate.ruleset(),
          store.recentEvents(count),
          modules.catalog(),
          Daemon.compiler(config));
    } catch (RulesetException ex) {
      err.println("The engine cannot take the ruleset:");
      ex.problems().forEach(p -> err.println("  " + p));
      return null;
    } catch (Exception ex) {
      err.println("cannot replay the stored events: " + ex.getMessage());
      return null;
    }
  }

  /**
   * Runs the tests of the layers and reports the ones that fail or cannot be read ({@code test}),
   * and every rule that no test of its owner layer makes fire ({@code coverage}): a rule that
   * nothing tests is an error, which also means that a changed rule needs a changed or new test.
   */
  private static List<Verifier.Finding> testFindings(
      DaemonConfig config,
      List<LayerStack.Source> sources,
      LayerStack.Effective e,
      LoadedModules modules)
      throws DaemonException {
    RuleTestRunner.Report tests =
        new RuleTestRunner(modules.catalog(), Daemon.compiler(config))
            .run(sources, java.util.Map.of());
    List<Verifier.Finding> findings = new ArrayList<>();
    for (Problem p : tests.problems()) {
      findings.add(
          new Verifier.Finding(Verifier.Severity.ERROR, "test", p.location(), p.message()));
    }
    for (RuleTestRunner.Result r : tests.results()) {
      if (!r.passed()) {
        findings.add(
            new Verifier.Finding(
                Verifier.Severity.ERROR,
                "test",
                r.layer() + ": tests/" + r.test().rule() + ".toml, '" + r.test().name() + "'",
                r.message()));
      }
    }
    java.util.Map<String, String> owners = new java.util.HashMap<>();
    e.origins().forEach(o -> owners.put(o.ruleId(), o.owner()));
    Set<String> covered = tests.covered(owners);
    for (LayerStack.Origin o : e.origins()) {
      if (!covered.contains(o.ruleId())) {
        findings.add(
            new Verifier.Finding(
                Verifier.Severity.ERROR,
                "coverage",
                "rules/" + o.ruleId() + ".toml",
                "no test of layer '"
                    + o.owner()
                    + "' makes this rule fire; add a test with a matching event to tests/"
                    + o.ruleId()
                    + ".toml in that layer"));
      }
    }
    return findings;
  }

  /** Runs the tests of the layers with recording capabilities. */
  private static int test(DaemonConfig config, Layers layers, PrintWriter out, PrintWriter err)
      throws DaemonException {
    List<LayerStack.Source> sources = layers.sources(layers.readLock());
    RuleTestRunner.Report report;
    try (LoadedModules modules =
        ModuleLoader.load(
            config.modules(),
            config.lockfile(),
            config.moduleSettings(),
            config.enabledModules())) {
      report =
          new RuleTestRunner(modules.catalog(), Daemon.compiler(config))
              .run(sources, java.util.Map.of());
    } catch (ModuleLoadException ex) {
      err.println("cannot run the tests against the modules: " + ex.getMessage());
      return 1;
    }
    long passed = report.results().stream().filter(RuleTestRunner.Result::passed).count();
    out.printf(
        "Tests: %d run, %d passed, %d failed%s%n",
        report.results().size(),
        passed,
        report.results().size() - passed,
        report.problems().isEmpty() ? "" : ", " + report.problems().size() + " unreadable");
    for (RuleTestRunner.Result r : report.results()) {
      out.printf(
          "  %-4s %s  %s: %s%n",
          r.passed() ? "ok" : "FAIL", r.layer(), r.test().rule(), r.test().name());
      if (!r.passed()) {
        out.println("         " + r.message().replace("\n", "\n         "));
      }
    }
    report.problems().forEach(p -> out.println("  ERROR " + p));
    return report.ok() ? 0 : 1;
  }

  private static Verifier.Finding policyFinding(Problem p) {
    return new Verifier.Finding(Verifier.Severity.ERROR, "policy", p.location(), p.message());
  }

  /**
   * Works out the access a ruleset needs and which of it the grants in policy.toml do not cover.
   */
  static Layers.AccessCheck accessFor(DaemonConfig config) {
    return effective -> {
      try (LoadedModules modules =
          ModuleLoader.load(
              config.modules(),
              config.lockfile(),
              config.moduleSettings(),
              config.enabledModules())) {
        Permissions.Needs needs = Permissions.of(effective, modules.catalog());
        List<Permissions.Requirement> uncovered =
            Permissions.uncovered(needs.requirements(), Policy.load(config.policy()));
        return new Layers.Access(needs, uncovered);
      } catch (ModuleLoadException | RulesetException ex) {
        throw new DaemonException("cannot work out what the rules need: " + ex.getMessage(), ex);
      }
    };
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
