// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.ValueType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Reads the condition of a call (a map with an {@code op}, see {@link Filter}) against an entity
 * type: the fields must exist, must be of a type that can be compared and must not be sensitive,
 * and the values are converted to the type of their field.
 */
public final class Filters {
  private Filters() {}

  /** Types a filter may compare. */
  static final Set<ValueType> COMPARABLE =
      Set.of(
          ValueType.STRING, ValueType.INT, ValueType.DOUBLE, ValueType.BOOL, ValueType.TIMESTAMP);

  /** The type of a field a filter or sort may use, or empty if it cannot. */
  public static Optional<ValueType> typeOf(EntityType type, String field) {
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
  public static Filter parse(EntityType type, Object raw) throws EntityException {
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
        return name.equals("and") ? new Filter.And(parts) : new Filter.Or(parts);
      }
      case "not" -> {
        return new Filter.Not(parse(type, m.get("of"), depth + 1));
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
        return new Filter.Equals(field, value(field, t, m.get("value"), "value"));
      }
      case "prefix" -> {
        if (t != ValueType.STRING) {
          throw new EntityException("filter prefix: '" + field + "' is not a text field");
        }
        return new Filter.Prefix(field, (String) value(field, t, m.get("value"), "value"));
      }
      case "one_of" -> {
        if (!(m.get("values") instanceof List<?> list) || list.isEmpty()) {
          throw new EntityException("filter one_of: 'values' must be a list with a value");
        }
        List<Object> values = new ArrayList<>();
        for (Object o : list) {
          values.add(value(field, t, o, "values"));
        }
        return new Filter.OneOf(field, values);
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
        return new Filter.Range(field, gt, gte, lt, lte);
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
