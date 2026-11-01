// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.ruleset.Action;
import it.uhde.praksis.ruleset.Arg;
import it.uhde.praksis.ruleset.Rule;
import it.uhde.praksis.ruleset.Verifier.Finding;
import it.uhde.praksis.ruleset.Verifier.Severity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks what rules write in the arguments of the {@code entity.*} capabilities against the entity
 * types, without running anything: a type that does not exist, a field that the type does not have,
 * a value of the wrong type, a required field that a create leaves out, a filter that is not valid,
 * and a reveal of a field that is not sensitive. Only arguments written as values in the rule can
 * be checked; a value that is an expression is checked when the rule runs.
 */
public final class EntityChecks {
  /** The name of these checks in a finding. */
  public static final String CHECK = "entity";

  private EntityChecks() {}

  /** Checks the actions of the rules. */
  public static List<Finding> check(List<Rule> rules, EntityCatalog catalog) {
    List<Finding> findings = new ArrayList<>();
    for (Rule rule : rules) {
      for (Action action : rule.actions()) {
        if (action instanceof Action.Call call && call.capability().startsWith("entity.")) {
          check(rule.id(), call, catalog, findings);
        }
      }
    }
    return findings;
  }

  private static void check(
      String ruleId, Action.Call call, EntityCatalog catalog, List<Finding> findings) {
    String where = ruleId + ", action '" + call.id() + "'";
    if (!(call.args().get("type") instanceof Arg.Literal(Object value))) {
      return;
    }
    if (!(value instanceof String typeId)) {
      return;
    }
    Optional<EntityType> found = catalog.get(typeId);
    if (found.isEmpty()) {
      findings.add(error(where, call.capability() + ": there is no entity type '" + typeId + "'"));
      return;
    }
    EntityType type = found.get();
    switch (call.capability()) {
      case "entity.create" -> values(where, type, call.args().get("values"), true, findings);
      case "entity.update" -> values(where, type, call.args().get("values"), false, findings);
      case "entity.query" -> query(where, type, call.args(), findings);
      case "entity.reveal" -> reveal(where, type, call.args().get("fields"), findings);
      default -> {}
    }
  }

  private static void values(
      String where, EntityType type, Arg arg, boolean create, List<Finding> findings) {
    if (!(arg instanceof Arg.Literal(Object value)) || !(value instanceof Map<?, ?> values)) {
      return;
    }
    for (var entry : values.entrySet()) {
      String name = String.valueOf(entry.getKey());
      Optional<EntityType.Field> field = type.field(name);
      if (field.isEmpty()) {
        findings.add(
            error(
                where,
                EntityType.RESERVED.contains(name)
                    ? "field '" + name + "' is set by Praksis"
                    : "type '" + type.id() + "' has no field '" + name + "'"));
      } else if (entry.getValue() != null) {
        try {
          EntityValues.normalize(name, field.get().type(), entry.getValue());
        } catch (EntityException e) {
          findings.add(error(where, e.getMessage()));
        }
      } else if (!create && field.get().required()) {
        findings.add(error(where, "field '" + name + "' is required and cannot be removed"));
      }
    }
    if (create) {
      for (EntityType.Field f : type.fields()) {
        if (f.required() && values.get(f.name()) == null) {
          findings.add(error(where, "field '" + f.name() + "' is required"));
        }
      }
    }
  }

  private static void query(
      String where, EntityType type, Map<String, Arg> args, List<Finding> findings) {
    if (args.get("filter") instanceof Arg.Literal(Object filter)) {
      try {
        Filters.parse(type, filter);
      } catch (EntityException e) {
        findings.add(error(where, e.getMessage()));
      }
    }
    if (args.get("sort") instanceof Arg.Literal(Object sort)
        && sort instanceof String name
        && Filters.typeOf(type, name).isEmpty()) {
      findings.add(error(where, "cannot sort by '" + name + "' in type '" + type.id() + "'"));
    }
  }

  private static void reveal(String where, EntityType type, Arg arg, List<Finding> findings) {
    if (!(arg instanceof Arg.Literal(Object value)) || !(value instanceof List<?> names)) {
      return;
    }
    if (names.isEmpty()) {
      findings.add(error(where, "fields: name at least one sensitive field"));
    }
    for (Object name : names) {
      Optional<EntityType.Field> f = type.field(String.valueOf(name));
      if (f.isEmpty() || !f.get().sensitive()) {
        findings.add(
            error(
                where,
                "field '" + name + "' of type '" + type.id() + "' is not a sensitive field"));
      }
    }
  }

  private static Finding error(String where, String message) {
    return new Finding(Severity.ERROR, CHECK, where, message);
  }
}
