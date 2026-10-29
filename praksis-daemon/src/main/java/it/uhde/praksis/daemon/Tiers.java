// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.ruleset.ApprovalRules;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Permissions;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.Rule;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Decides what a change to the ruleset needs before it may take effect. Approval is tiered by what
 * a change does, and the strict version is the default (see {@link ApprovalRules}):
 *
 * <ul>
 *   <li>{@link Tier#APPROVAL}: the change needs access that no grant covers, a new or changed rule
 *       has a destructive or external effect class, or someone but the user changes or removes a
 *       rule the user wrote;
 *   <li>{@link Tier#SHADOW}: anything else that is new or changed runs in shadow mode first;
 *   <li>{@link Tier#AUTOMATIC}: only if the user turned shadow mode for these changes off.
 * </ul>
 *
 * A rule counts as the user's only if the last commit that changed it has a good signature by one
 * of the user's keys, see {@link Provenance#author}.
 */
final class Tiers {
  private Tiers() {}

  /** How much a change needs, weakest first. */
  enum Tier {
    AUTOMATIC,
    SHADOW,
    APPROVAL
  }

  /**
   * The decision about one rule.
   *
   * @param rule the rule id
   * @param change {@code new}, {@code changed} or {@code removed}
   * @param tier what it needs
   * @param reasons why, one line each
   */
  record Decision(String rule, String change, Tier tier, List<String> reasons) {}

  /**
   * The decision about a whole change.
   *
   * @param tier the strongest tier of any rule
   * @param decisions one per rule that is new, changed or removed
   */
  record Verdict(Tier tier, List<Decision> decisions) {}

  /**
   * @param current the ruleset in use
   * @param candidate the ruleset that would replace it
   * @param currentWho who wrote each rule of the ruleset in use
   * @param candidateWho who wrote each rule of the candidate
   * @param needs what the candidate needs
   * @param diff what is new in it compared with the ruleset in use
   * @param policy the grants, the user\'s keys and the rules for approval
   */
  static Verdict decide(
      LayerStack.Effective current,
      LayerStack.Effective candidate,
      Function<LayerStack.Origin, Provenance> currentWho,
      Function<LayerStack.Origin, Provenance> candidateWho,
      Permissions.Needs needs,
      Permissions.Diff diff,
      Policy policy) {
    ApprovalRules rules = policy.approval();
    Map<String, LayerStack.Origin> before = new HashMap<>();
    current.origins().forEach(o -> before.put(o.ruleId(), o));
    Map<String, Rule> beforeRules = new HashMap<>();
    current.ruleset().rules().forEach(r -> beforeRules.put(r.id(), r));
    List<Permissions.Requirement> uncovered = Permissions.uncovered(diff.added(), policy);
    List<Decision> decisions = new ArrayList<>();
    Tier overall = Tier.AUTOMATIC;
    Map<String, Rule> afterRules = new HashMap<>();
    candidate.ruleset().rules().forEach(r -> afterRules.put(r.id(), r));

    for (LayerStack.Origin o : candidate.origins()) {
      LayerStack.Origin was = before.get(o.ruleId());
      if (was != null && was.hash().equals(o.hash())) {
        continue;
      }
      Rule rule = afterRules.get(o.ruleId());
      List<String> reasons = new ArrayList<>();
      boolean approval = false;
      if (rules.widening()) {
        for (Permissions.Requirement r : uncovered) {
          if (needs.rules().getOrDefault(r, new java.util.TreeSet<>()).contains(o.ruleId())) {
            approval = true;
            reasons.add("needs access that no grant covers: " + r.describe());
          }
        }
      }
      if (rules.destructiveOrExternal()
          && (rule.effect() == EffectClass.DESTRUCTIVE || rule.effect() == EffectClass.EXTERNAL)) {
        approval = true;
        reasons.add("effect class '" + rule.effect().id() + "'");
      }
      Provenance.Author author = candidateWho.apply(o).author(policy.trust());
      if (rules.userRules()
          && was != null
          && currentWho.apply(was).author(policy.trust()) == Provenance.Author.HUMAN
          && author != Provenance.Author.HUMAN) {
        approval = true;
        reasons.add(
            "changes a rule you wrote, and the change is not signed by one of your keys ("
                + author.label()
                + ")");
      }
      boolean command = rule.trigger() instanceof it.uhde.praksis.ruleset.Trigger.OnCommand;
      Tier tier =
          approval ? Tier.APPROVAL : rules.shadowFirst() && !command ? Tier.SHADOW : Tier.AUTOMATIC;
      if (!approval && command) {
        reasons.add("a command: it runs only when you start it, so there is nothing to observe");
      } else if (!approval) {
        reasons.add(
            rules.shadowFirst()
                ? "within the grants and without a destructive or external effect: runs in shadow"
                    + " mode first"
                : "within the grants and without a destructive or external effect");
      }
      decisions.add(new Decision(o.ruleId(), was == null ? "new" : "changed", tier, reasons));
      overall = max(overall, tier);
    }

    // a view only reads and shows; it needs approval only if it reads what no grant covers
    Map<String, String> viewBefore = new HashMap<>();
    current.viewOrigins().forEach(v -> viewBefore.put(v.viewId(), v.hash()));
    for (LayerStack.ViewOrigin v : candidate.viewOrigins()) {
      String was = viewBefore.get(v.viewId());
      if (v.hash().equals(was)) {
        continue;
      }
      List<String> reasons = new ArrayList<>();
      if (rules.widening()) {
        for (Permissions.Requirement r : uncovered) {
          if (needs
              .rules()
              .getOrDefault(r, new java.util.TreeSet<>())
              .contains("view:" + v.viewId())) {
            reasons.add("reads what no grant covers: " + r.describe());
          }
        }
      }
      Tier tier = reasons.isEmpty() ? Tier.AUTOMATIC : Tier.APPROVAL;
      if (reasons.isEmpty()) {
        reasons.add("a view only reads and shows what the grants allow");
      }
      decisions.add(
          new Decision("view:" + v.viewId(), was == null ? "new" : "changed", tier, reasons));
      overall = max(overall, tier);
    }

    for (LayerStack.Origin was : current.origins()) {
      if (afterRules.containsKey(was.ruleId())) {
        continue;
      }
      boolean mine =
          rules.userRules()
              && currentWho.apply(was).author(policy.trust()) == Provenance.Author.HUMAN;
      Tier tier = mine ? Tier.APPROVAL : Tier.AUTOMATIC;
      decisions.add(
          new Decision(
              was.ruleId(),
              "removed",
              tier,
              List.of(mine ? "removes a rule you wrote" : "removes a rule")));
      overall = max(overall, tier);
    }
    return new Verdict(overall, decisions);
  }

  private static Tier max(Tier a, Tier b) {
    return a.compareTo(b) >= 0 ? a : b;
  }
}
