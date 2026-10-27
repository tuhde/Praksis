// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of the run log: what happened when an event was handled.
 *
 * @param time when it happened
 * @param eventId the event being handled
 * @param ruleId the rule involved, or empty for an entry about the event as a whole
 * @param actionId the action involved, or empty
 * @param outcome how it went
 * @param message a short description
 */
public record LogEntry(
    Instant time,
    String eventId,
    Optional<String> ruleId,
    Optional<String> actionId,
    Outcome outcome,
    String message) {
  /** How a logged step went. */
  public enum Outcome {
    OK,
    FAILED,
    SKIPPED
  }

  public LogEntry {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(actionId, "actionId");
    Objects.requireNonNull(outcome, "outcome");
    Objects.requireNonNull(message, "message");
  }
}
