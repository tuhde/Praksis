// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.expression.Expression;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * A rule whose expressions are compiled and whose literals are converted to their declared types.
 *
 * @param rule the rule as written
 * @param filter the compiled trigger filter, if the rule has one
 * @param actions the compiled actions, in order
 */
public record CompiledRule(Rule rule, Optional<Expression> filter, List<CompiledAction> actions) {
  public CompiledRule {
    actions = List.copyOf(actions);
  }

  /** One compiled step of a rule. */
  public sealed interface CompiledAction {
    /** A capability call with converted arguments. */
    record Call(String id, String capability, Map<String, CompiledArg> args)
        implements CompiledAction {
      public Call {
        args = Map.copyOf(args);
      }
    }

    /** Ends the handling of the event. */
    record Stop() implements CompiledAction {}
  }

  /** A compiled action argument. */
  public sealed interface CompiledArg {
    /** A value known in advance, already of its declared Java type. */
    record Value(Object value) implements CompiledArg {}

    /** An expression evaluated when the action runs. */
    record Computed(Expression expression) implements CompiledArg {}
  }
}
