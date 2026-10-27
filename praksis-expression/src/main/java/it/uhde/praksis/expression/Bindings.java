// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression;

import it.uhde.praksis.capability.Event;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The values of the variables of a {@link Scope} for one evaluation.
 *
 * @param event the event being handled; must be present if the scope has an event type
 * @param params the ruleset parameters
 * @param results the results of earlier actions, by action id
 * @param now the current time
 */
public record Bindings(
    Optional<Event> event,
    Map<String, Object> params,
    Map<String, Map<String, Object>> results,
    Instant now) {
  public Bindings {
    Objects.requireNonNull(event, "event");
    Objects.requireNonNull(now, "now");
    params = Map.copyOf(params);
    results = Map.copyOf(results);
  }

  /** Bindings with only the time. */
  public static Bindings of(Instant now) {
    return new Bindings(Optional.empty(), Map.of(), Map.of(), now);
  }
}
