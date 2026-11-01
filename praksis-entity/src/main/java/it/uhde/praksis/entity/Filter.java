// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
 * Fields are the declared ones of string, int, double, bool and timestamp type that are not
 * sensitive, and {@code id}, {@code created}, {@code updated}, {@code created_by} and {@code
 * updated_by}. An entity without a value for a field does not match a condition on it (except under
 * {@code not}).
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

  /** Types a filter may compare. */
  Set<ValueType> COMPARABLE =
      Set.of(
          ValueType.STRING, ValueType.INT, ValueType.DOUBLE, ValueType.BOOL, ValueType.TIMESTAMP);

  /** The type of a field a filter or sort may use, or empty if it cannot. */
  static Optional<ValueType> typeOf(EntityType type, String field) {
    return switch (field) {
      case "id", "created_by", "updated_by" -> Optional.of(ValueType.STRING);
      case "created", "updated" -> Optional.of(ValueType.TIMESTAMP);
      default ->
          type.field(field)
              .filter(f -> !f.sensitive())
              .map(EntityType.Field::type)
              .filter(COMPARABLE::contains);
    };
  }

  /** Reads the map of a call; values are converted to the type of their field. */
  static Filter parse(EntityType type, Object raw) throws EntityException {
    return parse(type, raw, 0);
  }

  private static Filter parse(EntityType type, Object raw, int depth) throws EntityException {
    if (depth > 8) {
      throw new EntityException("filter: nested more than 8 levels");
    }
    if (!(raw instanceof Map<?, ?> m)) {
      throw new EntityException("filter: must be a map with an 'op'");
    }
    Object op = m.get("op");
    if (!(op instanceof String name)) {
      throw new EntityException("filter: missing 'op'");
    }
    Set<String> keys =
        switch (name) {
          case "equals", "prefix" -> Set.of("op", "field", "value");
          case "one_of" -> Set.of("op", "field", "values");
          case "range" -> Set.of("op", "field", "gt", "gte", "lt", "lte");
          case "and", "or", "not" -> Set.of("op", "of");
          default -> throw new EntityException("filter: unknown op '" + name + "'");
        };
    for (Object k : m.keySet()) {
      if (!keys.contains(k)) {
        throw new EntityException("filter " + name + ": unknown key '" + k + "'");
      }
    }
    switch (name) {
      case "and", "or" -> {
        if (!(m.get("of") instanceof List<?> list) || list.isEmpty()) {
          throw new EntityException("filter " + name + ": 'of' must be a list of conditions");
        }
        List<Filter> parts = new ArrayList<>();
        for (Object o : list) {
          parts.add(parse(type, o, depth + 1));
        }
        return name.equals("and") ? new And(parts) : new Or(parts);
      }
      case "not" -> {
        return new Not(parse(type, m.get("of"), depth + 1));
      }
      default -> {}
    }
    String field = field(m);
    if (type.field(field).filter(EntityType.Field::sensitive).isPresent()) {
      throw new EntityException(
          "filter " + name + ": '" + field + "' is sensitive and cannot be used in a filter");
    }
    ValueType t =
        typeOf(type, field)
            .orElseThrow(
                () ->
                    new EntityException(
                        "filter "
                            + name
                            + ": '"
                            + field
                            + "' is not a field that can be compared in type '"
                            + type.id()
                            + "'"));
    switch (name) {
      case "equals" -> {
        return new Equals(field, value(field, t, m.get("value"), "value"));
      }
      case "prefix" -> {
        if (t != ValueType.STRING) {
          throw new EntityException("filter prefix: '" + field + "' is not a text field");
        }
        return new Prefix(field, (String) value(field, t, m.get("value"), "value"));
      }
      case "one_of" -> {
        if (!(m.get("values") instanceof List<?> list) || list.isEmpty()) {
          throw new EntityException("filter one_of: 'values' must be a list with a value");
        }
        List<Object> values = new ArrayList<>();
        for (Object o : list) {
          values.add(value(field, t, o, "values"));
        }
        return new OneOf(field, values);
      }
      default -> {
        if (t == ValueType.BOOL) {
          throw new EntityException("filter range: '" + field + "' is a bool field");
        }
        Object gt = bound(field, t, m, "gt");
        Object gte = bound(field, t, m, "gte");
        Object lt = bound(field, t, m, "lt");
        Object lte = bound(field, t, m, "lte");
        if (gt == null && gte == null && lt == null && lte == null) {
          throw new EntityException("filter range: give gt, gte, lt or lte");
        }
        return new Range(field, gt, gte, lt, lte);
      }
    }
  }

  private static String field(Map<?, ?> m) throws EntityException {
    if (m.get("field") instanceof String s) {
      return s;
    }
    throw new EntityException("filter: missing 'field'");
  }

  private static Object bound(String field, ValueType t, Map<?, ?> m, String key)
      throws EntityException {
    return m.containsKey(key) ? value(field, t, m.get(key), key) : null;
  }

  private static Object value(String field, ValueType t, Object v, String key)
      throws EntityException {
    if (v == null) {
      throw new EntityException("filter: '" + key + "' for '" + field + "' is missing");
    }
    return EntityValues.normalize(field, t, v);
  }
}
