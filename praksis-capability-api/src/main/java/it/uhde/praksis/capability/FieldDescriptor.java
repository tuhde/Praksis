// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.Objects;

/**
 * One typed value in a capability's parameters or results, or in an event's data.
 *
 * @param name the field name, as used in rules
 * @param type the value type
 * @param required whether the field must be present
 * @param scopable whether a grant may constrain this field (parameters only)
 */
public record FieldDescriptor(String name, ValueType type, boolean required, boolean scopable) {
  public FieldDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
  }
}
