// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.capability.Event;
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
import java.util.function.Consumer;

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
  private static final ObjectMapper CANONICAL =
      new ObjectMapper()
          .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

  /** The longest chain of events that entity changes may cause; a write beyond it is refused. */
  public static final int MAX_EVENT_DEPTH = 8;

  /**
   * The circumstances of a write.
   *
   * @param dryRun check everything, change nothing, emit nothing
   * @param cause the event whose handling makes this call, or empty (a command)
   * @param idempotencyKey the same for every retry of the same action, or null
   * @param by who makes the change, kept as {@code created_by} and {@code updated_by}: {@code
   *     rule:<id>} or {@link #USER}
   */
  public record Call(boolean dryRun, Optional<Event> cause, String idempotencyKey, String by) {
    /** A call by a person, outside any rule. */
    public Call(boolean dryRun, Optional<Event> cause, String idempotencyKey) {
      this(dryRun, cause, idempotencyKey, USER);
    }

    public static Call of(boolean dryRun) {
      return new Call(dryRun, Optional.empty(), null, USER);
    }
  }

  /** Who made a change that no rule made. */
  public static final String USER = "user";

  private static final String TOPIC = "entities";

  private final Store store;
  private final EntityCatalog catalog;
  private final Clock clock;
  private final Consumer<Event> queued;

  /**
   * Creates the service. Every change stores an event ({@code entity.created}, {@code
   * entity.updated} or {@code entity.deleted}) in the same transaction, and after the commit hands
   * it to {@code queued}, which has the engine handle it.
   */
  public EntityService(Store store, EntityCatalog catalog, Clock clock, Consumer<Event> queued) {
    this.store = store;
    this.catalog = catalog;
    this.clock = clock;
    this.queued = queued;
  }

  /** A service whose events are stored but not handed on. */
  public EntityService(Store store, EntityCatalog catalog, Clock clock) {
    this(store, catalog, clock, e -> {});
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
    T run(Connection connection) throws SQLException, EntityException, StoreException;
  }

  private <T> T read(Work<T> work) throws EntityException {
    return run(false, null, List.of(), work);
  }

  /**
   * Runs {@code work} as a transaction. The events that it put in {@code events} were stored in the
   * same transaction; after the commit (and not for a dry run) they are handed to the engine.
   */
  private <T> T run(boolean dryRun, String topic, List<Event> events, Work<T> work)
      throws EntityException {
    try {
      T result =
          store.transaction(
              dryRun ? null : topic,
              c -> {
                T r;
                try {
                  r = work.run(c);
                } catch (EntityException e) {
                  throw new Failed(e);
                }
                if (dryRun) {
                  throw new DryRunDone(r);
                }
                return r;
              });
      events.forEach(queued);
      return result;
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

  private static void checkDepth(Call call) throws EntityException {
    int depth = call.cause().map(e -> e.depth() + 1).orElse(0);
    if (depth > MAX_EVENT_DEPTH) {
      throw new EntityException(
          "event depth limit "
              + MAX_EVENT_DEPTH
              + " reached: rules change entities and react to the change in a loop");
    }
  }

  /**
   * The names in the order the type declares its fields, so that an event is the same each time.
   */
  private static List<String> inTypeOrder(EntityType type, java.util.Collection<String> names) {
    return type.fields().stream().map(EntityType.Field::name).filter(names::contains).toList();
  }

  /** The event for a change; it carries no values, so reading them needs a grant to read. */
  private Event event(
      Call call, String kind, EntityType type, String id, List<String> fields, String keyPart) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("type", type.id());
    data.put("id", id);
    if (fields != null) {
      data.put("fields", fields);
    }
    Instant now = clock.instant();
    return new Event(
        UUID.randomUUID().toString(),
        "entity." + kind,
        EntityCapabilities.MODULE,
        now,
        "entity:" + type.id() + ":" + id,
        Optional.of("entity." + kind + ":" + type.id() + ":" + id + keyPart),
        call.cause().map(Event::id),
        call.cause().map(e -> e.depth() + 1).orElse(0),
        data);
  }

  private EntityType type(String id) throws EntityException {
    return catalog
        .get(id)
        .orElseThrow(() -> new EntityException("unknown entity type '" + id + "'"));
  }

  /** Creates an entity; see {@link #create(String, Map, Call)}. */
  public Map<String, Object> create(
      String typeId, Map<String, Object> values, String idempotencyKey, boolean dryRun)
      throws EntityException {
    return create(typeId, values, new Call(dryRun, Optional.empty(), idempotencyKey));
  }

  /**
   * Creates an entity and the event {@code entity.created}. If the call has an idempotency key the
   * id is made from the type and the key, so that a retry of the same action finds the entity it
   * already made, returns it and emits nothing again.
   */
  public Map<String, Object> create(String typeId, Map<String, Object> values, Call call)
      throws EntityException {
    EntityType type = type(typeId);
    checkDepth(call);
    Map<String, Object> fields = checked(type, values, true);
    String key = call.idempotencyKey();
    String id =
        key == null || key.isEmpty()
            ? UUID.randomUUID().toString()
            : UUID.nameUUIDFromBytes((typeId + "\0" + key).getBytes(StandardCharsets.UTF_8))
                .toString();
    Instant now = Instant.parse(EntityValues.time(clock.instant()));
    List<Event> events = new ArrayList<>();
    return run(
        call.dryRun(),
        TOPIC,
        events,
        c -> {
          Optional<Map<String, Object>> existing = get(c, type, id);
          if (existing.isPresent() && key != null && !key.isEmpty()) {
            return existing.get();
          }
          write(c, type, id, now, now, call.by(), call.by(), fields, true);
          if (!call.dryRun()) {
            Event e = event(call, "created", type, id, inTypeOrder(type, fields.keySet()), "");
            store.append(e);
            events.add(e);
          }
          return entity(type, id, now, now, call.by(), call.by(), fields);
        });
  }

  /** The entity without the values of its sensitive fields, which a rule must reveal. */
  public Map<String, Object> withoutSensitive(String typeId, Map<String, Object> entity)
      throws EntityException {
    EntityType type = type(typeId);
    Map<String, Object> out = new LinkedHashMap<>(entity);
    type.fields().stream().filter(EntityType.Field::sensitive).forEach(f -> out.remove(f.name()));
    return out;
  }

  /**
   * The values of some sensitive fields of an entity, or empty if there is no such entity. A field
   * that is not sensitive is refused (it is in what {@link #read} returns), and so is a list
   * without fields.
   */
  public Optional<Map<String, Object>> reveal(String typeId, String id, List<String> names)
      throws EntityException {
    EntityType type = type(typeId);
    if (names.isEmpty()) {
      throw new EntityException("fields: name at least one sensitive field");
    }
    for (String name : names) {
      EntityType.Field f = type.field(name).orElse(null);
      if (f == null || !f.sensitive()) {
        throw new EntityException(
            "field '" + name + "' of type '" + typeId + "' is not a sensitive field");
      }
    }
    return read(
        c ->
            get(c, type, id)
                .map(
                    e -> {
                      Map<String, Object> out = new LinkedHashMap<>();
                      names.stream()
                          .filter(e::containsKey)
                          .forEach(name -> out.put(name, e.get(name)));
                      return out;
                    }));
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
                  "SELECT id, created, updated, created_by, updated_by, json FROM entity WHERE type = ? AND ("
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

  /** Changes an entity; see {@link #update(String, String, Map, Call)}. */
  public Optional<Map<String, Object>> update(
      String typeId, String id, Map<String, Object> values, boolean dryRun) throws EntityException {
    return update(typeId, id, values, Call.of(dryRun));
  }

  /**
   * Changes fields of an entity: a field in {@code values} gets the new value, or is removed if the
   * value is null (not allowed for a required field). Returns the entity, or empty if there is
   * none. If no value differs from what is stored nothing is written and no event is emitted, so a
   * rule that sets a value on every {@code entity.updated} does not trigger itself.
   */
  public Optional<Map<String, Object>> update(
      String typeId, String id, Map<String, Object> values, Call call) throws EntityException {
    EntityType type = type(typeId);
    checkDepth(call);
    Map<String, Object> changes = checked(type, values, false);
    Instant now = Instant.parse(EntityValues.time(clock.instant()));
    List<Event> events = new ArrayList<>();
    return run(
        call.dryRun(),
        TOPIC,
        events,
        c -> {
          Optional<Map<String, Object>> old = get(c, type, id);
          if (old.isEmpty()) {
            return Optional.<Map<String, Object>>empty();
          }
          Map<String, Object> fields = new LinkedHashMap<>(old.get());
          fields.keySet().removeAll(EntityType.RESERVED);
          List<String> changed = new ArrayList<>();
          for (var e : changes.entrySet()) {
            EntityType.Field f = type.field(e.getKey()).orElseThrow();
            Object before = fields.get(f.name());
            if (e.getValue() == null) {
              if (f.required()) {
                throw new EntityException("field '" + e.getKey() + "' is required");
              }
              fields.remove(f.name());
            } else {
              fields.put(f.name(), e.getValue());
            }
            if (!same(f, before, fields.get(f.name()))) {
              changed.add(f.name());
            }
          }
          if (changed.isEmpty()) {
            return old;
          }
          Instant created = (Instant) old.get().get("created");
          String createdBy = (String) old.get().get("created_by");
          write(c, type, id, created, now, createdBy, call.by(), fields, false);
          if (!call.dryRun()) {
            Event e =
                event(
                    call,
                    "updated",
                    type,
                    id,
                    inTypeOrder(type, changed),
                    ":" + now.toEpochMilli());
            store.append(e);
            events.add(e);
          }
          return Optional.of(entity(type, id, created, now, createdBy, call.by(), fields));
        });
  }

  /** Whether two values of a field are the same once stored. */
  private static boolean same(EntityType.Field f, Object a, Object b) throws EntityException {
    if (a == null || b == null) {
      return a == b;
    }
    try {
      return CANONICAL
          .writeValueAsString(EntityValues.toStored(f.type(), a))
          .equals(CANONICAL.writeValueAsString(EntityValues.toStored(f.type(), b)));
    } catch (JsonProcessingException e) {
      throw new EntityException("cannot compare '" + f.name() + "': " + e.getOriginalMessage(), e);
    }
  }

  /** Deletes an entity; see {@link #delete(String, String, Call)}. */
  public boolean delete(String typeId, String id, boolean dryRun) throws EntityException {
    return delete(typeId, id, Call.of(dryRun));
  }

  /** Deletes an entity and emits {@code entity.deleted}; false if there was none. */
  public boolean delete(String typeId, String id, Call call) throws EntityException {
    EntityType type = type(typeId);
    checkDepth(call);
    List<Event> events = new ArrayList<>();
    return run(
        call.dryRun(),
        TOPIC,
        events,
        c -> {
          int n;
          try (PreparedStatement s =
              c.prepareStatement("DELETE FROM entity WHERE type = ? AND id = ?")) {
            s.setString(1, type.id());
            s.setString(2, id);
            n = s.executeUpdate();
          }
          unique(c, type, id, Map.of(), false);
          if (n > 0 && !call.dryRun()) {
            Event e = event(call, "deleted", type, id, null, ":" + clock.instant().toEpochMilli());
            store.append(e);
            events.add(e);
          }
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
      EntityType type,
      String id,
      Instant created,
      Instant updated,
      String createdBy,
      String updatedBy,
      Map<String, Object> fields) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", id);
    m.put("created", created);
    m.put("updated", updated);
    m.put("created_by", createdBy);
    m.put("updated_by", updatedBy);
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
            "SELECT id, created, updated, created_by, updated_by, json FROM entity WHERE type = ? AND id = ?")) {
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
      Map<String, Object> read = JSON.readValue(r.getString(6), Map.class);
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
        type,
        r.getString(1),
        Instant.parse(r.getString(2)),
        Instant.parse(r.getString(3)),
        r.getString(4),
        r.getString(5),
        fields);
  }

  private static void write(
      Connection c,
      EntityType type,
      String id,
      Instant created,
      Instant updated,
      String createdBy,
      String updatedBy,
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
            ? "INSERT INTO entity (created, updated, created_by, updated_by, json, type, id) VALUES"
                + " (?, ?, ?, ?, ?, ?, ?)"
            : "UPDATE entity SET created = ?, updated = ?, created_by = ?, updated_by = ?, json = ?"
                + " WHERE type = ? AND id = ?";
    try (PreparedStatement s = c.prepareStatement(sql)) {
      s.setString(1, EntityValues.time(created));
      s.setString(2, EntityValues.time(updated));
      s.setString(3, createdBy);
      s.setString(4, updatedBy);
      s.setString(5, json);
      s.setString(6, type.id());
      s.setString(7, id);
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
              "conflict: another '"
                  + type.id()
                  + "' already has "
                  + f.name()
                  + (f.sensitive() ? "" : " = " + v));
        }
      }
    }
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
