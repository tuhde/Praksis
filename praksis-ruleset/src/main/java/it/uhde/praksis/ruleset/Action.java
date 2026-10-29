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
   */
  record Call(
      String id, String capability, Map<String, Arg> args, java.util.List<String> requiresModules)
      implements Action {
    public Call {
      args = Map.copyOf(args);
      requiresModules = java.util.List.copyOf(requiresModules);
    }

    /** A step that needs no module in particular. */
    public Call(String id, String capability, Map<String, Arg> args) {
      this(id, capability, args, java.util.List.of());
    }
  }

  /** Ends the handling of the event: no later rule fires for it. */
  record Stop() implements Action {}
}
