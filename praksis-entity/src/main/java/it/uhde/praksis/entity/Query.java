// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import java.util.Optional;

/**
 * What to read from the entities of one type.
 *
 * @param filter the condition, or empty for all
 * @param sort the field to sort by (default {@code created}); ties are broken by {@code id}
 * @param reverse whether to sort from high to low
 * @param limit how many to return, 1 to {@link #MAX_LIMIT} (default {@link #DEFAULT_LIMIT})
 * @param offset how many to skip
 */
public record Query(Optional<Filter> filter, String sort, boolean reverse, int limit, int offset) {
  public static final int DEFAULT_LIMIT = 100;
  public static final int MAX_LIMIT = 1000;

  /** All entities, oldest first, the first {@link #DEFAULT_LIMIT}. */
  public static Query all() {
    return new Query(Optional.empty(), "created", false, DEFAULT_LIMIT, 0);
  }
}
