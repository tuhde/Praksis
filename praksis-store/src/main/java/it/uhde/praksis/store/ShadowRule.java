// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;

/**
 * A version of a rule that is on trial: it is observed and does nothing.
 *
 * @param ruleId the rule
 * @param hash the hash of the rule's content
 * @param since when the trial started
 */
public record ShadowRule(String ruleId, String hash, Instant since) {}
