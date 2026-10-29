// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.RuleTest;
import it.uhde.praksis.ruleset.RuleTests;
import it.uhde.praksis.ruleset.RulesetException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runs the tests of the layers ({@code tests/<rule>.toml}, see {@link RuleTest}) on the real
 * engine. The capabilities are replaced by recorders that return the mocked results, so nothing
 * outside the process is touched. The tests of a layer run against the ruleset as it is up to and
 * including that layer, so a layer cannot be tested against rules that are only changed above it.
 */
public final class RuleTestRunner {
  /**
   * The outcome of one test.
   *
   * @param layer the layer whose test it is
   * @param test the test
   * @param passed whether the rule behaved as expected
   * @param message what was different, empty if the test passed
   * @param fired the rules that matched the test's event and ran, the one under test among them
   */
  public record Result(
      String layer, RuleTest test, boolean passed, String message, Set<String> fired) {}

  /**
   * Everything that came out of a run.
   *
   * @param results one per test, in layer order
   * @param problems tests or layers that could not be read
   */
  public record Report(List<Result> results, List<Problem> problems) {
    public Report {
      results = List.copyOf(results);
      problems = List.copyOf(problems);
    }

    /** Whether every test passed and nothing was unreadable. */
    public boolean ok() {
      return problems.isEmpty() && results.stream().allMatch(Result::passed);
    }

    /**
     * The rules that a test of their owner layer made fire.
     *
     * @param owners the owner layer of each rule id
     */
    public Set<String> covered(Map<String, String> owners) {
      Set<String> out = new LinkedHashSet<>();
      for (Result r : results) {
        for (String rule : r.fired()) {
          if (r.layer().equals(owners.get(rule)) && r.test().rule().equals(rule)) {
            out.add(rule);
          }
        }
      }
      return out;
    }
  }

  private final ModuleCatalog catalog;
  private final ExpressionCompiler compiler;

  public RuleTestRunner(ModuleCatalog catalog, ExpressionCompiler compiler) {
    this.catalog = catalog;
    this.compiler = compiler;
  }

  /**
   * Runs the tests of every layer.
   *
   * @param layers bottom to top
   * @param params parameter values that every test starts from (a test can set its own)
   */
  public Report run(List<LayerStack.Source> layers, Map<String, Object> params) {
    List<Result> results = new ArrayList<>();
    List<Problem> problems = new ArrayList<>();
    for (int i = 0; i < layers.size(); i++) {
      LayerStack.Source layer = layers.get(i);
      List<RuleTest> tests;
      try {
        tests = RuleTests.load(layer.directory());
      } catch (RulesetException e) {
        e.problems()
            .forEach(
                p ->
                    problems.add(
                        new Problem("layer '" + layer.name() + "': " + p.location(), p.message())));
        continue;
      }
      if (tests.isEmpty()) {
        continue;
      }
      LayerStack.Effective effective;
      try {
        effective = LayerStack.resolve(layers.subList(0, i + 1), params, catalog.moduleNames());
      } catch (RulesetException e) {
        problems.addAll(e.problems());
        continue;
      }
      for (RuleTest test : tests) {
        LayerStack.Effective used = effective;
        if (!test.params().isEmpty()) {
          Map<String, Object> values = new java.util.HashMap<>(params);
          values.putAll(test.params());
          try {
            used = LayerStack.resolve(layers.subList(0, i + 1), values, catalog.moduleNames());
          } catch (RulesetException e) {
            results.add(
                new Result(
                    layer.name(),
                    test,
                    false,
                    "cannot set the parameters: " + e.problems().get(0).message(),
                    Set.of()));
            continue;
          }
        }
        results.add(run(layer.name(), used, test));
      }
    }
    return new Report(results, problems);
  }

