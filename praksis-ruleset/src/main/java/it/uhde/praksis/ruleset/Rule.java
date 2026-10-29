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
    List<Action> actions) {
  public Rule {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(description, "description");
    Objects.requireNonNull(effect, "effect");
    Objects.requireNonNull(trigger, "trigger");
    needs = List.copyOf(needs);
    actions = List.copyOf(actions);
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
