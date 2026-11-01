// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates, reads, queries, updates and deletes entities in the SQLite database of the store. It
 * checks every write against the type (known fields, types, required and unique) and keeps {@code
 * id}, {@code created} and {@code updated}. An entity is the map of its fields; the three fields
 * that Praksis sets are always in it.
 *
 * <p>Each call is one transaction. With {@code dryRun} the call is checked in full and rolled back.
 * Permissions are not checked here; they are those of the capabilities that call this class.
 */
public final class EntityService {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final String TOPIC = "entities";

  private final Store store;
  private final EntityCatalog catalog;
  private final Clock clock;

  public EntityService(Store store, EntityCatalog catalog, Clock clock) {
    this.store = store;
    this.catalog = catalog;
    this.clock = clock;
  }

  public EntityCatalog catalog() {
    return catalog;
  }

  /** Thrown inside a transaction to roll a dry run back and carry its result out. */
  private static final class DryRunDone extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient Object result;

    DryRunDone(Object result) {
      super(null, null, false, false);
      this.result = result;
    }
  }

  /** Carries an {@link EntityException} out of a transaction, which rolls back on its way. */
  private static final class Failed extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient EntityException cause;

    Failed(EntityException cause) {
      super(cause.getMessage(), cause, false, false);
      this.cause = cause;
    }
  }

  private interface Work<T> {
    T run(Connection connection) throws SQLException, EntityException;
  }

  private <T> T read(Work<T> work) throws EntityException {
    return run(false, null, work);
  }

  private <T> T run(boolean dryRun, String topic, Work<T> work) throws EntityException {
    try {
      return store.transaction(
          dryRun ? null : topic,
          c -> {
            T result;
            try {
              result = work.run(c);
            } catch (EntityException e) {
              throw new Failed(e);
            }
            if (dryRun) {
              throw new DryRunDone(result);
            }
            return result;
          });
    } catch (DryRunDone done) {
      @SuppressWarnings("unchecked")
      T result = (T) done.result;
      return result;
    } catch (Failed failed) {
      throw failed.cause;
    } catch (StoreException e) {
      throw new EntityException("the database failed: " + e.getMessage(), e);
    }
  }

  private EntityType type(String id) throws EntityException {
    return catalog
        .get(id)
        .orElseThrow(() -> new EntityException("unknown entity type '" + id + "'"));
  }

  /**
   * Creates an entity. If {@code idempotencyKey} is given the id is made from the type and the key,
   * so that a retry of the same action finds the entity it already made and returns it.
   */
  public Map<String, Object> create(
      String typeId, Map<String, Object> values, String idempotencyKey, boolean dryRun)
      throws EntityException {
    EntityType type = type(typeId);
    Map<String, Object> fields = checked(type, values, true);
    String id =
        idempotencyKey == null || idempotencyKey.isEmpty()
            ? UUID.randomUUID().toString()
            : UUID.nameUUIDFromBytes(
                    (typeId + "\0" + idempotencyKey).getBytes(StandardCharsets.UTF_8))
                .toString();
    Instant now = Instant.parse(EntityValues.time(clock.instant()));
    return run(
        dryRun,
        TOPIC,
        c -> {
          Optional<Map<String, Object>> existing = get(c, type, id);
          if (existing.isPresent() && idempotencyKey != null && !idempotencyKey.isEmpty()) {
            return existing.get();
          }
          write(c, type, id, now, now, fields, true);
          return entity(type, id, now, now, fields);
        });
  }

  /** The entity, or empty. */
  public Optional<Map<String, Object>> read(String typeId, String id) throws EntityException {
    EntityType type = type(typeId);
    return read(c -> get(c, type, id));
  }

  /** The matching entities in order, and how many match in all (before limit and offset). */
  public record Page(List<Map<String, Object>> items, long total) {}

  public Page query(String typeId, Query query) throws EntityException {
    EntityType type = type(typeId);
    if (query.limit() < 1 || query.limit() > Query.MAX_LIMIT) {
      throw new EntityException("limit must be from 1 to " + Query.MAX_LIMIT);
    }
    if (query.offset() < 0) {
      throw new EntityException("offset must not be negative");
    }
    if (Filter.typeOf(type, query.sort()).isEmpty()) {
      throw new EntityException("cannot sort by '" + query.sort() + "' in type '" + typeId + "'");
    }
    List<Object> params = new ArrayList<>();
    String where = query.filter().map(f -> sql(f, params)).orElse("1");
    String order = expr(query.sort()) + (query.reverse() ? " DESC" : " ASC") + ", id ASC";
    return read(
        c -> {
          long total;
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT count(*) FROM entity WHERE type = ? AND (" + where + ")")) {
            bind(s, typeId, params);
            try (ResultSet r = s.executeQuery()) {
              r.next();
              total = r.getLong(1);
            }
          }
          List<Map<String, Object>> items = new ArrayList<>();
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT id, created, updated, json FROM entity WHERE type = ? AND ("
                      + where
                      + ") ORDER BY "
                      + order
                      + " LIMIT ? OFFSET ?")) {
            List<Object> all = new ArrayList<>(params);
            all.add((long) query.limit());
            all.add((long) query.offset());
            bind(s, typeId, all);
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) {
                items.add(row(type, r));
              }
            }
          }
          return new Page(items, total);
        });
  }

  /**
   * Changes fields of an entity: a field in {@code values} gets the new value, or is removed if the
   * value is null (not allowed for a required field). Returns the entity, or empty if there is
   * none.
   */
  public Optional<Map<String, Object>> update(
      String typeId, String id, Map<String, Object> values, boolean dryRun) throws EntityException {
    EntityType type = type(typeId);
    Map<String, Object> changes = checked(type, values, false);
    Instant now = Instant.parse(EntityValues.time(clock.instant()));
    return run(
        dryRun,
        TOPIC,
        c -> {
          Optional<Map<String, Object>> old = get(c, type, id);
          if (old.isEmpty()) {
            return Optional.<Map<String, Object>>empty();
          }
          Map<String, Object> fields = new LinkedHashMap<>(old.get());
          fields.keySet().removeAll(EntityType.RESERVED);
          for (var e : changes.entrySet()) {
            if (e.getValue() == null) {
              if (type.field(e.getKey()).orElseThrow().required()) {
                throw new EntityException("field '" + e.getKey() + "' is required");
              }
              fields.remove(e.getKey());
            } else {
              fields.put(e.getKey(), e.getValue());
            }
          }
          Instant created = (Instant) old.get().get("created");
          write(c, type, id, created, now, fields, false);
          return Optional.of(entity(type, id, created, now, fields));
        });
  }

  /** Deletes an entity; false if there was none. */
  public boolean delete(String typeId, String id, boolean dryRun) throws EntityException {
    EntityType type = type(typeId);
    return run(
        dryRun,
        TOPIC,
        c -> {
          int n;
          try (PreparedStatement s =
              c.prepareStatement("DELETE FROM entity WHERE type = ? AND id = ?")) {
            s.setString(1, type.id());
            s.setString(2, id);
            n = s.executeUpdate();
          }
          unique(c, type, id, Map.of(), false);
          return n > 0;
        });
  }

  // --- checks and conversion ---

  /** The values converted to Java types; with {@code create} the required fields must be there. */
  private static Map<String, Object> checked(
      EntityType type, Map<String, Object> values, boolean create) throws EntityException {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : values.entrySet()) {
      EntityType.Field f =
          type.field(e.getKey())
              .orElseThrow(
                  () ->
                      new EntityException(
                          EntityType.RESERVED.contains(e.getKey())
                              ? "field '" + e.getKey() + "' is set by Praksis"
                              : "type '" + type.id() + "' has no field '" + e.getKey() + "'"));
      if (e.getValue() == null) {
        if (create) {
          continue;
        }
        out.put(f.name(), null);
      } else {
        out.put(f.name(), EntityValues.normalize(f.name(), f.type(), e.getValue()));
      }
    }
    if (create) {
      for (EntityType.Field f : type.fields()) {
        if (f.required() && !out.containsKey(f.name())) {
          throw new EntityException("field '" + f.name() + "' is required");
        }
      }
    }
    return out;
  }

  private static Map<String, Object> entity(
      EntityType type, String id, Instant created, Instant updated, Map<String, Object> fields) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("created", created);
    m.put("updated", updated);
    for (EntityType.Field f : type.fields()) {
      if (fields.get(f.name()) != null) {
        m.put(f.name(), fields.get(f.name()));
      }
    }
    return m;
  }

  // --- SQL ---

  private static Optional<Map<String, Object>> get(Connection c, EntityType type, String id)
      throws SQLException, EntityException {
    try (PreparedStatement s =
        c.prepareStatement(
            "SELECT id, created, updated, json FROM entity WHERE type = ? AND id = ?")) {
      s.setString(1, type.id());
      s.setString(2, id);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(row(type, r)) : Optional.empty();
      }
    }
  }

  private static Map<String, Object> row(EntityType type, ResultSet r)
      throws SQLException, EntityException {
    Map<String, Object> stored;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = JSON.readValue(r.getString(4), Map.class);
      stored = read;
    } catch (JsonProcessingException e) {
      throw new EntityException("stored entity " + r.getString(1) + " is damaged", e);
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (EntityType.Field f : type.fields()) {
      Object v = stored.get(f.name());
      if (v != null) {
        fields.put(f.name(), EntityValues.fromStored(f.type(), v));
      }
    }
    return entity(
        type, r.getString(1), Instant.parse(r.getString(2)), Instant.parse(r.getString(3)), fields);
  }

  private static void write(
      Connection c,
      EntityType type,
      String id,
      Instant created,
      Instant updated,
      Map<String, Object> fields,
      boolean insert)
      throws SQLException, EntityException {
    Map<String, Object> stored = new LinkedHashMap<>();
    for (EntityType.Field f : type.fields()) {
      Object v = fields.get(f.name());
      if (v != null) {
        stored.put(f.name(), EntityValues.toStored(f.type(), v));
      }
    }
    String json;
    try {
      json = JSON.writeValueAsString(stored);
    } catch (JsonProcessingException e) {
      throw new EntityException("cannot store the entity: " + e.getOriginalMessage(), e);
    }
    unique(c, type, id, stored, true);
    String sql =
        insert
            ? "INSERT INTO entity (created, updated, json, type, id) VALUES (?, ?, ?, ?, ?)"
            : "UPDATE entity SET created = ?, updated = ?, json = ? WHERE type = ? AND id = ?";
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, EntityValues.time(created));
      s.setString(2, EntityValues.time(updated));
      s.setString(3, json);
      s.setString(4, type.id());
      s.setString(5, id);
      s.executeUpdate();
    }
  }

  /** Replaces the unique values of the entity, refusing a value that another entity has. */
  private static void unique(
      Connection c, EntityType type, String id, Map<String, Object> stored, boolean check)
      throws SQLException, EntityException {
    try (PreparedStatement s =
        c.prepareStatement("DELETE FROM entity_unique WHERE type = ? AND id = ?")) {
      s.setString(1, type.id());
      s.setString(2, id);
      s.executeUpdate();
    }
    for (EntityType.Field f : type.fields()) {
      Object v = stored.get(f.name());
      if (!f.unique() || v == null) {
        continue;
      }
      try (PreparedStatement s =
          c.prepareStatement(
              "INSERT INTO entity_unique (type, field, value, id) VALUES (?, ?, ?, ?) "
                  + "ON CONFLICT DO NOTHING")) {
        s.setString(1, type.id());
        s.setString(2, f.name());
        s.setString(3, String.valueOf(v));
        s.setString(4, id);
        if (s.executeUpdate() == 0 && check) {
          throw new EntityException(
              "conflict: another '" + type.id() + "' already has " + f.name() + " = " + v);
        }
      }
    }
  }

  private static String expr(String field) {
    return switch (field) {
      case "id", "created", "updated" -> field;
      default -> "json_extract(json, '$." + field + "')";
    };
  }

  private static Object param(Object v) {
    return switch (v) {
      case Instant t -> EntityValues.time(t);
      case Boolean b -> b ? 1L : 0L;
      default -> v;
    };
  }

  private static String sql(Filter f, List<Object> params) {
    switch (f) {
      case Filter.Equals e -> {
        params.add(param(e.value()));
        return expr(e.field()) + " = ?";
      }
      case Filter.OneOf o -> {
        o.values().forEach(v -> params.add(param(v)));
        return expr(o.field())
            + " IN ("
            + String.join(", ", java.util.Collections.nCopies(o.values().size(), "?"))
            + ")";
      }
      case Filter.Prefix p -> {
        params.add((long) p.value().length());
        params.add(p.value());
        return "substr(" + expr(p.field()) + ", 1, ?) = ?";
      }
      case Filter.Range r -> {
        List<String> parts = new ArrayList<>();
        bound(parts, params, r.field(), ">", r.gt());
        bound(parts, params, r.field(), ">=", r.gte());
        bound(parts, params, r.field(), "<", r.lt());
        bound(parts, params, r.field(), "<=", r.lte());
        return String.join(" AND ", parts);
      }
      case Filter.And a -> {
        return "(" + String.join(" AND ", a.of().stream().map(x -> sql(x, params)).toList()) + ")";
      }
      case Filter.Or o -> {
        return "(" + String.join(" OR ", o.of().stream().map(x -> sql(x, params)).toList()) + ")";
      }
      case Filter.Not n -> {
        return "NOT COALESCE(" + sql(n.of(), params) + ", 0)";
      }
    }
  }

  private static void bound(
      List<String> parts, List<Object> params, String field, String op, Object v) {
    if (v != null) {
      params.add(param(v));
      parts.add(expr(field) + " " + op + " ?");
    }
  }

  private static void bind(PreparedStatement s, String type, List<Object> params)
      throws SQLException {
    s.setString(1, type);
    int i = 2;
    for (Object p : params) {
      switch (p) {
        case Long l -> s.setLong(i++, l);
        case Double d -> s.setDouble(i++, d);
        default -> s.setString(i++, String.valueOf(p));
      }
    }
  }
}
