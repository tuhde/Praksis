// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.math.BigInteger;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Turns an injected JSON payload into a properly typed event, or says what is wrong with it. */
final class Injection {
  private Injection() {}

  /** Thrown with every problem found, one per line. */
  static final class Invalid extends Exception {
    private static final long serialVersionUID = 1L;

    Invalid(List<String> problems) {
      super(String.join("\n", problems));
    }
  }

  static Event event(Request.Inject request, ModuleCatalog catalog, Clock clock) throws Invalid {
    Optional<EventTypeDescriptor> descriptor =
        request.type() == null ? Optional.empty() : catalog.eventType(request.type());
    if (descriptor.isEmpty()) {
      throw new Invalid(List.of("unknown event type '" + request.type() + "'"));
    }
    List<String> problems = new ArrayList<>();
    Map<String, Object> data = new HashMap<>();
    Set<String> known = new HashSet<>();
    for (FieldDescriptor field : descriptor.get().fields()) {
      known.add(field.name());
      Object raw = request.data().get(field.name());
      if (raw == null) {
        if (field.required()) {
          problems.add("missing required field '" + field.name() + "'");
        }
        continue;
      }
      try {
        data.put(field.name(), coerce(field, raw));
      } catch (IllegalArgumentException e) {
        problems.add("field '" + field.name() + "': " + e.getMessage());
      }
    }
    for (String key : request.data().keySet()) {
      if (!known.contains(key)) {
        problems.add("unknown field '" + key + "' for event type '" + request.type() + "'");
      }
    }
    if (!problems.isEmpty()) {
      throw new Invalid(problems);
    }
    return new Event(
        UUID.randomUUID().toString(),
        request.type(),
        "cli",
        clock.instant(),
        request.target() == null ? "cli" : request.target(),
        Optional.ofNullable(request.key()),
        Optional.empty(),
        0,
        data);
  }

  @SuppressWarnings("unchecked")
  private static Object coerce(FieldDescriptor field, Object v) {
    switch (field.type()) {
      case STRING:
        if (v instanceof String) {
          return v;
        }
        break;
      case INT:
        if (v instanceof Integer || v instanceof Long || v instanceof Short) {
          return ((Number) v).longValue();
        }
        if (v instanceof BigInteger b && b.bitLength() < 64) {
          return b.longValue();
        }
        break;
      case DOUBLE:
        if (v instanceof Number n) {
          return n.doubleValue();
        }
        break;
      case BOOL:
        if (v instanceof Boolean) {
          return v;
        }
        break;
      case TIMESTAMP:
        if (v instanceof String s) {
          try {
            return Instant.parse(s);
          } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("not an ISO-8601 timestamp: " + s);
          }
        }
        break;
      case BYTES:
        if (v instanceof String s) {
          return Base64.getDecoder().decode(s);
        }
        break;
      case LIST:
        if (v instanceof List<?>) {
          return v;
        }
        break;
      case MAP:
        if (v instanceof Map<?, ?>) {
          return (Map<String, Object>) v;
        }
        break;
    }
    throw new IllegalArgumentException("must be of type " + field.type().id());
  }
}
