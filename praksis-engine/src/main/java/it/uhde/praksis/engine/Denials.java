// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import java.util.Map;

/** Told when a rule is disabled because the access policy refused one of its calls. */
@FunctionalInterface
public interface Denials {
  /**
   * Called once when the rule becomes disabled, not again for later events it misses.
   *
   * @param ruleId the rule that is now disabled
   * @param capability the capability it called
   * @param args the arguments of the refused call
   * @param event the event being handled
   */
  void denied(
      String ruleId, CapabilityDescriptor capability, Map<String, Object> args, Event event);

  /** Nobody is told. */
  static Denials none() {
    return (rule, capability, args, event) -> {};
  }
}
