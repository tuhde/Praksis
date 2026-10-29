// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Rule;
import it.uhde.praksis.ruleset.Trigger;
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
    return event(
        descriptor.get(),
        "cli",
        request.target() == null ? "cli" : request.target(),
        Optional.ofNullable(request.key()),
        request.data(),
        clock);
  }

  /** The event that starts a command: its arguments, checked against the declared parameters. */
  static Event command(
      Rule rule, Trigger.OnCommand command, Map<String, Object> arguments, Clock clock)
      throws Invalid {
    Map<String, Object> given = new HashMap<>(arguments);
    for (Trigger.CommandParam p : command.params()) {
      if (!given.containsKey(p.name()) && p.defaultValue().isPresent()) {
        given.put(p.name(), p.defaultValue().get());
      }
    }
    EventTypeDescriptor descriptor =
        new EventTypeDescriptor(
            rule.eventType(),
            command.params().stream()
                .map(p -> new FieldDescriptor(p.name(), p.type(), p.required(), false))
                .toList());
    return event(descriptor, "command", "command:" + rule.id(), Optional.empty(), given, clock);
  }

  private static Event event(
      EventTypeDescriptor descriptor,
      String source,
      String target,
      Optional<String> key,
      Map<String, Object> raw,
      Clock clock)
      throws Invalid {
    List<String> problems = new ArrayList<>();
    Map<String, Object> data = new HashMap<>();
    Set<String> known = new HashSet<>();
    for (FieldDescriptor field : descriptor.fields()) {
      known.add(field.name());
      Object value = raw.get(field.name());
      if (value == null) {
        if (field.required()) {
          problems.add("missing required field '" + field.name() + "'");
        }
        continue;
      }
      try {
        data.put(field.name(), coerce(field, value));
      } catch (IllegalArgumentException e) {
        problems.add("field '" + field.name() + "': " + e.getMessage());
      }
    }
    for (String name : raw.keySet()) {
      if (!known.contains(name)) {
        problems.add("unknown field '" + name + "' for event type '" + descriptor.type() + "'");
      }
    }
    if (!problems.isEmpty()) {
      throw new Invalid(problems);
    }
    return new Event(
        UUID.randomUUID().toString(),
        descriptor.type(),
        source,
        clock.instant(),
        target,
        key,
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
