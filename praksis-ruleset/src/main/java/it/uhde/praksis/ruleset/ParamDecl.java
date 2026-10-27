// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.ValueType;
import java.util.Objects;
import java.util.Optional;

/**
 * A ruleset parameter, available to expressions as {@code params.<name>}.
 *
 * @param name the parameter name
 * @param type the value type
 * @param defaultValue the value used when no layer sets one; a parameter without a default must be
 *     set
 */
public record ParamDecl(String name, ValueType type, Optional<Object> defaultValue) {
  public ParamDecl {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(type, "type");
    Objects.requireNonNull(defaultValue, "defaultValue");
  }
}
