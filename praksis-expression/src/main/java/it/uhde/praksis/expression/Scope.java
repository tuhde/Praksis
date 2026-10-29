// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression;

import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The variables an expression may use and their types. Compiling against a scope reports unknown
 * fields and type errors. The variable {@code now} is always available.
 *
 * @param event the type of {@code event}; the variable is not available if empty. {@code
 *     event.data} holds the typed core fields, and {@code event.extras} holds the free-form rest of
 *     the event's data
 * @param params the ruleset parameters, available as {@code params}
 * @param results the result fields of earlier actions in the same rule, by action id, available as
 *     {@code results.<id>}
 * @param dynamic further variables of no declared type (for example {@code view}, {@code source}
 *     and {@code item} in a view), whose values are plain maps, lists and values; the verifier can
 *     check only that they exist
 */
public record Scope(
    Optional<EventTypeDescriptor> event,
    List<FieldDescriptor> params,
    Map<String, List<FieldDescriptor>> results,
    java.util.Set<String> dynamic) {
  public Scope {
    Objects.requireNonNull(event, "event");
    params = List.copyOf(params);
    results = Map.copyOf(results);
    dynamic = java.util.Set.copyOf(dynamic);
  }

  /** A scope without further variables. */
  public Scope(
      Optional<EventTypeDescriptor> event,
      List<FieldDescriptor> params,
      Map<String, List<FieldDescriptor>> results) {
    this(event, params, results, java.util.Set.of());
  }

  /** A scope with only {@code now}. */
  public static Scope empty() {
    return new Scope(Optional.empty(), List.of(), Map.of(), java.util.Set.of());
  }
}
