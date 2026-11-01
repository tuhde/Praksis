// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.ruleset.Table;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * One step that turns the entities of the version of a type below into entities of the replacement.
 * A replacement lists its steps as {@code [[migration]]} tables with exactly one key each:
 *
 * <pre>
 * [[migration]]
 * rename = { from = "phone", to = "mobile" }
 * [[migration]]
 * drop = "fax"
 * [[migration]]
 * default = { field = "country", value = "DE" }    # for the entities that have no value
 * [[migration]]
 * convert = { field = "age", to = "int" }           # a text becomes a number, ...
 * [[migration]]
 * check = "email"                                    # the stored values must fit the new rule
 * </pre>
 *
 * The steps are applied in order to every stored entity when the daemon first starts with the
 * replacement; if any entity does not fit afterwards, nothing is changed and the start stops.
 */
public sealed interface EntityMigration {
  /** The field gets another name. */
  record Rename(String from, String to) implements EntityMigration {}

  /** The field and its values are removed. */
  record Drop(String field) implements EntityMigration {}

  /** An entity without a value for the field gets this one. */
  record Default(String field, Object value) implements EntityMigration {}

  /** The values of the field are converted to another type. */
  record Convert(String field, ValueType to) implements EntityMigration {}

  /**
   * Changes nothing. It says that the stored values are meant to fit a rule that the replacement
   * adds to the field (unique, a reference), and fails the migration if they do not.
   */
  record Check(String field) implements EntityMigration {}

  /** The conversions that exist. */
  Set<String> CONVERSIONS =
      Set.of(
          "string>int",
          "string>double",
          "string>bool",
          "string>timestamp",
          "int>double",
          "int>string",
          "double>int",
          "double>string",
          "bool>string",
          "timestamp>string");

  /** Reads the {@code [[migration]]} tables; problems are recorded in the table. */
  static List<EntityMigration> parse(Table t) {
    List<EntityMigration> out = new ArrayList<>();
    for (Table m : t.tables("migration")) {
      m.onlyKeys(Set.of("rename", "drop", "default", "convert", "check"));
      if (m.rawEntries().size() != 1) {
        m.problem("a migration step has exactly one of rename, drop, default, convert and check");
        continue;
      }
      var entry = m.rawEntries().entrySet().iterator().next();
      Object v = entry.getValue();
      switch (entry.getKey()) {
        case "rename" -> {
          Optional<Table> r = m.table("rename", true);
          r.ifPresent(
              rt -> {
                rt.onlyKeys(Set.of("from", "to"));
                Optional<String> from = rt.string("from", true);
                Optional<String> to = rt.string("to", true);
                if (from.isPresent() && to.isPresent()) {
                  out.add(new Rename(from.get(), to.get()));
                }
              });
        }
        case "drop" -> {
          if (v instanceof String s && !s.isBlank()) {
            out.add(new Drop(s));
          } else {
            m.problem("'drop' must be the name of a field");
          }
        }
        case "check" -> {
          if (v instanceof String s && !s.isBlank()) {
            out.add(new Check(s));
          } else {
            m.problem("'check' must be the name of a field");
          }
        }
        case "default" -> {
          Optional<Table> d = m.table("default", true);
          d.ifPresent(
              dt -> {
                dt.onlyKeys(Set.of("field", "value"));
                Optional<String> field = dt.string("field", true);
                if (field.isPresent() && dt.has("value")) {
                  out.add(new Default(field.get(), dt.raw("value").orElse(null)));
                } else if (field.isPresent()) {
                  dt.problem("missing 'value'");
                }
              });
        }
        case "convert" -> {
          Optional<Table> c = m.table("convert", true);
          c.ifPresent(
              ct -> {
                ct.onlyKeys(Set.of("field", "to"));
                Optional<String> field = ct.string("field", true);
                Optional<String> to = ct.string("to", true);
                if (field.isPresent() && to.isPresent()) {
                  Optional<ValueType> type = ValueType.fromId(to.get());
                  if (type.isEmpty()) {
                    ct.problem("unknown type '" + to.get() + "'");
                  } else {
                    out.add(new Convert(field.get(), type.get()));
                  }
                }
              });
        }
        default -> {
          // reported by onlyKeys
        }
      }
    }
    return out;
  }

