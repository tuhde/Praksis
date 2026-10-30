// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.Map;

/** One step of a rule. */
public sealed interface Action {
  /**
   * A call of a module capability.
   *
   * @param id the action id, unique within the rule; later actions read the results as {@code
   *     results.<id>}
   * @param capability the capability name
   * @param args the arguments by parameter name
   * @param requiresModules modules that must be there for this step; if one is not, the step is
   *     left out and the rule does the rest (so a mail can be sent without an address book)
   * @param when a condition (an expression that is true or false) under which the step is done: a
   *     step whose condition is false is skipped, and later steps see its results as empty
   */
  record Call(
      String id,
      String capability,
      Map<String, Arg> args,
      java.util.List<String> requiresModules,
      java.util.Optional<String> when)
      implements Action {
    public Call {
      args = Map.copyOf(args);
      requiresModules = java.util.List.copyOf(requiresModules);
    }

    public Call(
        String id,
        String capability,
        Map<String, Arg> args,
        java.util.List<String> requiresModules) {
      this(id, capability, args, requiresModules, java.util.Optional.empty());
    }

    /** A step that needs no module in particular. */
    public Call(String id, String capability, Map<String, Arg> args) {
      this(id, capability, args, java.util.List.of(), java.util.Optional.empty());
    }
  }

  /**
   * Ends the handling of the event: no later rule fires for it.
   *
   * @param when a condition under which the event is stopped; without one it always is
   */
  record Stop(java.util.Optional<String> when) implements Action {
    public Stop() {
      this(java.util.Optional.empty());
    }
  }
}
