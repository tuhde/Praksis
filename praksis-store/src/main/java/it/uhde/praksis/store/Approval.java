// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A request for a permission: a rule made a call that no grant covers, so the rule was disabled
 * until a person answers.
 *
 * @param id the number the person uses to answer
 * @param time when the call was denied
 * @param ruleId the rule that is disabled
 * @param layer the layer that owns the rule, whose grants decide
 * @param capability the capability that was called
 * @param args the arguments of the denied call that a grant could restrict; this is what an "allow
 *     always" grant is made from
 * @param eventId the event whose handling was denied
 * @param state where the request stands
 * @param answered when it was answered, if it was
 */
public record Approval(
    long id,
    Instant time,
    String ruleId,
    String layer,
    String capability,
    Map<String, Object> args,
    String eventId,
    State state,
    Optional<Instant> answered) {
  /** Where a request stands. */
  public enum State {
    /** Nobody has answered; the rule stays disabled. */
    PENDING,
    /** Refused; the rule stays disabled until a grant covers the call. */
    DENIED,
    /** The one pending call was let through; the rule is enabled again. */
    ALLOWED_ONCE,
    /** A grant was made from it; the rule is enabled again. */
    ALLOWED_ALWAYS,
    /** A grant that was added by hand covers it now. */
    COVERED,
    /** The rule does not exist any more. */
    STALE;

    /** Whether the rule stays disabled in this state. */
    public boolean disables() {
      return this == PENDING || this == DENIED;
    }
  }

  public Approval {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(capability, "capability");
    args = Map.copyOf(args);
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(answered, "answered");
  }
}
