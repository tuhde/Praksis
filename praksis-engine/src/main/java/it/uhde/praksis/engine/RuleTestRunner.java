// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.RuleTest;
import it.uhde.praksis.ruleset.RuleTests;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Store;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

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
   * @param params parameter values from the local configuration
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
        effective = LayerStack.resolve(layers.subList(0, i + 1), params);
      } catch (RulesetException e) {
        problems.addAll(e.problems());
        continue;
      }
      for (RuleTest test : tests) {
        results.add(run(layer.name(), effective, test));
      }
    }
    return new Report(results, problems);
  }

  private Result run(String layer, LayerStack.Effective effective, RuleTest test) {
    if (effective.ruleset().rules().stream().noneMatch(r -> r.id().equals(test.rule()))) {
      return new Result(
          layer,
          test,
          false,
          "there is no rule '" + test.rule() + "' in the ruleset up to layer '" + layer + "'",
          Set.of());
    }
    Path temp = null;
    try {
      temp = Files.createTempDirectory("praksis-test");
      return execute(layer, effective, test, temp);
    } catch (IOException | InterruptedException | RuntimeException | RulesetException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      return new Result(layer, test, false, "cannot run the test: " + e.getMessage(), Set.of());
    } catch (Exception e) {
      return new Result(layer, test, false, "cannot run the test: " + e, Set.of());
    } finally {
      if (temp != null) {
        delete(temp);
      }
    }
  }

  private Result execute(String layer, LayerStack.Effective effective, RuleTest test, Path temp)
      throws Exception {
    List<Call> calls = Collections.synchronizedList(new ArrayList<>());
    Map<String, Capability> recorders = new HashMap<>();
    for (CapabilityDescriptor c : catalog.capabilities()) {
      Map<String, Object> mocked = test.mocks().getOrDefault(c.name(), Map.of());
      recorders.put(c.name(), new Recorder(c.name(), mocked));
    }
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
            test.eventData());
    try (Store store = Store.open(temp.resolve("test.db"));
        Engine engine =
            new Engine(
                effective.ruleset(),
                catalog,
                compiler,
                recorders,
                store,
                new Engine.Options(
                    AccessPolicy.allowAll(),
                    (time, rule, capability, args, allowed, error) ->
                        calls.add(new Call(rule, capability, args, error)),
                    java.time.Clock.systemUTC(),
                    false))) {
      engine.submit(event);
      if (!engine.awaitIdle(Duration.ofSeconds(30))) {
        return new Result(
            layer, test, false, "the event was not handled within 30 seconds", Set.of());
      }
      Set<String> fired = new LinkedHashSet<>();
      List<String> failures = new ArrayList<>();
      for (LogEntry entry : store.recentLog(1000)) {
        if (!entry.eventId().equals(event.id()) || entry.ruleId().isEmpty()) {
          continue;
        }
        fired.add(entry.ruleId().get());
        if (entry.outcome() != LogEntry.Outcome.OK
            && entry.ruleId().get().equals(test.rule())
            && entry.outcome() != LogEntry.Outcome.SKIPPED) {
          failures.add(entry.message());
        }
      }
      List<Call> made = calls.stream().filter(c -> c.rule().equals(test.rule())).toList();
      String difference = failures.isEmpty() ? compare(test, made) : String.join("; ", failures);
      return new Result(layer, test, difference.isEmpty(), difference, fired);
    }
  }

  private static String compare(RuleTest test, List<Call> made) {
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
      Call got = made.get(i);
      boolean same = want.capability().equals(got.capability());
      for (var arg : want.args().entrySet()) {
        same &=
            got.args().containsKey(arg.getKey())
                && new ArgMatcher.Equals(arg.getValue()).matches(got.args().get(arg.getKey()));
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

  private static void delete(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    } catch (IOException e) {
      // a temporary directory that stays behind does no harm
    }
  }

  private record Call(
      String rule, String capability, Map<String, Object> args, Optional<String> error) {
    String describe() {
      return capability + " " + args;
    }
  }

  /** Stands in for a capability: does nothing and returns the mocked results. */
  private record Recorder(String name, Map<String, Object> results) implements Capability {
    @Override
    public Map<String, Object> invoke(
        it.uhde.praksis.capability.CapabilityContext context, Map<String, Object> args) {
      return results;
    }
  }
}
