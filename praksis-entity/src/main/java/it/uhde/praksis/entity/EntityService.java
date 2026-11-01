// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.nio.charset.StandardCharsets;
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
 * Creates, reads, queries, updates and deletes entities in the storage that their type is bound to
 * (the daemon's SQLite database unless a person chose another). It checks every write against the
 * type (known fields, types, required and unique) and keeps {@code id}, {@code created} and {@code
 * updated}. An entity is the map of its fields; the three fields that Praksis sets are always in
 * it.
 *
 * <p>Writes to one storage are done one at a time. With {@code dryRun} the call is checked in full
 * (including unique values) and nothing is written. When the storage is the daemon's database a
 * change and its event are one transaction; with another storage the change is written first and
 * the event after it, so a crash in between loses the event but never the change. Permissions are
 * not checked here; they are those of the capabilities that call this class.
 */
public final class EntityService {
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
   * @param refusal asked before a delete does something else than the call names (it deletes or
   *     changes the entities that refer to the one deleted): whether the caller may also do that;
   *     empty means yes, else why not
   */
  public record Call(
      boolean dryRun,
      Optional<Event> cause,
      String idempotencyKey,
      String by,
      java.util.function.BiFunction<String, Map<String, Object>, Optional<String>> refusal) {
    /** A call that may do everything it asks for. */
    public Call(boolean dryRun, Optional<Event> cause, String idempotencyKey, String by) {
      this(dryRun, cause, idempotencyKey, by, (capability, args) -> Optional.empty());
    }

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

  private final Store store;
  private final EntityCatalog catalog;
  private final Clock clock;
  private final Consumer<Event> queued;
  private final EntityStorages storages;

  /** Writes are done one at a time, so that a reference cannot be broken by a write in between. */
  private final Object writes = new Object();

  /**
   * Creates the service. Every change stores an event ({@code entity.created}, {@code
   * entity.updated} or {@code entity.deleted}) and after that hands it to {@code queued}, which has
   * the engine handle it.
   */
  public EntityService(
      Store store,
      EntityCatalog catalog,
      Clock clock,
      Consumer<Event> queued,
      EntityStorages storages) {
    this.store = store;
    this.catalog = catalog;
    this.clock = clock;
    this.queued = queued;
    this.storages = storages;
  }

  /** A service with all types in the daemon's database. */
  public EntityService(Store store, EntityCatalog catalog, Clock clock, Consumer<Event> queued) {
    this(store, catalog, clock, queued, sqliteOnly(store, catalog));
  }

  /** A service whose events are stored but not handed on. */
  public EntityService(Store store, EntityCatalog catalog, Clock clock) {
    this(store, catalog, clock, e -> {});
  }

  private static EntityStorages sqliteOnly(Store store, EntityCatalog catalog) {
    try {
      return EntityStorages.sqliteOnly(store, catalog);
    } catch (StorageException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  public EntityCatalog catalog() {
    return catalog;
  }

  private EntityType type(String id) throws EntityException {
    return catalog
        .get(id)
        .orElseThrow(() -> new EntityException("unknown entity type '" + id + "'"));
  }

  private static EntityException failed(Exception e) {
    return new EntityException(e.getMessage(), e);
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

  /** A write of a storage. */
  @FunctionalInterface
  private interface Write {
    void run() throws StorageException;
  }

  /**
   * Does the write and stores its event. In the daemon's database that is one transaction; in
   * another storage the write comes first, so a crash in between loses the event and keeps the
   * change.
   */
  private void commit(EntityStorage storage, Event event, Write write)
      throws StorageException, EntityException {
    if (storage instanceof SqliteEntityStorage) {
      storage.atomically(
          () -> {
            write.run();
            append(event);
            return null;
          });
    } else {
      write.run();
      append(event);
      store.touch("entities");
    }
    queued.accept(event);
  }

  private void append(Event event) throws StorageException {
    try {
      store.append(event);
    } catch (StoreException e) {
      throw new StorageException("cannot store the event: " + e.getMessage(), e);
    }
  }

  /** Refuses a unique value that another entity of the type has. */
  private static void uniqueFree(
      EntityStorage storage, EntityType type, String id, Map<String, Object> fields)
      throws StorageException, EntityException {
    for (EntityType.Field f : type.fields()) {
      Object v = fields.get(f.name());
      if (!f.unique() || v == null) {
        continue;
      }
      Optional<String> owner = storage.findUnique(type.id(), f.name(), v);
      if (owner.isPresent() && !owner.get().equals(id)) {
        throw new EntityException(
            "conflict: another '"
                + type.id()
                + "' already has "
                + f.name()
                + (f.sensitive() ? "" : " = " + v));
      }
    }
  }

  private static Map<String, Object> toMap(EntityType type, StoredEntity e) {
    return entity(type, e.id(), e.created(), e.updated(), e.createdBy(), e.updatedBy(), e.fields());
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
    EntityStorage storage = storages.of(typeId);
    synchronized (writes) {
      try {
        Optional<StoredEntity> existing = storage.get(typeId, id);
        if (existing.isPresent() && key != null && !key.isEmpty()) {
          return toMap(type, existing.get());
        }
        uniqueFree(storage, type, id, fields);
        referencesExist(type, fields, Map.of());
        StoredEntity made = new StoredEntity(id, now, now, call.by(), call.by(), fields);
        if (!call.dryRun()) {
          Event e = event(call, "created", type, id, inTypeOrder(type, fields.keySet()), "");
          commit(storage, e, () -> storage.insert(typeId, made));
        }
        return toMap(type, made);
      } catch (StorageException e) {
        throw failed(e);
      }
    }
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
   * The values of a write as the audit log may keep them: the values of sensitive fields are
   * replaced by a marker. An unknown type or a value that is not a map is returned as it is.
   */
  public Map<String, Object> redact(String typeId, Map<String, Object> values) {
    EntityType type = catalog.get(typeId).orElse(null);
    if (type == null) {
      return values;
    }
    Map<String, Object> out = new LinkedHashMap<>();
    values.forEach(
        (k, v) ->
            out.put(
                k,
                type.field(k).filter(EntityType.Field::sensitive).isPresent()
                    ? EntityCapabilities.HIDDEN
                    : v));
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
    try {
      return storages
          .of(typeId)
          .get(typeId, id)
          .map(
              e -> {
                Map<String, Object> out = new LinkedHashMap<>();
                names.stream()
                    .filter(e.fields()::containsKey)
                    .forEach(name -> out.put(name, e.fields().get(name)));
                return out;
              });
    } catch (StorageException e) {
      throw failed(e);
    }
  }

  /** The entity, or empty. */
  public Optional<Map<String, Object>> read(String typeId, String id) throws EntityException {
    EntityType type = type(typeId);
    try {
      return storages.of(typeId).get(typeId, id).map(e -> toMap(type, e));
    } catch (StorageException e) {
      throw failed(e);
    }
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
    if (Filters.typeOf(type, query.sort()).isEmpty()) {
      throw new EntityException("cannot sort by '" + query.sort() + "' in type '" + typeId + "'");
    }
    try {
      EntityStorage.Page page = storages.of(typeId).query(typeId, query);
      return new Page(page.items().stream().map(e -> toMap(type, e)).toList(), page.total());
    } catch (StorageException e) {
      throw failed(e);
    }
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
    EntityStorage storage = storages.of(typeId);
    synchronized (writes) {
      try {
        Optional<StoredEntity> old = storage.get(typeId, id);
        if (old.isEmpty()) {
          return Optional.empty();
        }
        Map<String, Object> fields = new LinkedHashMap<>(old.get().fields());
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
          return Optional.of(toMap(type, old.get()));
        }
        uniqueFree(storage, type, id, fields);
        referencesExist(type, fields, old.get().fields());
        StoredEntity updated =
            new StoredEntity(
                id, old.get().created(), now, old.get().createdBy(), call.by(), fields);
        if (!call.dryRun()) {
          Event e =
              event(
                  call, "updated", type, id, inTypeOrder(type, changed), ":" + now.toEpochMilli());
          commit(storage, e, () -> storage.replace(typeId, updated));
        }
        return Optional.of(toMap(type, updated));
      } catch (StorageException e) {
        throw failed(e);
      }
    }
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

  /**
   * Deletes an entity and emits {@code entity.deleted}; false if there was none. What refers to the
   * entity decides what else happens, as the fields that refer to its type say ({@code on_delete}):
   * a {@code restrict} reference refuses the delete, a {@code cascade} reference deletes the entity
   * that has it (and what refers to that), a {@code set_null} reference is cleared. The caller must
   * be allowed to do all of it, or nothing is done.
   */
  public boolean delete(String typeId, String id, Call call) throws EntityException {
    EntityType type = type(typeId);
    checkDepth(call);
    synchronized (writes) {
      try {
        if (storages.of(typeId).get(typeId, id).isEmpty()) {
          return false;
        }
        Plan plan = plan(typeId, id, call);
        if (call.dryRun()) {
          return true;
        }
        Instant now = Instant.parse(EntityValues.time(clock.instant()));
        for (Plan.Change c : plan.changes()) {
          EntityStorage st = storages.of(c.type());
          EntityType t = type(c.type());
          StoredEntity before = st.get(c.type(), c.id()).orElseThrow();
          Map<String, Object> fields = new LinkedHashMap<>(before.fields());
          fields.putAll(c.set());
          c.clear().forEach(fields::remove);
          StoredEntity changed =
              new StoredEntity(
                  c.id(), before.created(), now, before.createdBy(), call.by(), fields);
          List<String> names = new ArrayList<>(c.set().keySet());
          names.addAll(c.clear());
          Event e =
              event(call, "updated", t, c.id(), inTypeOrder(t, names), ":" + now.toEpochMilli());
          commit(st, e, () -> st.replace(c.type(), changed));
        }
        for (int i = plan.deletes().size() - 1; i >= 0; i--) {
          Plan.Ref r = plan.deletes().get(i);
          EntityStorage st = storages.of(r.type());
          Event e =
              event(
                  call,
                  "deleted",
                  type(r.type()),
                  r.id(),
                  null,
                  ":" + clock.instant().toEpochMilli());
          commit(st, e, () -> st.delete(r.type(), r.id()));
        }
        return true;
      } catch (StorageException e) {
        throw failed(e);
      }
    }
  }

  /** What deleting an entity does besides: the entities deleted too and the references cleared. */
  private record Plan(List<Ref> deletes, List<Change> changes) {
    record Ref(String type, String id) {}

    /** An entity that is changed: fields set to a new value, fields removed. */
    record Change(String type, String id, Map<String, Object> set, List<String> clear) {}
  }

  private Plan plan(String typeId, String id, Call call) throws EntityException, StorageException {
    // the entities that go with it: the closure of the cascade references
    java.util.LinkedHashSet<Plan.Ref> deletes = new java.util.LinkedHashSet<>();
    deletes.add(new Plan.Ref(typeId, id));
    java.util.ArrayDeque<Plan.Ref> queue = new java.util.ArrayDeque<>(deletes);
    while (!queue.isEmpty()) {
      Plan.Ref r = queue.poll();
      for (EntityCatalog.Reference ref : catalog.referencesTo(r.type())) {
        if (ref.field().onDelete() != EntityType.OnDelete.CASCADE) {
          continue;
        }
        for (String rid : referrers(ref, r.id())) {
          Plan.Ref next = new Plan.Ref(ref.type().id(), rid);
          if (deletes.add(next)) {
            queue.add(next);
          }
        }
      }
    }
    // what else refers to any of them
    Map<Plan.Ref, Map<String, Object>> set = new LinkedHashMap<>();
    Map<Plan.Ref, List<String>> clear = new LinkedHashMap<>();
    for (Plan.Ref r : deletes) {
      for (EntityCatalog.Reference ref : catalog.referencesTo(r.type())) {
        for (String rid : referrers(ref, r.id())) {
          Plan.Ref other = new Plan.Ref(ref.type().id(), rid);
          if (deletes.contains(other)) {
            continue;
          }
          if (ref.field().onDelete() != EntityType.OnDelete.SET_NULL) {
            throw new EntityException(
                "cannot delete "
                    + r.type()
                    + " "
                    + r.id()
                    + ": "
                    + other.type()
                    + " "
                    + other.id()
                    + " refers to it in the field '"
                    + ref.field().name()
                    + "'");
          }
          StoredEntity holder =
              storages.of(other.type()).get(other.type(), other.id()).orElseThrow();
          String name = ref.field().name();
          if (ref.field().type() == ValueType.LIST) {
            @SuppressWarnings("unchecked")
            List<Object> now =
                new ArrayList<>(
                    (List<Object>)
                        set.getOrDefault(other, Map.of())
                            .getOrDefault(name, holder.fields().get(name)));
            now.remove(r.id());
            set.computeIfAbsent(other, k -> new LinkedHashMap<>()).put(name, now);
          } else {
            clear.computeIfAbsent(other, k -> new ArrayList<>()).add(name);
          }
        }
      }
    }
    // the caller may do all of it
    for (Plan.Ref r : deletes) {
      if (r.type().equals(typeId) && r.id().equals(id)) {
        continue;
      }
      Optional<String> why =
          call.refusal().apply("entity.delete", Map.of("type", r.type(), "id", r.id()));
      if (why.isPresent()) {
        throw new EntityException(
            "deleting "
                + typeId
                + " "
                + id
                + " would also delete "
                + r.type()
                + " "
                + r.id()
                + ", which this call may not do: "
                + why.get());
      }
    }
    List<Plan.Change> changes = new ArrayList<>();
    java.util.Set<Plan.Ref> changed = new java.util.LinkedHashSet<>(set.keySet());
    changed.addAll(clear.keySet());
    for (Plan.Ref r : changed) {
      Map<String, Object> values = set.getOrDefault(r, Map.of());
      List<String> cleared = clear.getOrDefault(r, List.of());
      Map<String, Object> asked = new LinkedHashMap<>(values);
      cleared.forEach(n -> asked.put(n, null));
      Optional<String> why =
          call.refusal()
              .apply("entity.update", Map.of("type", r.type(), "id", r.id(), "values", asked));
      if (why.isPresent()) {
        throw new EntityException(
            "deleting "
                + typeId
                + " "
                + id
                + " would also change "
                + r.type()
                + " "
                + r.id()
                + ", which this call may not do: "
                + why.get());
      }
      changes.add(new Plan.Change(r.type(), r.id(), values, cleared));
    }
    return new Plan(new ArrayList<>(deletes), changes);
  }

  /** The ids of the entities whose field refers to the entity. */
  private List<String> referrers(EntityCatalog.Reference ref, String targetId)
      throws StorageException {
    String type = ref.type().id();
    String field = ref.field().name();
    boolean list = ref.field().type() == ValueType.LIST;
    EntityStorage st = storages.of(type);
    Optional<Filter> filter =
        list ? Optional.empty() : Optional.of(new Filter.Equals(field, targetId));
    List<String> out = new ArrayList<>();
    int offset = 0;
    while (true) {
      EntityStorage.Page page =
          st.query(type, new Query(filter, "id", false, Query.MAX_LIMIT, offset));
      for (StoredEntity e : page.items()) {
        if (!list || (e.fields().get(field) instanceof List<?> l && l.contains(targetId))) {
          out.add(e.id());
        }
      }
      offset += page.items().size();
      if (page.items().isEmpty() || offset >= page.total()) {
        return out;
      }
    }
  }

  /** The target of every reference that is new in these fields must exist. */
  private void referencesExist(EntityType type, Map<String, Object> fields, Map<String, Object> old)
      throws EntityException, StorageException {
    for (EntityType.Field f : type.fields()) {
      if (f.ref().isEmpty() || fields.get(f.name()) == null) {
        continue;
      }
      Object value = fields.get(f.name());
      List<Object> ids = value instanceof List<?> l ? new ArrayList<>(l) : List.of(value);
      Object before = old.get(f.name());
      List<?> known =
          before instanceof List<?> l ? l : before == null ? List.of() : List.of(before);
      for (Object each : ids) {
        if (!(each instanceof String ref) || ref.isEmpty()) {
          throw new EntityException(
              "field '"
                  + f.name()
                  + "' must "
                  + (value instanceof List ? "be a list of ids" : "be an id"));
        }
        if (known.contains(ref)) {
          continue;
        }
        if (storages.of(f.ref().get()).get(f.ref().get(), ref).isEmpty()) {
          throw new EntityException(
              "field '"
                  + f.name()
                  + "' refers to "
                  + f.ref().get()
                  + " '"
                  + ref
                  + "', which does not exist");
        }
      }
    }
  }

  /**
   * Some of the entities of a type with all their values, also those of sensitive fields, in order
   * of creation, for an export. The caller is a person; no rule can export.
   */
  public Dump export(String typeId, int offset, int limit) throws EntityException {
    type(typeId);
    if (limit < 1 || limit > Query.MAX_LIMIT || offset < 0) {
      throw new EntityException("limit must be from 1 to " + Query.MAX_LIMIT + ", offset from 0");
    }
    try {
      EntityStorage.Page page =
          storages
              .of(typeId)
              .query(typeId, new Query(Optional.empty(), "created", false, limit, offset));
      return new Dump(page.items(), page.total());
    } catch (StorageException e) {
      throw failed(e);
    }
  }

  /** A part of an export. */
  public record Dump(List<StoredEntity> items, long total) {}

  /**
   * An entity as an import brings it: the id, the times and who made it (a missing one is now and
   * {@link #USER}), and the values as an export wrote them.
   */
  public record Imported(
      String id,
      Instant created,
      Instant updated,
      String createdBy,
      String updatedBy,
      Map<String, Object> fields) {}

  /** What an import did. */
  public record ImportResult(int created, int replaced) {}

  /**
   * Writes entities that an export made, with their ids, times and provenance, and no events (a
   * restore or a move would flood the rules). Everything is checked first, as a create is: known
   * fields, types, required and unique values (against the stored entities and within the import)
   * and references (to stored entities, or to entities of the same import). An id that exists is an
   * error unless {@code upsert}. Nothing is written if anything is wrong, and with the daemon's own
   * database the write is one transaction.
   */
  public ImportResult importAll(String typeId, List<Imported> items, boolean upsert, boolean dryRun)
      throws EntityException {
    EntityType type = type(typeId);
    EntityStorage storage = storages.of(typeId);
    synchronized (writes) {
      try {
        Instant now = Instant.parse(EntityValues.time(clock.instant()));
        List<StoredEntity> ready = new ArrayList<>();
        Map<String, StoredEntity> byId = new LinkedHashMap<>();
        for (Imported item : items) {
          String where = "entity '" + item.id() + "': ";
          if (item.id() == null || item.id().isBlank() || item.id().length() > 100) {
            throw new EntityException("an entity needs an id of 1 to 100 characters");
          }
          if (byId.containsKey(item.id())) {
            throw new EntityException("the id " + item.id() + " is in the import twice");
          }
          Map<String, Object> fields;
          try {
            fields = checked(type, EntityWire.values(type, item.fields()), true);
          } catch (EntityException e) {
            throw new EntityException(where + e.getMessage(), e);
          }
          StoredEntity entity =
              new StoredEntity(
                  item.id(),
                  item.created() == null ? now : Instant.parse(EntityValues.time(item.created())),
                  item.updated() == null ? now : Instant.parse(EntityValues.time(item.updated())),
                  item.createdBy() == null || item.createdBy().isBlank() ? USER : item.createdBy(),
                  item.updatedBy() == null || item.updatedBy().isBlank() ? USER : item.updatedBy(),
                  fields);
          byId.put(entity.id(), entity);
          ready.add(entity);
        }
        int replaced = 0;
        for (StoredEntity e : ready) {
          boolean exists = storage.get(typeId, e.id()).isPresent();
          if (exists && !upsert) {
            throw new EntityException(
                "entity '" + e.id() + "' exists already; use upsert to replace it");
          }
          replaced += exists ? 1 : 0;
        }
        // unique values: not used by an entity outside the import, and not twice inside it
        for (EntityType.Field f : type.fields()) {
          if (!f.unique()) {
            continue;
          }
          Map<Object, String> seen = new java.util.HashMap<>();
          for (StoredEntity e : ready) {
            Object v = e.fields().get(f.name());
            if (v == null) {
              continue;
            }
            String twin = seen.put(v, e.id());
            Optional<String> owner = storage.findUnique(typeId, f.name(), v);
            if (twin != null || (owner.isPresent() && !byId.containsKey(owner.get()))) {
              throw new EntityException(
                  "entity '"
                      + e.id()
                      + "': another '"
                      + typeId
                      + "' already has "
                      + f.name()
                      + (f.sensitive() ? "" : " = " + v));
            }
          }
        }
        // references: to a stored entity, or to one of the import
        for (StoredEntity e : ready) {
          for (EntityType.Field f : type.fields()) {
            Object value = e.fields().get(f.name());
            if (f.ref().isEmpty() || value == null) {
              continue;
            }
            List<?> ids = value instanceof List<?> l ? l : List.of(value);
            for (Object each : ids) {
              if (!(each instanceof String target) || target.isEmpty()) {
                throw new EntityException(
                    "entity '" + e.id() + "': field '" + f.name() + "' must hold ids");
              }
              boolean inImport = f.ref().get().equals(typeId) && byId.containsKey(target);
              if (!inImport && storages.of(f.ref().get()).get(f.ref().get(), target).isEmpty()) {
                throw new EntityException(
                    "entity '"
                        + e.id()
                        + "': field '"
                        + f.name()
                        + "' refers to "
                        + f.ref().get()
                        + " '"
                        + target
                        + "', which does not exist");
              }
            }
          }
        }
        if (!dryRun) {
          storage.atomically(
              () -> {
                for (StoredEntity e : ready) {
                  if (storage.get(typeId, e.id()).isPresent()) {
                    storage.replace(typeId, e);
                  } else {
                    storage.insert(typeId, e);
                  }
                }
                return null;
              });
          if (!(storage instanceof SqliteEntityStorage)) {
            store.touch("entities");
          }
        }
        return new ImportResult(ready.size() - replaced, replaced);
      } catch (StorageException e) {
        throw failed(e);
      }
    }
  }

  /**
   * Turns all entities of a type into entities of its current version: the steps of the versions
   * after the one they were made under are applied in order, then every entity is checked as a
   * create is checked (known fields, types, required, unique values, references). Nothing is
   * written if any entity does not fit; with the daemon's database the write is one transaction. No
   * events are emitted.
   *
   * @param from the version that the stored entities have, or null if the layers do not have it
   *     (the type was changed in place)
   * @param versions the versions after it, oldest first, the last being the current one
   * @return how many entities were rewritten
   */
  public int migrate(String typeId, EntityType from, List<EntityCatalog.Version> versions)
      throws EntityException {
    EntityType target = type(typeId);
    EntityStorage storage = storages.of(typeId);
    synchronized (writes) {
      try {
        if (!(storage instanceof SqliteEntityStorage sqlite)) {
          throw new EntityException(
              "the entities of type '"
                  + typeId
                  + "' are kept in the storage '"
                  + storage.name()
                  + "', which cannot migrate them (only the daemon's own database can): change"
                  + " the file or the table by hand, or export the entities before the type is"
                  + " replaced, edit the export and import it");
        }
        List<StoredEntity> old = new ArrayList<>();
        for (SqliteEntityStorage.Raw raw : sqlite.raw(typeId)) {
          Map<String, Object> fields = new LinkedHashMap<>();
          for (var e : raw.json().entrySet()) {
            EntityType.Field f = from == null ? null : from.field(e.getKey()).orElse(null);
            try {
              fields.put(
                  e.getKey(),
                  f == null || e.getValue() == null
                      ? e.getValue()
                      : EntityValues.fromStored(f.type(), e.getValue()));
            } catch (RuntimeException ex) {
              throw new EntityException(
                  "entity '"
                      + raw.id()
                      + "': the stored value of '"
                      + e.getKey()
                      + "' is not a "
                      + f.type().id(),
                  ex);
            }
          }
          old.add(
              new StoredEntity(
                  raw.id(),
                  raw.created(),
                  raw.updated(),
                  raw.createdBy(),
                  raw.updatedBy(),
                  fields));
        }
        List<StoredEntity> migrated = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (StoredEntity e : old) {
          ids.add(e.id());
        }
        for (StoredEntity e : old) {
          String where = "entity '" + e.id() + "': ";
          Map<String, Object> fields = new LinkedHashMap<>(e.fields());
          EntityType previous = from;
          try {
            for (EntityCatalog.Version v : versions) {
              fields = EntityMigration.apply(fields, v.steps(), previous);
              previous = v.type();
            }
            fields = checked(target, fields, true);
          } catch (EntityException ex) {
            throw new EntityException(where + ex.getMessage(), ex);
          }
          migrated.add(
              new StoredEntity(
                  e.id(), e.created(), e.updated(), e.createdBy(), e.updatedBy(), fields));
        }
        for (EntityType.Field f : target.fields()) {
          Map<Object, String> seen = new java.util.HashMap<>();
          for (StoredEntity e : migrated) {
            Object v = e.fields().get(f.name());
            if (v == null) {
              continue;
            }
            if (f.unique() && seen.put(v, e.id()) != null) {
              throw new EntityException(
                  "entity '"
                      + e.id()
                      + "': another '"
                      + typeId
                      + "' has the same "
                      + f.name()
                      + (f.sensitive() ? "" : " = " + v));
            }
            if (f.ref().isPresent()) {
              List<?> targets = v instanceof List<?> l ? l : List.of(v);
              for (Object each : targets) {
                if (!(each instanceof String ref) || ref.isEmpty()) {
                  throw new EntityException(
                      "entity '" + e.id() + "': field '" + f.name() + "' must hold ids");
                }
                boolean inside = f.ref().get().equals(typeId) && ids.contains(ref);
                if (!inside && storages.of(f.ref().get()).get(f.ref().get(), ref).isEmpty()) {
                  throw new EntityException(
                      "entity '"
                          + e.id()
                          + "': field '"
                          + f.name()
                          + "' refers to "
                          + f.ref().get()
                          + " '"
                          + ref
                          + "', which does not exist");
                }
              }
            }
          }
        }
        storage.atomically(
            () -> {
              for (StoredEntity e : migrated) {
                storage.replace(typeId, e);
              }
              return null;
            });
        if (!(storage instanceof SqliteEntityStorage)) {
          store.touch("entities");
        }
        return migrated.size();
      } catch (StorageException e) {
        throw failed(e);
      }
    }
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
}