  /**
   * What the steps do to the shape of a type: the type as its entities look after the steps. A step
   * that cannot be done to this shape (a field that is not there, a name that is taken, a
   * conversion that does not exist, a default that does not fit the new field) is a message.
   *
   * @param old the version below
   * @param steps the steps of the replacement
   * @param replacement the version that the steps lead to
   * @param problems where the messages go
   * @return the shape after the steps
   */
  static List<EntityType.Field> shape(
      EntityType old, List<EntityMigration> steps, EntityType replacement, List<String> problems) {
    Map<String, EntityType.Field> fields = new LinkedHashMap<>();
    old.fields().forEach(f -> fields.put(f.name(), f));
    for (EntityMigration step : steps) {
      switch (step) {
        case Rename r -> {
          EntityType.Field f = fields.get(r.from());
          if (f == null) {
            problems.add("rename: there is no field '" + r.from() + "'");
          } else if (fields.containsKey(r.to())) {
            problems.add("rename: the field '" + r.to() + "' exists already");
          } else {
            Map<String, EntityType.Field> next = new LinkedHashMap<>();
            fields.forEach(
                (name, field) ->
                    next.put(
                        name.equals(r.from()) ? r.to() : name,
                        name.equals(r.from()) ? withName(field, r.to()) : field));
            fields.clear();
            fields.putAll(next);
          }
        }
        case Drop d -> {
          if (fields.remove(d.field()) == null) {
            problems.add("drop: there is no field '" + d.field() + "'");
          }
        }
        case Default d -> {
          EntityType.Field target = replacement.field(d.field()).orElse(null);
          if (target == null) {
            problems.add("default: the type has no field '" + d.field() + "'");
          } else {
            try {
              EntityValues.normalize(d.field(), target.type(), d.value());
            } catch (EntityException e) {
              problems.add("default: " + e.getMessage());
            }
            // every entity has a value afterwards
            EntityType.Field now = fields.get(d.field());
            fields.put(
                d.field(),
                new EntityType.Field(
                    d.field(),
                    target.type(),
                    true,
                    now != null && now.unique(),
                    now != null && now.sensitive(),
                    Optional.empty(),
                    now == null ? Optional.empty() : now.ref(),
                    now == null ? EntityType.OnDelete.RESTRICT : now.onDelete()));
          }
        }
        case Convert c -> {
          EntityType.Field f = fields.get(c.field());
          if (f == null) {
            problems.add("convert: there is no field '" + c.field() + "'");
          } else if (!CONVERSIONS.contains(f.type().id() + ">" + c.to().id())) {
            problems.add(
                "convert: a "
                    + f.type().id()
                    + " cannot be converted to "
                    + c.to().id()
                    + " (possible: "
                    + new java.util.TreeSet<>(CONVERSIONS)
                    + ")");
          } else {
            fields.put(
                c.field(),
                new EntityType.Field(
                    f.name(),
                    c.to(),
                    f.required(),
                    f.unique(),
                    f.sensitive(),
                    f.description(),
                    f.ref(),
                    f.onDelete()));
          }
        }
        case Check c -> {
          EntityType.Field f = fields.get(c.field());
          EntityType.Field target = replacement.field(c.field()).orElse(null);
          if (f == null) {
            problems.add("check: there is no field '" + c.field() + "'");
          } else if (target == null) {
            problems.add("check: the type has no field '" + c.field() + "'");
          } else {
            fields.put(
                c.field(),
                new EntityType.Field(
                    f.name(),
                    f.type(),
                    f.required(),
                    target.unique(),
                    f.sensitive(),
                    f.description(),
                    target.ref(),
                    target.onDelete()));
          }
        }
      }
    }
    return new ArrayList<>(fields.values());
  }

  private static EntityType.Field withName(EntityType.Field f, String name) {
    return new EntityType.Field(
        name,
        f.type(),
        f.required(),
        f.unique(),
        f.sensitive(),
        f.description(),
        f.ref(),
        f.onDelete());
  }

