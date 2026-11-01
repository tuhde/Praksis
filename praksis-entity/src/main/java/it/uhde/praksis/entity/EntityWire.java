// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts entities from and to what a client sends and receives as JSON: times as ISO-8601 text,
 * bytes as Base64. A client that cannot tell types apart (a command line) may send a text for any
 * scalar field, and it is read as the type of the field.
 */
public final class EntityWire {
  private EntityWire() {}

  /** The values of a client as the Java types of the fields of the type. */
  public static Map<String, Object> values(EntityType type, Map<String, Object> wire)
      throws EntityException {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : wire.entrySet()) {
      EntityType.Field f = type.field(e.getKey()).orElse(null);
      out.put(
          e.getKey(), f == null || !(e.getValue() instanceof String s) ? e.getValue() : read(f, s));
    }
    return out;
  }

  private static Object read(EntityType.Field f, String text) throws EntityException {
    try {
      return switch (f.type()) {
        case INT -> Long.parseLong(text.strip());
        case DOUBLE -> Double.parseDouble(text.strip());
        case BOOL -> {
          if (!text.strip().equals("true") && !text.strip().equals("false")) {
            throw new EntityException("field '" + f.name() + "' must be true or false");
          }
          yield Boolean.parseBoolean(text.strip());
        }
        case BYTES -> Base64.getDecoder().decode(text.strip());
        default -> text;
      };
    } catch (NumberFormatException e) {
      throw new EntityException(
          "field '"
              + f.name()
              + "' must be "
              + (f.type() == it.uhde.praksis.capability.ValueType.INT
                  ? "a whole number"
                  : "a number"));
    } catch (IllegalArgumentException e) {
      throw new EntityException("field '" + f.name() + "' must be Base64");
    }
  }

  /** An entity (or the values of some fields) as JSON values. */
  public static Map<String, Object> entity(Map<String, Object> entity) {
    Map<String, Object> out = new LinkedHashMap<>();
    entity.forEach((k, v) -> out.put(k, json(v)));
    return out;
  }

  private static Object json(Object v) {
    return switch (v) {
      case Instant t -> EntityValues.time(t);
      case byte[] b -> Base64.getEncoder().encodeToString(b);
      case List<?> l -> l.stream().map(EntityWire::json).toList();
      case Map<?, ?> m -> {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, x) -> out.put(String.valueOf(k), json(x)));
        yield out;
      }
      default -> v;
    };
  }
}
