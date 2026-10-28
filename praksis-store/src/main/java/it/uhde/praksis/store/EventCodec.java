// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import it.uhde.praksis.capability.Event;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Stores events as JSON. Timestamps and bytes have no JSON form, so they are written as {@code
 * {"$timestamp": "..."}} and {@code {"$bytes": "<base64>"}} and come back with their types.
 */
final class EventCodec {
  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

  private EventCodec() {}

  /** A map of plain values as JSON, e.g. the arguments of a capability call. */
  static String encodeMap(Map<String, Object> values) {
    try {
      return MAPPER.writeValueAsString(value(values));
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static String encode(Event e) {
    ObjectNode n = JSON.objectNode();
    n.put("id", e.id());
    n.put("type", e.type());
    n.put("source", e.source());
    n.put("time", e.time().toString());
    n.put("target", e.target());
    e.key().ifPresent(k -> n.put("key", k));
    e.parent().ifPresent(p -> n.put("parent", p));
    n.put("depth", e.depth());
    n.set("data", value(e.data()));
    try {
      return MAPPER.writeValueAsString(n);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException(ex);
    }
  }

  static Event decode(String json) {
    try {
      JsonNode n = MAPPER.readTree(json);
      @SuppressWarnings("unchecked")
      Map<String, Object> data = (Map<String, Object>) plain(n.get("data"));
      return new Event(
          n.get("id").asText(),
          n.get("type").asText(),
          n.get("source").asText(),
          Instant.parse(n.get("time").asText()),
          n.get("target").asText(),
          optional(n, "key"),
          optional(n, "parent"),
          n.get("depth").asInt(),
          data);
    } catch (JsonProcessingException ex) {
      throw new IllegalStateException("stored event is not valid JSON", ex);
    }
  }

  private static Optional<String> optional(JsonNode n, String field) {
    return n.has(field) ? Optional.of(n.get(field).asText()) : Optional.empty();
  }

  private static JsonNode value(Object o) {
    return switch (o) {
      case null -> JSON.nullNode();
      case String s -> JSON.textNode(s);
      case Boolean b -> JSON.booleanNode(b);
      case Long l -> JSON.numberNode(l);
      case Integer i -> JSON.numberNode(i.longValue());
      case Double d -> JSON.numberNode(d);
      case Instant t -> JSON.objectNode().put("$timestamp", t.toString());
      case byte[] b -> JSON.objectNode().put("$bytes", Base64.getEncoder().encodeToString(b));
      case Map<?, ?> m -> {
        ObjectNode out = JSON.objectNode();
        m.forEach((k, v) -> out.set(String.valueOf(k), value(v)));
        yield out;
      }
      case List<?> l -> {
        ArrayNode out = JSON.arrayNode();
        l.forEach(v -> out.add(value(v)));
        yield out;
      }
      default ->
          throw new IllegalArgumentException(
              "cannot store a " + o.getClass().getName() + " in an event");
    };
  }

  private static Object plain(JsonNode n) {
    if (n == null || n.isNull()) {
      return null;
    }
    if (n.isTextual()) {
      return n.asText();
    }
    if (n.isBoolean()) {
      return n.asBoolean();
    }
    if (n.isIntegralNumber()) {
      return n.asLong();
    }
    if (n.isNumber()) {
      return n.asDouble();
    }
    if (n.isArray()) {
      List<Object> out = new ArrayList<>();
      n.forEach(item -> out.add(plain(item)));
      return out;
    }
    if (n.size() == 1 && n.has("$timestamp")) {
      return Instant.parse(n.get("$timestamp").asText());
    }
    if (n.size() == 1 && n.has("$bytes")) {
      return Base64.getDecoder().decode(n.get("$bytes").asText());
    }
    Map<String, Object> out = new LinkedHashMap<>();
    n.properties().forEach(e -> out.put(e.getKey(), plain(e.getValue())));
    return out;
  }
}
