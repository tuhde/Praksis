// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The envelope of everything that happens: schedule ticks, user commands and events from modules.
 *
 * <p>The values in {@code data} use the Java types listed at {@link ValueType}.
 *
 * @param id unique id of this event
 * @param type event type, e.g. {@code mail.received}
 * @param source who produced the event, e.g. a module name
 * @param time when it happened
 * @param target what it is about; events with the same target are handled one at a time
 * @param key idempotency key; an event whose key was seen before is not handled again, or empty
 * @param parent id of the event whose handling emitted this one, or empty
 * @param depth number of emitting steps from the original event; 0 for original events
 * @param data the payload
 */
public record Event(
    String id,
    String type,
    String source,
    Instant time,
    String target,
    Optional<String> key,
    Optional<String> parent,
    int depth,
    Map<String, Object> data) {
  public Event {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(time, "time");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(parent, "parent");
    if (depth < 0) {
      throw new IllegalArgumentException("depth must not be negative: " + depth);
    }
    data = Map.copyOf(data);
  }
}
