// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import java.time.Instant;

/**
 * What a rule on trial would have done for one event.
 *
 * @param time when the event was seen
 * @param ruleId the rule
 * @param hash the version of the rule
 * @param eventId the event
 * @param calls the calls it would have made, as a JSON array of objects with {@code capability},
 *     {@code args}, {@code allowed} and {@code reason}
 * @param note a failure, or {@code stop}, or empty
 */
public record ShadowRun(
    Instant time, String ruleId, String hash, String eventId, String calls, String note) {}
