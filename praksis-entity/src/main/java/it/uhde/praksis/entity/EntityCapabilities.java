// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The module {@code entity}, which is part of the daemon: the five capabilities that create, read,
 * query, update and delete entities. The type is a scopable parameter, so a grant can name the
 * types a rule may use; every call goes through the usual permission check, audit and dry run.
 */
public final class EntityCapabilities {
  public static final String MODULE = "entity";

  private final java.util.function.Supplier<EntityService> services;

  /**
   * The capabilities, which ask {@code services} for the service at each call, so that they can be
   * registered before the database is open.
   */
  public EntityCapabilities(java.util.function.Supplier<EntityService> services) {
    this.services = services;
  }

  /** The capabilities of a service that exists already. */
  public static EntityCapabilities of(EntityService service) {
    return new EntityCapabilities(() -> service);
  }

  private EntityService service() {
    EntityService service = services.get();
    if (service == null) {
      throw new IllegalStateException("the entity service is not open yet");
    }
    return service;
  }

  private static FieldDescriptor in(
      String name, ValueType type, boolean required, boolean scopable) {
    return new FieldDescriptor(name, type, required, scopable);
  }

  private static FieldDescriptor out(String name, ValueType type) {
    return new FieldDescriptor(name, type, true, false);
  }

  /** The descriptor of the module. */
  public static ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE,
        "0.1.0",
        List.of(),
        List.of(
            new CapabilityDescriptor(
                "entity.create",
                EffectClass.LOCAL_WRITE,
                true,
                List.of(
                    in("type", ValueType.STRING, true, true),
                    in("values", ValueType.MAP, true, false)),
                List.of(out("id", ValueType.STRING), out("entity", ValueType.MAP))),
            new CapabilityDescriptor(
                "entity.read",
                EffectClass.READ_ONLY,
                true,
                List.of(
                    in("type", ValueType.STRING, true, true),
                    in("id", ValueType.STRING, true, false)),
                List.of(
                    out("found", ValueType.BOOL),
                    new FieldDescriptor("entity", ValueType.MAP, false, false))),
            new CapabilityDescriptor(
                "entity.query",
                EffectClass.READ_ONLY,
                true,
                List.of(
                    in("type", ValueType.STRING, true, true),
                    in("filter", ValueType.MAP, false, false),
                    in("sort", ValueType.STRING, false, false),
                    in("reverse", ValueType.BOOL, false, false),
                    in("limit", ValueType.INT, false, false),
                    in("offset", ValueType.INT, false, false)),
                List.of(
                    out("items", ValueType.LIST),
                    out("count", ValueType.INT),
                    out("total", ValueType.INT))),
            new CapabilityDescriptor(
                "entity.update",
                EffectClass.LOCAL_WRITE,
                true,
                List.of(
                    in("type", ValueType.STRING, true, true),
                    in("id", ValueType.STRING, true, false),
                    in("values", ValueType.MAP, true, false)),
                List.of(
                    out("found", ValueType.BOOL),
                    new FieldDescriptor("entity", ValueType.MAP, false, false))),
            new CapabilityDescriptor(
                "entity.delete",
                EffectClass.DESTRUCTIVE,
                true,
                List.of(
                    in("type", ValueType.STRING, true, true),
                    in("id", ValueType.STRING, true, false)),
                List.of(out("deleted", ValueType.BOOL)))),
        List.of());
  }

  /** The five capabilities by name. */
  public Map<String, Capability> capabilities() {
    Map<String, Capability> m = new LinkedHashMap<>();
    cap(m, "entity.create", this::create);
    cap(m, "entity.read", this::read);
    cap(m, "entity.query", this::query);
    cap(m, "entity.update", this::update);
    cap(m, "entity.delete", this::delete);
    return m;
  }

  private interface Body {
    Map<String, Object> run(CapabilityContext context, Map<String, Object> args)
        throws EntityException;
  }

  private static void cap(Map<String, Capability> into, String name, Body body) {
    into.put(
        name,
        new Capability() {
          @Override
          public String name() {
            return name;
          }

          @Override
          public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
              throws CapabilityException {
            try {
              return body.run(context, args);
            } catch (EntityException e) {
              throw new CapabilityException(e.getMessage(), false, e);
            }
          }
        });
  }

  private static String text(Map<String, Object> args, String key) {
    return (String) args.get(key);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Map<String, Object> args, String key) {
    return (Map<String, Object>) args.get(key);
  }

  private Map<String, Object> create(CapabilityContext c, Map<String, Object> args)
      throws EntityException {
    Map<String, Object> e =
        service().create(text(args, "type"), map(args, "values"), c.idempotencyKey(), c.dryRun());
    return Map.of("id", e.get("id"), "entity", e);
  }

  private Map<String, Object> read(CapabilityContext c, Map<String, Object> args)
      throws EntityException {
    return found(service().read(text(args, "type"), text(args, "id")));
  }

  private Map<String, Object> query(CapabilityContext c, Map<String, Object> args)
      throws EntityException {
    String type = text(args, "type");
    EntityType t =
        service()
            .catalog()
            .get(type)
            .orElseThrow(() -> new EntityException("unknown entity type '" + type + "'"));
    Optional<Filter> filter =
        args.get("filter") == null
            ? Optional.empty()
            : Optional.of(Filter.parse(t, args.get("filter")));
    Query q =
        new Query(
            filter,
            args.get("sort") == null ? "created" : text(args, "sort"),
            Boolean.TRUE.equals(args.get("reverse")),
            args.get("limit") == null
                ? Query.DEFAULT_LIMIT
                : ((Number) args.get("limit")).intValue(),
            args.get("offset") == null ? 0 : ((Number) args.get("offset")).intValue());
    EntityService.Page page = service().query(type, q);
    return Map.of(
        "items", page.items(), "count", (long) page.items().size(), "total", page.total());
  }

  private Map<String, Object> update(CapabilityContext c, Map<String, Object> args)
      throws EntityException {
    return found(
        service().update(text(args, "type"), text(args, "id"), map(args, "values"), c.dryRun()));
  }

  private Map<String, Object> delete(CapabilityContext c, Map<String, Object> args)
      throws EntityException {
    return Map.of("deleted", service().delete(text(args, "type"), text(args, "id"), c.dryRun()));
  }

  private static Map<String, Object> found(Optional<Map<String, Object>> entity) {
    return entity.isPresent()
        ? Map.of("found", true, "entity", entity.get())
        : Map.of("found", false);
  }
}
