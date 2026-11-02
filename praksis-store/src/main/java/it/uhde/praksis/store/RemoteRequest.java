// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A request that a connected daemon causes and a person answers: it offers a function that was not
 * there before, or the members of a set that a grant is pinned to have changed. Unlike an {@link
 * Approval} no rule waits for it; the function simply cannot be used until it is approved.
 *
 * @param id the number the person uses to answer
 * @param time when it was opened
 * @param peer the daemon that caused it
 * @param kind {@code capability} (a new function) or {@code pin} (a pinned set changed)
 * @param subject what it is about, e.g. {@code capability:entity.read} or {@code pin:finance}
 * @param detail what a person needs to decide; text in it comes from the remote and is untrusted
 * @param state where it stands
 * @param answered when it was answered, if it was
 */
public record RemoteRequest(
    long id,
    Instant time,
    String peer,
    String kind,
    String subject,
    Map<String, Object> detail,
    State state,
    Optional<Instant> answered) {
  /** Where a request stands. */
  public enum State {
    /** Nobody has answered. */
    PENDING,
    /** A person approved. */
    APPROVED,
    /** Approved without asking, by the standing read grant of the peer (safe functions only). */
    AUTO,
    /** A person refused. */
    REFUSED;

    /** Whether this lets the function be used. */
    public boolean accepts() {
      return this == APPROVED || this == AUTO;
    }
  }

  public RemoteRequest {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(peer, "peer");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(subject, "subject");
    detail = Map.copyOf(detail);
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(answered, "answered");
  }
}
