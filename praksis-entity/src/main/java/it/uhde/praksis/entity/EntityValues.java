// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.ValueType;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Converts the values of fields between the Java types of {@link ValueType} and what is stored.
 * Timestamps are stored as text with millisecond precision in UTC, so that the text sorts like the
 * time; bytes are stored as Base64.
 */
final class EntityValues {
  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

  private EntityValues() {}

  static String time(Instant t) {
    return TIME.format(t);
  }

  static Instant parseTime(String s) throws EntityException {
    try {
      return Instant.parse(s);
    } catch (DateTimeParseException e) {
      throw new EntityException("'" + s + "' is not a time like 2026-11-01T10:00:00Z");
    }
  }

  /** The value as the Java type of {@code type}, or an exception if it does not fit. */
  static Object normalize(String field, ValueType type, Object v) throws EntityException {
    boolean ok =
        switch (type) {
          case STRING -> v instanceof String;
          case INT -> v instanceof Long || v instanceof Integer || v instanceof Short;
          case DOUBLE -> v instanceof Number;
          case BOOL -> v instanceof Boolean;
          case TIMESTAMP -> v instanceof Instant || v instanceof String;
          case BYTES -> v instanceof byte[];
          case LIST -> v instanceof List<?>;
          case MAP -> v instanceof Map<?, ?>;
        };
    if (!ok) {
      throw new EntityException(
          "field '" + field + "' must be of type " + type.id() + ", not " + describe(v));
    }
    return switch (type) {
      case INT -> ((Number) v).longValue();
      case DOUBLE -> ((Number) v).doubleValue();
      case TIMESTAMP -> {
        Instant t = v instanceof Instant i ? i : parseTime((String) v);
        yield Instant.parse(time(t));
      }
      default -> v;
    };
  }

  private static String describe(Object v) {
    return v == null ? "nothing" : v.getClass().getSimpleName();
  }

  /** What is written to the JSON of the entity for a normalized value. */
  static Object toStored(ValueType type, Object v) {
    return switch (type) {
      case TIMESTAMP -> time((Instant) v);
      case BYTES -> Base64.getEncoder().encodeToString((byte[]) v);
      default -> v;
    };
  }

  /** The Java value for what was read from the JSON of the entity. */
  static Object fromStored(ValueType type, Object v) {
    return switch (type) {
      case INT -> ((Number) v).longValue();
      case DOUBLE -> ((Number) v).doubleValue();
      case TIMESTAMP -> Instant.parse((String) v);
      case BYTES -> Base64.getDecoder().decode((String) v);
      default -> v;
    };
  }
}
