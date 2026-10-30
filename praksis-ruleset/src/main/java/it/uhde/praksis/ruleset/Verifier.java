// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.expression.ExpressionCompiler;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The static checks of the verifier. It is deterministic code that reads a ruleset and the
 * installed modules and reports what is wrong, so a person or an AI can fix it before the ruleset
 * runs. It is not changed by rulesets.
 *
 * <p>Checks: everything {@link RulesetValidator} finds (check {@code validate}); a rule that lists
 * a capability in {@code needs} that none of its actions calls ({@code unused-need}); a rule that
 * claims a stronger effect than its capabilities have ({@code effect-overstated}); a rule that can
 * only fire if an earlier rule fails, because that rule handles every event of the type and stops
 * ({@code shadowed}); and a ruleset without rules ({@code empty}).
 */
public final class Verifier {
  /** How serious a finding is. */
  public enum Severity {
    /** The ruleset must not be used like this. */
    ERROR,
    /** Probably not what was meant, but harmless. */
    WARNING
  }

  /**
   * One thing the verifier found.
   *
   * @param severity how serious it is
   * @param check which check found it, e.g. {@code unused-need}
   * @param where the file or rule it is about
   * @param message what is wrong
   */
  public record Finding(Severity severity, String check, String where, String message) {
    @Override
    public String toString() {
      return severity + " " + check + " " + where + ": " + message;
    }
  }

  /**
   * What a verification found.
   *
   * @param findings the findings, errors first, otherwise in the order found
   */
  public record Report(List<Finding> findings) {
    public Report {
      List<Finding> sorted = new ArrayList<>(findings);
      sorted.sort(java.util.Comparator.comparing(Finding::severity));
      findings = List.copyOf(sorted);
    }

    /** Whether there are no errors; warnings do not count. */
    public boolean ok() {
      return errors() == 0;
    }

    public long errors() {
      return findings.stream().filter(f -> f.severity() == Severity.ERROR).count();
    }

    public long warnings() {
      return findings.stream().filter(f -> f.severity() == Severity.WARNING).count();
    }
  }

  private final ExpressionCompiler compiler;
  private final ModuleCatalog catalog;

  public Verifier(ExpressionCompiler compiler, ModuleCatalog catalog) {
    this.compiler = compiler;
    this.catalog = catalog;
  }

  /** Checks a ruleset. */
  public Report verify(Ruleset ruleset) {
    List<Finding> findings = new ArrayList<>();
    for (Problem p : new RulesetValidator(compiler, catalog).validate(ruleset)) {
      findings.add(new Finding(Severity.ERROR, "validate", p.location(), p.message()));
    }
    if (ruleset.rules().isEmpty()) {
      findings.add(new Finding(Severity.WARNING, "empty", "ruleset", "the ruleset has no rules"));
    }
    for (Rule rule : ruleset.rules()) {
      String where = "rules/" + rule.id() + ".toml";
      unusedNeeds(rule, where, findings);
      effectOverstated(rule, where, findings);
    }
    shadowed(ruleset.rules(), findings);
    return new Report(findings);
  }

  private static Set<String> called(Rule rule) {
    Set<String> out = new HashSet<>();
    for (Action a : rule.actions()) {
      if (a instanceof Action.Call c) {
        out.add(c.capability());
      }
    }
    return out;
  }

  private static void unusedNeeds(Rule rule, String where, List<Finding> out) {
    Set<String> called = called(rule);
    for (String need : rule.needs()) {
      if (!called.contains(need)) {
        out.add(
            new Finding(
                Severity.ERROR,
                "unused-need",
                where,
                "'needs' lists '"
                    + need
                    + "' but no action calls it; a rule may only ask for what"
                    + " it uses"));
      }
    }
  }

  private void effectOverstated(Rule rule, String where, List<Finding> out) {
    int strongest = -1;
    for (String capability : called(rule)) {
      Optional<CapabilityDescriptor> d = catalog.capability(capability);
      if (d.isPresent()) {
        strongest = Math.max(strongest, d.get().effect().ordinal());
      }
    }
    if (strongest >= 0 && rule.effect().ordinal() > strongest) {
      out.add(
          new Finding(
              Severity.WARNING,
              "effect-overstated",
              where,
              "declares effect '"
                  + rule.effect().id()
                  + "' but its capabilities have at most '"
                  + it.uhde.praksis.capability.EffectClass.values()[strongest].id()
                  + "'"));
    }
  }

  /** A rule with no filter that stops handles every event of its type: later rules are shadowed. */
  private static void shadowed(List<Rule> rules, List<Finding> out) {
    for (int i = 0; i < rules.size(); i++) {
      Rule first = rules.get(i);
      if (!(first.trigger() instanceof Trigger.OnEvent t)
          || t.filter().isPresent()
          || first.actions().stream()
              .noneMatch(a -> a instanceof Action.Stop stop && stop.when().isEmpty())) {
        continue;
      }
      for (Rule later : rules.subList(i + 1, rules.size())) {
        if (later.trigger() instanceof Trigger.OnEvent lt && lt.type().equals(t.type())) {
          out.add(
              new Finding(
                  Severity.WARNING,
                  "shadowed",
                  "rules/" + later.id() + ".toml",
                  "can only fire if '"
                      + first.id()
                      + "' fails before its stop: '"
                      + first.id()
                      + "' has no filter, handles every "
                      + t.type()
                      + " event and stops"));
        }
      }
    }
  }
}
