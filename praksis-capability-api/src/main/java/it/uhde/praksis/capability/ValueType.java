// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

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
  MAP
}
