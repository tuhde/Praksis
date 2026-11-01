// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.List;

/**
 * A condition on the fields of an entity, from a fixed vocabulary that every storage can implement.
 * In a call it is a map with an {@code op}:
 *
 * <pre>
 * { op = "equals", field = "email", value = "a@b.c" }
 * { op = "one_of", field = "kind", values = ["a", "b"] }
 * { op = "prefix", field = "name", value = "Mei" }
 * { op = "range", field = "age", gte = 18, lt = 65 }      # gt, gte, lt, lte
 * { op = "and", of = [ ... ] }    { op = "or", of = [ ... ] }    { op = "not", of = { ... } }
 * </pre>
 *
 * Here the condition is already read and checked: the values have the Java type of their field. An
 * entity without a value for a field does not match a condition on it (except under {@code not}).
 * {@link FilterMatcher} evaluates a filter on an entity in memory, for backends that cannot push it
 * down.
 */
public sealed interface Filter {
  /** {@code field = value}. */
  record Equals(String field, Object value) implements Filter {}

  /** The field is one of the values. */
  record OneOf(String field, List<Object> values) implements Filter {}

  /** The text field starts with the value. */
  record Prefix(String field, String value) implements Filter {}

  /** Bounds of a field; any may be null, at least one is set. */
  record Range(String field, Object gt, Object gte, Object lt, Object lte) implements Filter {}

  /** All conditions hold. */
  record And(List<Filter> of) implements Filter {}

  /** At least one condition holds. */
  record Or(List<Filter> of) implements Filter {}

  /** The condition does not hold. */
  record Not(Filter of) implements Filter {}
}