  /**
   * What can be said of the steps without the version below: that the fields they name in the new
   * type are there and that a conversion goes to the type the new field has.
   */
  static void check(List<EntityMigration> steps, EntityType replacement, List<String> problems) {
    for (EntityMigration step : steps) {
      switch (step) {
        case Default d -> {
          EntityType.Field target = replacement.field(d.field()).orElse(null);
          if (target == null) {
            problems.add("default: the type has no field '" + d.field() + "'");
          } else {
            try {
              EntityValues.normalize(d.field(), target.type(), d.value());
            } catch (EntityException e) {
              problems.add("default: " + e.getMessage());
            }
          }
        }
        case Convert c -> {
          EntityType.Field target = replacement.field(c.field()).orElse(null);
          if (target != null && target.type() != c.to()) {
            problems.add(
                "convert: the field '"
                    + c.field()
                    + "' is a "
                    + target.type().id()
                    + " in the type, not a "
                    + c.to().id());
          }
        }
        case Check c -> {
          if (replacement.field(c.field()).isEmpty()) {
            problems.add("check: the type has no field '" + c.field() + "'");
          }
        }
        default -> {}
      }
    }
  }

  /** The value type of a stored value when the type it was stored under is not known. */
  private static ValueType inferred(Object v) {
    if (v instanceof Boolean) {
      return ValueType.BOOL;
    }
    if (v instanceof Double || v instanceof Float) {
      return ValueType.DOUBLE;
    }
    if (v instanceof Number) {
      return ValueType.INT;
    }
    return ValueType.STRING;
  }

  /**
   * Applies the steps to the values of one entity.
   *
   * @param old the version the entity was made under, or null if it is not known (then a conversion
   *     goes by the kind of the stored value)
   */
  static Map<String, Object> apply(
      Map<String, Object> values, List<EntityMigration> steps, EntityType old)
      throws EntityException {
    Map<String, Object> out = new LinkedHashMap<>(values);
    Map<String, ValueType> types = new LinkedHashMap<>();
    if (old != null) {
      old.fields().forEach(f -> types.put(f.name(), f.type()));
    }
    for (EntityMigration step : steps) {
      switch (step) {
        case Rename r -> {
          if (out.containsKey(r.from())) {
            out.put(r.to(), out.remove(r.from()));
          }
          types.put(r.to(), types.remove(r.from()));
        }
        case Drop d -> {
          out.remove(d.field());
          types.remove(d.field());
        }
        case Default d -> {
          if (out.get(d.field()) == null) {
            out.put(d.field(), d.value());
          }
        }
        case Convert c -> {
          Object v = out.get(c.field());
          if (v != null) {
            ValueType from = types.containsKey(c.field()) ? types.get(c.field()) : inferred(v);
            if (!CONVERSIONS.contains(from.id() + ">" + c.to().id()) && from != c.to()) {
              throw new EntityException(
                  "the value "
                      + v
                      + " of '"
                      + c.field()
                      + "' is a "
                      + from.id()
                      + " and cannot be a "
                      + c.to().id());
            }
            out.put(c.field(), from == c.to() ? v : convert(c.field(), v, from, c.to()));
          }
          types.put(c.field(), c.to());
        }
        case Check c -> {
          // checked with the whole entity afterwards
        }
      }
    }
    return out;
  }

  private static Object convert(String field, Object v, ValueType from, ValueType to)
      throws EntityException {
    String why = "the value " + v + " of '" + field + "' cannot be a " + to.id();
    try {
      return switch (from.id() + ">" + to.id()) {
        case "string>int" -> Long.parseLong(((String) v).strip());
        case "string>double" -> Double.parseDouble(((String) v).strip());
        case "string>bool" -> {
          String b = ((String) v).strip().toLowerCase(java.util.Locale.ROOT);
          if (!b.equals("true") && !b.equals("false")) {
            throw new EntityException(why);
          }
          yield Boolean.parseBoolean(b);
        }
        case "string>timestamp" -> Instant.parse(((String) v).strip());
        case "int>double" -> ((Number) v).doubleValue();
        case "double>int" -> {
          double d = ((Number) v).doubleValue();
          if (d != Math.rint(d) || Math.abs(d) > 9.0e15) {
            throw new EntityException(why);
          }
          yield (long) d;
        }
        case "timestamp>string" -> EntityValues.time((Instant) v);
        default -> String.valueOf(v); // int, double and bool to string
      };
    } catch (NumberFormatException | DateTimeParseException e) {
      throw new EntityException(why);
    }
  }
}
