// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.List;
import java.util.Objects;

/**
 * What a storage backend needs to know of an entity type to keep its entities: the id and the
 * fields. (Praksis checks types, required and unique values before it writes; a backend may use
 * them to build tables, headers or indexes.)
 *
 * @param id the type id, for example {@code contact}
 * @param fields the declared fields, in order
 */
public record StorageType(String id, List<Field> fields) {
  /**
   * A declared field.
   *
   * @param name the name
   * @param type the type of its values
   * @param unique whether no two entities of the type may have the same value
   * @param required whether every entity has a value
   */
  public record Field(String name, ValueType type, boolean unique, boolean required) {}

  public StorageType {
    Objects.requireNonNull(id, "id");
    fields = List.copyOf(fields);
  }

  /** The field with the name, or null. */
  public Field field(String name) {
    return fields.stream().filter(f -> f.name().equals(name)).findFirst().orElse(null);
  }
}
