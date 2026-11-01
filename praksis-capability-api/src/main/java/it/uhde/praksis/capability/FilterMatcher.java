// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Evaluates a {@link Filter} and a sort on entities in memory, with the same answers as a database
 * would give. A backend that keeps its entities in files uses this to implement {@link
 * EntityStorage#query}.
 */
public final class FilterMatcher {
  private FilterMatcher() {}

  /** Whether the entity matches. */
  public static boolean matches(Filter filter, StoredEntity entity) {
    return switch (filter) {
      case Filter.Equals e -> {
        Object v = entity.value(e.field());
        yield v != null && compare(v, e.value()) == 0;
      }
      case Filter.OneOf o -> {
        Object v = entity.value(o.field());
        yield v != null && o.values().stream().anyMatch(x -> compare(v, x) == 0);
      }
      case Filter.Prefix p ->
          entity.value(p.field()) instanceof String s && s.startsWith(p.value());
      case Filter.Range r -> {
        Object v = entity.value(r.field());
        yield v != null
            && (r.gt() == null || compare(v, r.gt()) > 0)
            && (r.gte() == null || compare(v, r.gte()) >= 0)
            && (r.lt() == null || compare(v, r.lt()) < 0)
            && (r.lte() == null || compare(v, r.lte()) <= 0);
      }
      case Filter.And a -> a.of().stream().allMatch(f -> matches(f, entity));
      case Filter.Or o -> o.of().stream().anyMatch(f -> matches(f, entity));
      case Filter.Not n -> !matches(n.of(), entity);
    };
  }

  /** The order of a query: by the field (entities without a value first), then by id. */
  public static Comparator<StoredEntity> order(String sort, boolean reverse) {
    Comparator<StoredEntity> byField =
        (a, b) -> {
          Object x = a.value(sort);
          Object y = b.value(sort);
          if (x == null || y == null) {
            return x == y ? 0 : x == null ? -1 : 1;
          }
          return compare(x, y);
        };
    Comparator<StoredEntity> primary = reverse ? byField.reversed() : byField;
    return primary.thenComparing(StoredEntity::id);
  }

  /** The entities that match, in order, as a page. */
  public static EntityStorage.Page page(List<StoredEntity> all, Query query) {
    List<StoredEntity> hits =
        all.stream()
            .filter(e -> query.filter().map(f -> matches(f, e)).orElse(true))
            .sorted(order(query.sort(), query.reverse()))
            .toList();
    int from = Math.min(query.offset(), hits.size());
    int to = Math.min(from + query.limit(), hits.size());
    return new EntityStorage.Page(hits.subList(from, to), hits.size());
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private static int compare(Object a, Object b) {
    if (a instanceof Number x && b instanceof Number y) {
      boolean whole =
          !(x instanceof Double || x instanceof Float)
              && !(y instanceof Double || y instanceof Float);
      return whole
          ? Long.compare(x.longValue(), y.longValue())
          : Double.compare(x.doubleValue(), y.doubleValue());
    }
    if (a instanceof Instant x && b instanceof Instant y) {
      return x.compareTo(y);
    }
    return ((Comparable) a).compareTo(b);
  }
}
