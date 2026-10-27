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

  /** The event being handled, or empty for a call that has no event (e.g. a command). */
  Optional<Event> event();
}