  private Result run(String layer, LayerStack.Effective effective, RuleTest test) {
    if (!test.appliesTo(catalog.moduleNames())) {
      return new Result(
          layer, test, true, "skipped: the test is for another set of modules", Set.of());
    }
    if (effective.skipped().stream()
        .anyMatch(k -> k.kind().equals("rule") && k.id().equals(test.rule()))) {
      return new Result(
          layer, test, true, "skipped: the rule needs a module that is not there", Set.of());
    }
    if (effective.ruleset().rules().stream().noneMatch(r -> r.id().equals(test.rule()))) {
      return new Result(
          layer,
          test,
          false,
          "there is no rule '" + test.rule() + "' in the ruleset up to layer '" + layer + "'",
          Set.of());
    }
    try {
      return execute(layer, effective, test);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Result(layer, test, false, "cannot run the test: interrupted", Set.of());
    } catch (Exception e) {
      return new Result(layer, test, false, "cannot run the test: " + e.getMessage(), Set.of());
    }
  }

  /**
   * The data of the test's event. A command is started with the defaults of its parameters for what
   * is not given, so a test of a command gets them too.
   */
  private static Map<String, Object> withDefaults(
      it.uhde.praksis.ruleset.Ruleset ruleset, RuleTest test) {
    Map<String, Object> data = new java.util.HashMap<>(test.eventData());
    for (var rule : ruleset.rules()) {
      if (rule.eventType().equals(test.eventType())
          && rule.trigger() instanceof it.uhde.praksis.ruleset.Trigger.OnCommand command) {
        for (var p : command.params()) {
          if (!data.containsKey(p.name()) && p.defaultValue().isPresent()) {
            data.put(p.name(), p.defaultValue().get());
          }
        }
      }
    }
    return data;
  }

  /** The argument that was given is the one that was expected; bytes are expected as Base64. */
  private static boolean matches(Object expected, Object actual) {
    if (actual instanceof byte[] bytes) {
      return expected instanceof String text
          ? java.util.Base64.getEncoder().encodeToString(bytes).equals(text)
          : expected instanceof byte[] other && java.util.Arrays.equals(bytes, other);
    }
    return new ArgMatcher.Equals(expected).matches(actual);
  }

  private Result execute(String layer, LayerStack.Effective effective, RuleTest test)
      throws Exception {
    Event event =
        new Event(
            "test",
            test.eventType(),
            "test",
            Instant.EPOCH,
            "test",
            Optional.empty(),
            Optional.empty(),
            0,
            withDefaults(effective.ruleset(), test));
    Simulator.Run run;
    try (Simulator simulator =
        new Simulator(effective.ruleset(), catalog, compiler, test.mocks())) {
      run = simulator.run(event);
    }
    List<String> failures =
        run.failures().stream()
            .filter(f -> f.startsWith(test.rule() + ": "))
            .map(f -> f.substring(test.rule().length() + 2))
            .toList();
    List<Simulator.Call> made =
        run.calls().stream().filter(c -> c.rule().equals(test.rule())).toList();
    String difference = failures.isEmpty() ? compare(test, made) : String.join("; ", failures);
    return new Result(layer, test, difference.isEmpty(), difference, run.fired());
  }

  private static String compare(RuleTest test, List<Simulator.Call> made) {
    List<RuleTest.ExpectedCall> expected = test.calls();
    for (int i = 0; i < Math.max(expected.size(), made.size()); i++) {
      if (i >= made.size()) {
        return "call "
            + (i + 1)
            + " should be "
            + describe(expected.get(i))
            + ", but the rule made no more calls";
      }
      if (i >= expected.size()) {
        return "the rule made an unexpected call " + (i + 1) + ": " + made.get(i).describe();
      }
      RuleTest.ExpectedCall want = expected.get(i);
      Simulator.Call got = made.get(i);
      boolean same = want.capability().equals(got.capability());
      for (var arg : want.args().entrySet()) {
        same &=
            got.args().containsKey(arg.getKey())
                && matches(arg.getValue(), got.args().get(arg.getKey()));
      }
      if (!same) {
        return "call " + (i + 1) + " should be " + describe(want) + ", but is " + got.describe();
      }
    }
    return "";
  }

  private static String describe(RuleTest.ExpectedCall call) {
    return call.capability() + (call.args().isEmpty() ? "" : " " + call.args());
  }
}
