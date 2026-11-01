// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Table;
import it.uhde.praksis.ruleset.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A kind of data that Praksis manages: a name and a list of typed fields. A type is a file {@code
 * entities/<id>.toml} of a module or a layer.
 *
 * <pre>
 * format_version = 1
 * id = "contact"
 * title = "Contact"
 *
 * [[field]]
 * name = "email"
 * type = "string"
 * required = true
 * unique = true
 * </pre>
 *
 * Every entity also has the fields {@code id}, {@code created}, {@code updated}, {@code created_by}
 * and {@code updated_by} (who made the change: {@code rule:<id>} or {@code user}), which Praksis
 * sets and which a type cannot declare. A higher layer may replace a type of a lower one by naming
 * its hash in {@code based_on}.
 *
 * @param id the id, which is the file name without {@code .toml}
 * @param title what a person sees
 * @param description what it is for, or empty
 * @param fields the declared fields, in order
 * @param basedOn for a replacement, the hash of the type that it replaces
 * @param raw the type as the plain values of its file, which the hash is made of
 * @param migrations for a replacement, the steps that turn the entities of the type below into
 *     entities of this one; see {@link EntityMigration}
 */
public record EntityType(
    String id,
    String title,
    Optional<String> description,
    List<Field> fields,
    Optional<String> basedOn,
    Map<String, Object> raw,
    List<EntityMigration> migrations) {

  /** The version of the file format that this code reads. */
  public static final int SUPPORTED_FORMAT = 1;

  /** Fields that Praksis sets on every entity. */
  public static final Set<String> RESERVED =
      Set.of("id", "created", "updated", "created_by", "updated_by");

  static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
  static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]*");

  private static final Set<String> KEYS =
      Set.of(
          "format_version",
          "id",
          "title",
          "description",
          "based_on",
          "migrates_from",
          "field",
          "migration");
  private static final Set<String> FIELD_KEYS =
      Set.of("name", "type", "required", "unique", "sensitive", "description", "ref", "on_delete");
  private static final Set<ValueType> NOT_UNIQUE =
      Set.of(ValueType.LIST, ValueType.MAP, ValueType.BYTES);

  /**
   * A declared field.
   *
   * @param name the name, used in rules, queries and views
   * @param type the type of its values
   * @param required whether a value must be given on create
   * @param unique whether no two entities of the type may have the same value
   * @param sensitive whether the field is left out of what rules read (a rule needs {@code
   *     entity.reveal} and a grant for it), of filters and sorting, and of the audit log
   * @param description what it is for, or empty
   * @param ref the type that the field refers to, or empty: the value is the id of an entity of
   *     that type (a {@code string} field), or a list of such ids (a {@code list} field)
   * @param onDelete what happens to an entity with this field when the entity that it refers to is
   *     deleted
   */
  public record Field(
      String name,
      ValueType type,
      boolean required,
      boolean unique,
      boolean sensitive,
      Optional<String> description,
      Optional<String> ref,
      OnDelete onDelete) {
    /** A field that refers to nothing. */
    public Field(
        String name,
        ValueType type,
        boolean required,
        boolean unique,
        boolean sensitive,
        Optional<String> description) {
      this(
          name,
          type,
          required,
          unique,
          sensitive,
          description,
          Optional.empty(),
          OnDelete.RESTRICT);
    }
  }

  /** What happens to the entities that refer to an entity that is deleted. */
  public enum OnDelete {
    /** The deletion is refused while an entity refers to it. */
    RESTRICT,
    /** The entities that refer to it are deleted too (not for a list of references). */
    CASCADE,
    /** The reference is removed from them: the field is cleared, or the id leaves the list. */
    SET_NULL;

    /** The name in a type file, e.g. {@code set_null}. */
    public String id() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }

    static Optional<OnDelete> fromId(String id) {
      return java.util.Arrays.stream(values()).filter(o -> o.id().equals(id)).findFirst();
    }
  }

  /** The field with the given name, or empty. */
  public Optional<Field> field(String name) {
    return fields.stream().filter(f -> f.name().equals(name)).findFirst();
  }

  /**
   * The hash of the version of the type whose entities the {@code [[migration]]} steps turn into
   * entities of this one: {@code migrates_from}, else {@code based_on}.
   */
  public Optional<String> migratesFrom() {
    return raw.get("migrates_from") instanceof String s ? Optional.of(s) : basedOn;
  }

  /** {@code sha256:<hex>} of the type's content, without {@code based_on}. */
  public String hash() {
    return View.hashOf(raw);
  }

  /** Reads a type file; problems are recorded in the table. */
  static Optional<EntityType> parse(
      Table t, List<Problem> problems, String fileId, Map<String, Object> raw) {
    int before = problems.size();
    t.onlyKeys(KEYS);
    Object version = raw.get("format_version");
    if (!(version instanceof Number n) || n.intValue() != SUPPORTED_FORMAT) {
      t.problem("format_version must be " + SUPPORTED_FORMAT);
    }
    String id = t.string("id", true).orElse(fileId);
    if (!ID.matcher(id).matches()) {
      t.problem("id '" + id + "' must be lower case words joined by '.' (letters, digits, '_')");
    } else if (!id.equals(fileId)) {
      t.problem("id '" + id + "' must be the file name '" + fileId + "'");
    }
    String title = t.string("title", true).orElse(id);
    Optional<String> description = t.string("description", false);
    Optional<String> basedOn = t.string("based_on", false);

    List<Field> fields = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Table f : t.tables("field")) {
      f.onlyKeys(FIELD_KEYS);
      String name = f.string("name", true).orElse("");
      Optional<ValueType> type = f.string("type", true).flatMap(ValueType::fromId);
      if (f.has("type") && type.isEmpty() && f.raw("type").orElse("") instanceof String s) {
        f.problem("unknown type '" + s + "'");
      }
      if (!name.isEmpty() && !FIELD.matcher(name).matches()) {
        f.problem("field name '" + name + "' must be lower case letters, digits and '_'");
      } else if (RESERVED.contains(name)) {
        f.problem("field '" + name + "' is set by Praksis and cannot be declared");
      } else if (!name.isEmpty() && !seen.add(name)) {
        f.problem("field '" + name + "' is declared twice");
      }
      boolean unique = f.bool("unique", false);
      if (unique && type.filter(NOT_UNIQUE::contains).isPresent()) {
        f.problem("field '" + name + "' cannot be unique, its type is " + type.get().id());
      }
      Optional<String> ref = f.string("ref", false);
      OnDelete onDelete = OnDelete.RESTRICT;
      boolean required = f.bool("required", false);
      if (ref.isPresent()) {
        if (!ID.matcher(ref.get()).matches()) {
          f.problem("'ref' must be the id of an entity type, not '" + ref.get() + "'");
        }
        if (type.isPresent() && type.get() != ValueType.STRING && type.get() != ValueType.LIST) {
          f.problem(
              "field '"
                  + name
                  + "' cannot refer to a type, its type is "
                  + type.get().id()
                  + " (a string holds one id, a list holds several)");
        }
      }
      if (f.has("on_delete")) {
        Optional<String> od = f.string("on_delete", false);
        if (ref.isEmpty()) {
          f.problem("'on_delete' needs 'ref'");
        } else if (od.isPresent()) {
          Optional<OnDelete> parsed = OnDelete.fromId(od.get());
          if (parsed.isEmpty()) {
            f.problem("'on_delete' must be restrict, cascade or set_null, not '" + od.get() + "'");
          } else {
            onDelete = parsed.get();
          }
        }
      }
      if (ref.isPresent() && type.isPresent()) {
        if (onDelete == OnDelete.CASCADE && type.get() == ValueType.LIST) {
          f.problem("a list of references cannot cascade: use restrict or set_null");
        }
        if (onDelete == OnDelete.SET_NULL && required && type.get() == ValueType.STRING) {
          f.problem("a required reference cannot be set_null: use restrict or cascade");
        }
      }
      if (type.isPresent() && !name.isEmpty()) {
        fields.add(
            new Field(
                name,
                type.get(),
                required,
                unique,
                f.bool("sensitive", false),
                f.string("description", false),
                ref,
                onDelete));
      }
    }
    if (!t.has("field")) {
      t.problem("a type needs at least one [[field]]");
    }
    if (problems.size() > before) {
      return Optional.empty();
    }
    List<EntityMigration> migrations = EntityMigration.parse(t);
    if (!migrations.isEmpty() && basedOn.isEmpty() && t.string("migrates_from", false).isEmpty()) {
      t.problem(
          "'migration' steps need to know which version they start from: name it in"
              + " 'migrates_from' (or 'based_on' for a replacement of a type below)");
      return Optional.empty();
    }
    if (problems.size() > before) {
      return Optional.empty();
    }
    return Optional.of(
        new EntityType(id, title, description, List.copyOf(fields), basedOn, raw, migrations));
  }
}
