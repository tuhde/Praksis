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
 * Every entity also has the fields {@code id}, {@code created} and {@code updated}, which Praksis
 * sets and which a type cannot declare. A higher layer may replace a type of a lower one by naming
 * its hash in {@code based_on}.
 *
 * @param id the id, which is the file name without {@code .toml}
 * @param title what a person sees
 * @param description what it is for, or empty
 * @param fields the declared fields, in order
 * @param basedOn for a replacement, the hash of the type that it replaces
 * @param raw the type as the plain values of its file, which the hash is made of
 */
public record EntityType(
    String id,
    String title,
    Optional<String> description,
    List<Field> fields,
    Optional<String> basedOn,
    Map<String, Object> raw) {

  /** The version of the file format that this code reads. */
  public static final int SUPPORTED_FORMAT = 1;

  /** Fields that Praksis sets on every entity. */
  public static final Set<String> RESERVED = Set.of("id", "created", "updated");

  static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*");
  static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]*");

  private static final Set<String> KEYS =
      Set.of("format_version", "id", "title", "description", "based_on", "field");
  private static final Set<String> FIELD_KEYS =
      Set.of("name", "type", "required", "unique", "sensitive", "description");
  private static final Set<ValueType> NOT_UNIQUE =
      Set.of(ValueType.LIST, ValueType.MAP, ValueType.BYTES);

  /**
   * A declared field.
   *
   * @param name the name, used in rules, queries and views
   * @param type the type of its values
   * @param required whether a value must be given on create
   * @param unique whether no two entities of the type may have the same value
   * @param sensitive whether grants can limit the field and views show it only on request
   * @param description what it is for, or empty
   */
  public record Field(
      String name,
      ValueType type,
      boolean required,
      boolean unique,
      boolean sensitive,
      Optional<String> description) {}

  /** The field with the given name, or empty. */
  public Optional<Field> field(String name) {
    return fields.stream().filter(f -> f.name().equals(name)).findFirst();
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
      if (type.isPresent() && !name.isEmpty()) {
        fields.add(
            new Field(
                name,
                type.get(),
                f.bool("required", false),
                unique,
                f.bool("sensitive", false),
                f.string("description", false)));
      }
    }
    if (!t.has("field")) {
      t.problem("a type needs at least one [[field]]");
    }
    if (problems.size() > before) {
      return Optional.empty();
    }
    return Optional.of(new EntityType(id, title, description, List.copyOf(fields), basedOn, raw));
  }
}
