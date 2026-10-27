// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** The types a parameter, result or event field can have. Each maps to one Java type. */
public enum ValueType {
  /** {@link String}. */
  STRING,
  /** {@link Long}. */
  INT,
  /** {@link Double}. */
  DOUBLE,
  /** {@link Boolean}. */
  BOOL,
  /** {@link java.time.Instant}. */
  TIMESTAMP,
  /** {@code byte[]}. */
  BYTES,
  /** {@link java.util.List} of values. */
  LIST,
  /** {@link java.util.Map} from {@link String} to values. */
  MAP;

  /** The name used in rulesets and descriptors, e.g. {@code timestamp}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT);
  }

  /** The type with the given {@link #id()}, or empty if there is none. */
  public static Optional<ValueType> fromId(String id) {
    return Arrays.stream(values()).filter(t -> t.id().equals(id)).findFirst();
  }
}
