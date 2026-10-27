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
   */
  record Call(String id, String capability, Map<String, Arg> args) implements Action {
    public Call {
      args = Map.copyOf(args);
    }
  }

  /** Ends the handling of the event: no later rule fires for it. */
  record Stop() implements Action {}
}
