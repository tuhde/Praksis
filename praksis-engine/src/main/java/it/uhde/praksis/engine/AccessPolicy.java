// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityDescriptor;
import java.util.Map;

/**
 * Decides whether a rule may make a capability call. Every call goes through it, so permissions
 * (grants, scopes, credentials) can be added without touching the dispatcher.
 */
@FunctionalInterface
public interface AccessPolicy {
  /**
   * Checks one call.
   *
   * @param ruleId the rule making the call
   * @param capability the capability being called
   * @param args the evaluated arguments
   */
  Decision check(String ruleId, CapabilityDescriptor capability, Map<String, Object> args);

  /**
   * The answer of a policy.
   *
   * @param allowed whether the call may go ahead
   * @param reason why not, if it may not
   */
  record Decision(boolean allowed, String reason) {
    public static Decision allow() {
      return new Decision(true, "");
    }

    public static Decision deny(String reason) {
      return new Decision(false, reason);
    }
  }

  /** The policy until permissions exist: everything is allowed. */
  static AccessPolicy allowAll() {
    return (rule, capability, args) -> Decision.allow();
  }
}
