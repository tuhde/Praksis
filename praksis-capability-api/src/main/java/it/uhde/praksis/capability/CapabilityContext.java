// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.Optional;

/** The circumstances of one capability call. */
public interface CapabilityContext {
  /**
   * Whether this is a dry run. A capability that supports dry runs validates the call and reports
   * what it would do, but changes nothing.
   */
  boolean dryRun();

  /**
   * A key that is the same for every retry of the same action, so the capability can make the
   * effect idempotent where the target allows it.
   */
  String idempotencyKey();

  /**
   * The id of the rule that makes the call, or empty for a call that no rule makes. A capability
   * may keep it to say who changed something.
   */
  default Optional<String> ruleId() {
    return Optional.empty();
  }

  /**
   * Whether the rule that makes this call may also make another call of a capability, with these
   * arguments, as the grants stand. A capability whose call has effects on other things than its
   * arguments name (deleting an entity deletes the entities that depend on it) asks before it does
   * them, so that what a rule may do does not grow with what a definition says. The default allows
   * everything, for a call that no rule makes.
   *
   * @return empty if allowed, else why not
   */
  default Optional<String> refusal(String capability, java.util.Map<String, Object> args) {
    return Optional.empty();
  }

  /** The event being handled, or empty for a call that has no event (e.g. a command). */
  Optional<Event> event();
}
