// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * One line of the audit log: a capability call, a change of the grants, or the grants found at
 * start. Unlike the run log it is about what was allowed, not about handling events.
 *
 * @param time when it was decided
 * @param kind what kind of line it is
 * @param ruleId the rule that made the call, or empty
 * @param subject the capability for a call, the grant id for a grant change, else the file
 * @param allowed whether the access policy let a call through; true for the other kinds
 * @param detail JSON: the arguments of a call, or what changed
 * @param error what went wrong with a call, if anything
 */
public record AuditEntry(
    Instant time,
    Kind kind,
    Optional<String> ruleId,
    String subject,
    boolean allowed,
    String detail,
    Optional<String> error) {
  /** What an audit line is about. */
  public enum Kind {
    /** A capability call, allowed or denied. */
    CALL,
    /** A grant was added or revoked through the daemon. */
    GRANT,
    /** A request for a permission was opened or answered. */
    APPROVAL,
    /** The grants in {@code policy.toml} when the daemon started, if they differ from last time. */
    POLICY
  }

  /** The line for a capability call. */
  public static AuditEntry call(
      Instant time,
      String ruleId,
      String capability,
      java.util.Map<String, Object> args,
      boolean allowed,
      Optional<String> error) {
    return new AuditEntry(
        time,
        Kind.CALL,
        Optional.of(ruleId),
        capability,
        allowed,
        EventCodec.encodeMap(args),
        error);
  }

  /** A line that is not a call, with what happened as JSON built from the map. */
  public static AuditEntry of(
      Instant time,
      Kind kind,
      Optional<String> ruleId,
      String subject,
      java.util.Map<String, Object> detail) {
    return new AuditEntry(
        time, kind, ruleId, subject, true, EventCodec.encodeMap(detail), Optional.empty());
  }

  public AuditEntry {
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(subject, "subject");
    Objects.requireNonNull(detail, "detail");
    Objects.requireNonNull(error, "error");
  }
}
