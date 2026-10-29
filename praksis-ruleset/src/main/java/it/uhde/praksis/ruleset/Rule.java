// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.EffectClass;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One rule.
 *
 * @param id the stable id, which is also the file name without {@code .toml}
 * @param description what the rule is for, or empty
 * @param effect the strongest effect class of the rule's actions
 * @param needs the capabilities the rule uses
 * @param required whether a failure of this rule fails the handling of the event
 * @param trigger what makes the rule fire
 * @param actions the steps, in order
 */
public record Rule(
    String id,
    Optional<String> description,
    EffectClass effect,
    List<String> needs,
    boolean required,
    Trigger trigger,
    List<Action> actions,
    List<String> requiresModules) {
  public Rule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(trigger, "trigger");
    needs = List.copyOf(needs);
    actions = List.copyOf(actions);
    requiresModules = List.copyOf(requiresModules);
  }

  /**
   * The rule as it is with these modules: the steps that ask for a module that is not there are
   * left out, and so are the capabilities that only they needed.
   *
   * @return the rule, or empty if no step is left
   */
  public Optional<Rule> available(java.util.Set<String> modules) {
    List<Action> kept = new java.util.ArrayList<>();
    java.util.Set<String> left = new java.util.HashSet<>();
    java.util.Set<String> dropped = new java.util.HashSet<>();
    for (Action a : actions) {
      if (a instanceof Action.Call call && !modules.containsAll(call.requiresModules())) {
        dropped.add(call.capability());
      } else {
        kept.add(a);
        if (a instanceof Action.Call call) {
          left.add(call.capability());
        }
      }
    }
    if (kept.size() == actions.size()) {
      return Optional.of(this);
    }
    if (kept.stream().noneMatch(a -> a instanceof Action.Call)) {
      return Optional.empty();
    }
    dropped.removeAll(left);
    return Optional.of(
        new Rule(
            id,
            description,
            effect,
            needs.stream().filter(n -> !dropped.contains(n)).toList(),
            required,
            trigger,
            kept,
            requiresModules));
  }

  /** A rule that does not depend on a module being there. */
  public Rule(
      String id,
      Optional<String> description,
      EffectClass effect,
      List<String> needs,
      boolean required,
      Trigger trigger,
      List<Action> actions) {
    this(id, description, effect, needs, required, trigger, actions, List.of());
  }

  /**
   * The type of the events this rule fires for: the type it names, or for a command {@code
   * command.<id>}.
   */
  public String eventType() {
    return switch (trigger) {
      case Trigger.OnEvent e -> e.type();
      case Trigger.OnCommand c -> "command." + id;
    };
  }
}
