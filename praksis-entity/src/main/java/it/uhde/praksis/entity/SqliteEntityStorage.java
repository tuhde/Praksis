// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The storage that every daemon has: the tables {@code entity} and {@code entity_unique} of its
 * SQLite database. The fields of an entity are one JSON text, queried with {@code json_extract};
 * the unique values are in their own table, so that a write finds a clash from an index. Its {@link
 * #atomically} is a transaction of the store, which the service uses to write an entity and its
 * event together.
 */
public final class SqliteEntityStorage implements EntityStorage {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final ThreadLocal<Connection> OPEN = new ThreadLocal<>();

  private final Store store;
  private final Map<String, StorageType> types = new HashMap<>();

  public SqliteEntityStorage(Store store) {
    this.store = store;
  }

  /** The storage name of the one in the daemon's database. */
  public static final String NAME = "sqlite";

  @Override
  public String name() {
    return NAME;
  }

  @Override
  public void open(List<StorageType> list) {
    list.forEach(t -> types.put(t.id(), t));
  }

  /** Carries a storage failure out of a transaction, which rolls back on its way. */
  private static final class Failed extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final transient StorageException cause;

    Failed(StorageException cause) {
      super(cause.getMessage(), cause, false, false);
      this.cause = cause;
    }
  }

  private interface Op<T> {
    T run(Connection c) throws SQLException, StorageException;
  }

  private <T> T tx(String topic, Op<T> op) throws StorageException {
    Connection open = OPEN.get();
    try {
      if (open != null) {
        return op.run(open);
      }
      return store.transaction(
          topic,
          c -> {
            try {
              return op.run(c);
            } catch (StorageException e) {
              throw new Failed(e);
            }
          });
    } catch (Failed f) {
      throw f.cause;
    } catch (SQLException | StoreException e) {
      throw new StorageException("the database failed: " + e.getMessage(), e);
    }
  }

  @Override
  public <T> T atomically(Work<T> work) throws StorageException {
    return tx(
        "entities",
        c -> {
          boolean outer = OPEN.get() == null;
          OPEN.set(c);
          try {
            return work.run();
          } finally {
            if (outer) {
              OPEN.remove();
            }
          }
        });
  }

  private StorageType type(String id) throws StorageException {
    StorageType t = types.get(id);
    if (t == null) {
      throw new StorageException("the storage 'sqlite' was not opened for type '" + id + "'");
    }
    return t;
  }

  @Override
  public Optional<StoredEntity> get(String typeId, String id) throws StorageException {
    StorageType type = type(typeId);
    return tx(
        null,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT id, created, updated, created_by, updated_by, json FROM entity"
                      + " WHERE type = ? AND id = ?")) {
            s.setString(1, typeId);
            s.setString(2, id);
            try (ResultSet r = s.executeQuery()) {
              return r.next() ? Optional.of(row(type, r)) : Optional.empty();
            }
          }
        });
  }

  @Override
  public void insert(String typeId, StoredEntity entity) throws StorageException {
    write(typeId, entity, true);
  }

  @Override
  public void replace(String typeId, StoredEntity entity) throws StorageException {
    write(typeId, entity, false);
  }

  private void write(String typeId, StoredEntity e, boolean insert) throws StorageException {
    StorageType type = type(typeId);
    Map<String, Object> stored = new LinkedHashMap<>();
    for (StorageType.Field f : type.fields()) {
      Object v = e.fields().get(f.name());
      if (v != null) {
        stored.put(f.name(), EntityValues.toStored(f.type(), v));
      }
    }
    String json;
    try {
      json = JSON.writeValueAsString(stored);
    } catch (JsonProcessingException ex) {
      throw new StorageException("cannot store the entity: " + ex.getOriginalMessage(), ex);
    }
    tx(
        null,
        c -> {
          unique(c, type, e.id(), stored);
          String sql =
              insert
                  ? "INSERT INTO entity (created, updated, created_by, updated_by, json, type, id)"
                      + " VALUES (?, ?, ?, ?, ?, ?, ?)"
                  : "UPDATE entity SET created = ?, updated = ?, created_by = ?, updated_by = ?,"
                      + " json = ? WHERE type = ? AND id = ?";
          try (PreparedStatement s = c.prepareStatement(sql)) {
            s.setString(1, EntityValues.time(e.created()));
            s.setString(2, EntityValues.time(e.updated()));
            s.setString(3, e.createdBy());
            s.setString(4, e.updatedBy());
            s.setString(5, json);
            s.setString(6, typeId);
            s.setString(7, e.id());
            s.executeUpdate();
          }
          return null;
        });
  }

  /** Replaces the unique values of the entity, refusing a value that another entity has. */
  private static void unique(Connection c, StorageType type, String id, Map<String, Object> stored)
      throws SQLException, StorageException {
    deleteUnique(c, type.id(), id);
    for (StorageType.Field f : type.fields()) {
      Object v = stored.get(f.name());
      if (!f.unique() || v == null) {
        continue;
      }
      try (PreparedStatement s =
          c.prepareStatement(
              "INSERT INTO entity_unique (type, field, value, id) VALUES (?, ?, ?, ?)"
                  + " ON CONFLICT DO NOTHING")) {
        s.setString(1, type.id());
        s.setString(2, f.name());
        s.setString(3, String.valueOf(v));
        s.setString(4, id);
        if (s.executeUpdate() == 0) {
          throw new StorageException("unique value in use: " + type.id() + "." + f.name());
        }
      }
    }
  }

  private static void deleteUnique(Connection c, String type, String id) throws SQLException {
    try (PreparedStatement s =
        c.prepareStatement("DELETE FROM entity_unique WHERE type = ? AND id = ?")) {
      s.setString(1, type);
      s.setString(2, id);
      s.executeUpdate();
    }
  }

  @Override
  public boolean delete(String typeId, String id) throws StorageException {
    return tx(
        null,
        c -> {
          int n;
          try (PreparedStatement s =
              c.prepareStatement("DELETE FROM entity WHERE type = ? AND id = ?")) {
            s.setString(1, typeId);
            s.setString(2, id);
            n = s.executeUpdate();
          }
          deleteUnique(c, typeId, id);
          return n > 0;
        });
  }

  @Override
  public Optional<String> findUnique(String typeId, String field, Object value)
      throws StorageException {
    StorageType type = type(typeId);
    StorageType.Field f = type.field(field);
    if (f == null) {
      return Optional.empty();
    }
    String text = String.valueOf(EntityValues.toStored(f.type(), value));
    return tx(
        null,
        c -> {
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT id FROM entity_unique WHERE type = ? AND field = ? AND value = ?")) {
            s.setString(1, typeId);
            s.setString(2, field);
            s.setString(3, text);
            try (ResultSet r = s.executeQuery()) {
              return r.next() ? Optional.of(r.getString(1)) : Optional.<String>empty();
            }
          }
        });
  }

  @Override
  public Page query(String typeId, Query query) throws StorageException {
    StorageType type = type(typeId);
    List<Object> params = new ArrayList<>();
    String where = query.filter().map(f -> sql(f, params)).orElse("1");
    String order = expr(query.sort()) + (query.reverse() ? " DESC" : " ASC") + ", id ASC";
    return tx(
        null,
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
          List<StoredEntity> items = new ArrayList<>();
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT id, created, updated, created_by, updated_by, json FROM entity"
                      + " WHERE type = ? AND ("
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
   * An entity as it is stored, without reading its values as the type says: the values are those of
   * the JSON in the database ({@code String}, {@code Number}, {@code Boolean}, {@code List}, {@code
   * Map}), also for fields that the type does not have (any more).
   */
  public record Raw(
      String id,
      Instant created,
      Instant updated,
      String createdBy,
      String updatedBy,
      Map<String, Object> json) {}

  /** All entities of the type as they are stored, in order of creation. */
  public List<Raw> raw(String typeId) throws StorageException {
    return tx(
        null,
        c -> {
          List<Raw> out = new ArrayList<>();
          try (PreparedStatement s =
              c.prepareStatement(
                  "SELECT id, created, updated, created_by, updated_by, json FROM entity"
                      + " WHERE type = ? ORDER BY created, id")) {
            s.setString(1, typeId);
            try (ResultSet r = s.executeQuery()) {
              while (r.next()) {
                Map<String, Object> json;
                try {
                  json =
                      JSON.readValue(
                          r.getString(6),
                          new com.fasterxml.jackson.core.type.TypeReference<
                              Map<String, Object>>() {});
                } catch (JsonProcessingException e) {
                  throw new StorageException("stored entity " + r.getString(1) + " is damaged", e);
                }
                out.add(
                    new Raw(
                        r.getString(1),
                        Instant.parse(r.getString(2)),
                        Instant.parse(r.getString(3)),
                        r.getString(4),
                        r.getString(5),
                        json));
              }
            }
          }
          return out;
        });
  }

  private static StoredEntity row(StorageType type, ResultSet r)
      throws SQLException, StorageException {
    Map<String, Object> stored;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = JSON.readValue(r.getString(6), Map.class);
      stored = read;
    } catch (JsonProcessingException e) {
      throw new StorageException("stored entity " + r.getString(1) + " is damaged", e);
    }
    Map<String, Object> fields = new LinkedHashMap<>();
    for (StorageType.Field f : type.fields()) {
      Object v = stored.get(f.name());
      if (v != null) {
        fields.put(f.name(), EntityValues.fromStored(f.type(), v));
      }
    }
    return new StoredEntity(
        r.getString(1),
        Instant.parse(r.getString(2)),
        Instant.parse(r.getString(3)),
        r.getString(4),
        r.getString(5),
        Collections.unmodifiableMap(fields));
  }

  private static String expr(String field) {
    return switch (field) {
      case "id", "created", "updated", "created_by", "updated_by" -> field;
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
            + String.join(", ", Collections.nCopies(o.values().size(), "?"))
            + ")";
      }
      case Filter.Prefix p -> {
        params.add((long) p.value().codePointCount(0, p.value().length()));
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
