// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

/**
 * Which changes to a ruleset need the user, as set in {@code [approval]} of {@code policy.toml}.
 * Everything is on unless the user turns it off; this is the strict version.
 *
 * @param widening a change that needs access which no grant covers
 * @param destructiveOrExternal a new or changed rule whose effect class is destructive or external
 * @param userRules a change by anyone but the user to a rule the user wrote
 * @param shadowFirst every other change runs in shadow mode before it is promoted (if off, it needs
 *     nothing)
 */
public record ApprovalRules(
    boolean widening, boolean destructiveOrExternal, boolean userRules, boolean shadowFirst) {
  /** The strict version. */
  public static ApprovalRules strict() {
    return new ApprovalRules(true, true, true, true);
  }
}
