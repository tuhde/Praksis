// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Runs stored events through two rulesets, with recorders instead of capabilities, and reports the
 * events that they treat differently: what the rules would have called, in which order, with which
 * arguments, and which rules failed. Nothing is changed outside the process.
 */
public final class Replay {
  private Replay() {}

  /**
   * An event that the two rulesets treat differently.
   *
   * @param event the event
   * @param current what the current ruleset does, one line per call or failure
   * @param candidate what the candidate does
   */
  public record Difference(Event event, List<String> current, List<String> candidate) {
    public Difference {
      current = List.copyOf(current);
      candidate = List.copyOf(candidate);
    }
  }

  /**
   * The outcome.
   *
   * @param events how many events were replayed
   * @param differences the events that are treated differently, in order
   */
  public record Report(int events, List<Difference> differences) {
    public Report {
      differences = List.copyOf(differences);
    }
  }

  /**
   * Compares the behaviour of two rulesets on the events.
   *
   * @throws RulesetException if one of the rulesets cannot be loaded by the engine
   */
  public static Report compare(
      Ruleset current,
      Ruleset candidate,
      List<Event> events,
      ModuleCatalog catalog,
      ExpressionCompiler compiler)
      throws Exception {
    List<Difference> differences = new ArrayList<>();
    try (Simulator before = new Simulator(current, catalog, compiler, Map.of());
        Simulator after = new Simulator(candidate, catalog, compiler, Map.of())) {
      for (Event event : events) {
        List<String> was = lines(before.run(event));
        List<String> will = lines(after.run(event));
        if (!was.equals(will)) {
          differences.add(new Difference(event, was, will));
        }
      }
    }
    return new Report(events.size(), differences);
  }

  private static List<String> lines(Simulator.Run run) {
    List<String> out = new ArrayList<>();
    run.calls().forEach(c -> out.add(c.rule() + ": " + c.describe()));
    run.failures().forEach(f -> out.add("FAILED " + f));
    return out;
  }
}
