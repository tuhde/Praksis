// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;

/**
 * A version of a rule that is trusted to run live.
 *
 * @param ruleId the rule
 * @param hash the hash of the rule's content
 * @param time when it was promoted, or adopted as the baseline
 * @param json the rule in the shape its file has, so that it can run when a newer version is still
 *     on trial
 */
public record Promoted(String ruleId, String hash, Instant time, String json) {}
