// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * One entity as a backend keeps it. The values of {@code fields} have the Java types of {@link
 * ValueType} ({@link String}, {@link Long}, {@link Double}, {@link Boolean}, {@link Instant} with
 * millisecond precision, {@code byte[]}, {@link java.util.List}, {@link Map}); a field without a
 * value is not in the map. Inside a list or a map the values are JSON values (text, numbers, true
 * and false, lists, maps, nothing), and a number there may come back as an {@link Integer}, a
 * {@link Long} or a {@link Double}, whichever the storage's format gives.
 *
 * @param id the id, unique within the type
 * @param created when it was made
 * @param updated when it was last changed
 * @param createdBy who made it ({@code rule:<id>} or {@code user})
 * @param updatedBy who changed it last
 * @param fields the values of the declared fields
 */
public record StoredEntity(
    String id,
    Instant created,
    Instant updated,
    String createdBy,
    String updatedBy,
    Map<String, Object> fields) {
  public StoredEntity {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(created, "created");
    Objects.requireNonNull(updated, "updated");
    Objects.requireNonNull(createdBy, "createdBy");
    Objects.requireNonNull(updatedBy, "updatedBy");
    fields = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(fields));
  }

  /**
   * The value of a field, or of {@code id}, {@code created}, {@code updated}, {@code created_by} or
   * {@code updated_by}; null if there is none.
   */
  public Object value(String name) {
    return switch (name) {
      case "id" -> id;
      case "created" -> created;
      case "updated" -> updated;
      case "created_by" -> createdBy;
      case "updated_by" -> updatedBy;
      default -> fields.get(name);
    };
  }
}
