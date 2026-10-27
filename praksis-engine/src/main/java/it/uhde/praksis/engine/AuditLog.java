// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/** Receives a record of every capability call, allowed or not. */
@FunctionalInterface
public interface AuditLog {
  /**
   * Records one call.
   *
   * @param time when the call was decided
   * @param ruleId the rule making the call
   * @param capability the capability name
   * @param args the evaluated arguments
   * @param allowed whether the access policy let the call through
   * @param error what went wrong, if the call was denied or failed
   */
  void record(
      Instant time,
      String ruleId,
      String capability,
      Map<String, Object> args,
      boolean allowed,
      Optional<String> error);

  /** An audit log that keeps nothing. */
  static AuditLog none() {
    return (time, rule, capability, args, allowed, error) -> {};
  }
}
