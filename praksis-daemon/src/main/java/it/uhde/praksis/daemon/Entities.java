// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.entity.EntityException;
import it.uhde.praksis.entity.EntityService;
import it.uhde.praksis.entity.EntityType;
import it.uhde.praksis.entity.EntityWire;
import it.uhde.praksis.entity.Filter;
import it.uhde.praksis.entity.Query;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Answers the entity requests of a person at the local socket. They need no grant, as the answers
 * to approvals need none: the person owns the data. Each one is written to the audit log as a call
 * by {@code user} (without the values of sensitive fields), and a change emits the same events as a
 * rule's change does. The values of sensitive fields reach an answer only if the person names them
 * in a read.
 */
final class Entities {
  private final Supplier<EntityService> service;
  private final Store store;
  private final Clock clock;

  Entities(Supplier<EntityService> service, Store store, Clock clock) {
    this.service = service;
    this.store = store;
    this.clock = clock;
  }

  private EntityService service() throws EntityException {
    EntityService s = service.get();
    if (s == null) {
      throw new EntityException("the entity service is not open");
    }
    return s;
  }

  private EntityType type(String id) throws EntityException {
    return service()
        .catalog()
        .get(id)
        .orElseThrow(() -> new EntityException("unknown entity type '" + id + "'"));
  }

  Response handle(Request request) {
    try {
      return switch (request) {
        case Request.EntityTypes t -> types();
        case Request.EntityQuery q -> query(q);
        case Request.EntityRead r -> read(r);
        case Request.EntityCreate c -> create(c);
        case Request.EntityUpdate u -> update(u);
        case Request.EntityDelete d -> delete(d);
        default -> new Response.Error("not an entity request");
      };
    } catch (EntityException e) {
      return new Response.Error(e.getMessage());
    }
  }

  private Response types() throws EntityException {
    var catalog = service().catalog();
    return new Response.EntityTypeList(
        catalog.types().values().stream()
            .sorted(java.util.Comparator.comparing(EntityType::id))
            .map(
                t ->
                    new Response.EntityTypeList.EntityTypeInfo(
                        t.id(),
                        t.title(),
                        t.description().orElse(null),
                        catalog.owner(t.id()).orElse(null),
                        t.hash(),
                        t.fields().stream()
                            .map(
                                f ->
                                    new Response.EntityTypeList.EntityField(
                                        f.name(),
                                        f.type().id(),
                                        f.required(),
                                        f.unique(),
                                        f.sensitive(),
                                        f.description().orElse(null)))
                            .toList()))
            .toList());
  }

  private Response query(Request.EntityQuery q) throws EntityException {
    EntityType type = type(q.type());
    Optional<Filter> filter =
        q.filter() == null || q.filter().isEmpty()
            ? Optional.empty()
            : Optional.of(Filter.parse(type, q.filter()));
    Query query =
        new Query(
            filter,
            q.sort() == null ? "created" : q.sort(),
            q.reverse(),
            q.limit() == null ? Query.DEFAULT_LIMIT : q.limit(),
            q.offset() == null ? 0 : q.offset());
    EntityService.Page page = service().query(q.type(), query);
    List<Map<String, Object>> items = new java.util.ArrayList<>();
    for (Map<String, Object> e : page.items()) {
      items.add(EntityWire.entity(service().withoutSensitive(q.type(), e)));
    }
    return new Response.EntityPage(items, page.total());
  }

  private Response read(Request.EntityRead r) throws EntityException {
    Optional<Map<String, Object>> found = service().read(r.type(), r.id());
    List<String> reveal = r.reveal() == null ? List.of() : r.reveal();
    Map<String, Object> revealed = null;
    if (found.isPresent() && !reveal.isEmpty()) {
      revealed = EntityWire.entity(service().reveal(r.type(), r.id(), reveal).orElseThrow());
    } else if (!reveal.isEmpty()) {
      service().reveal(r.type(), r.id(), reveal); // the names are checked in any case
    }
    Map<String, Object> detail = new LinkedHashMap<>();
    detail.put("type", r.type());
    detail.put("id", r.id());
    detail.put("revealed", reveal);
    if (!reveal.isEmpty()) {
      audit("entity.reveal", detail); // a plain read is not logged, a look at a secret is
    }
    return found.isEmpty()
        ? new Response.EntityItem(false, null, null)
        : new Response.EntityItem(
            true, EntityWire.entity(service().withoutSensitive(r.type(), found.get())), revealed);
  }

  private Response create(Request.EntityCreate c) throws EntityException {
    EntityType type = type(c.type());
    Map<String, Object> values =
        EntityWire.values(type, c.values() == null ? Map.of() : c.values());
    Map<String, Object> made = service().create(c.type(), values, EntityService.Call.of(false));
    audit(
        "entity.create",
        Map.of(
            "type", c.type(), "id", made.get("id"), "values", service().redact(c.type(), values)));
    return new Response.EntityItem(
        true, EntityWire.entity(service().withoutSensitive(c.type(), made)), null);
  }

  private Response update(Request.EntityUpdate u) throws EntityException {
    EntityType type = type(u.type());
    Map<String, Object> values =
        EntityWire.values(type, u.values() == null ? Map.of() : u.values());
    Optional<Map<String, Object>> changed =
        service().update(u.type(), u.id(), values, EntityService.Call.of(false));
    audit(
        "entity.update",
        Map.of("type", u.type(), "id", u.id(), "values", service().redact(u.type(), values)));
    return changed.isEmpty()
        ? new Response.EntityItem(false, null, null)
        : new Response.EntityItem(
            true, EntityWire.entity(service().withoutSensitive(u.type(), changed.get())), null);
  }

  private Response delete(Request.EntityDelete d) throws EntityException {
    boolean deleted = service().delete(d.type(), d.id(), EntityService.Call.of(false));
    audit("entity.delete", Map.of("type", d.type(), "id", d.id(), "deleted", deleted));
    return deleted
        ? new Response.Done("deleted " + d.type() + " " + d.id())
        : new Response.Error("there is no " + d.type() + " " + d.id());
  }

  private void audit(String subject, Map<String, Object> detail) {
    Map<String, Object> d = new LinkedHashMap<>(detail);
    d.put("by", EntityService.USER);
    try {
      store.audit(
          AuditEntry.of(clock.instant(), AuditEntry.Kind.CALL, Optional.empty(), subject, d));
    } catch (StoreException e) {
      System.err.println("praksis: cannot write the audit log: " + e.getMessage());
    }
  }
}
