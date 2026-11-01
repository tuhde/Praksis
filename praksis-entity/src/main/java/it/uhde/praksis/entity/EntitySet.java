// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Table;
import it.uhde.praksis.ruleset.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A named group of entity types, and of other sets, so that a grant or a storage can name the group
 * once. A set is a file {@code entity-sets/<id>.toml} of a layer:
 *
 * <pre>
 * format_version = 1
 * id = "people"
 * title = "People"
 * types = ["contact", "author"]
 * sets = ["staff"]                 # sets inside this one
 * </pre>
 *
 * A set holds no rights of its own: a grant says what may be done with the types of a set, and a
 * type is in as many sets as name it. The members of a set are its types and the types of the sets
 * inside it, to any depth up to {@link #MAX_DEPTH}. A higher layer may replace a set of a lower one
 * by naming its hash in {@code based_on}; what a replacement adds is new access for the grants that
 * name the set, which the verifier reports.
 *
 * @param id the id, which is the file name without {@code .toml}
 * @param title what a person sees
 * @param description what it is for, or empty
 * @param types the ids of the types in the set
 * @param sets the ids of the sets inside the set
 * @param basedOn for a replacement, the hash of the set that it replaces
 * @param raw the set as the plain values of its file, which the hash is made of
 */
public record EntitySet(
    String id,
    String title,
    Optional<String> description,
    List<String> types,
    List<String> sets,
    Optional<String> basedOn,
    Map<String, Object> raw) {
  /** The version of the file format that this code reads. */
  public static final int SUPPORTED_FORMAT = 1;

  /** How deep sets may be inside sets. */
  public static final int MAX_DEPTH = 8;

  private static final Set<String> KEYS =
      Set.of("format_version", "id", "title", "description", "based_on", "types", "sets");

  /** {@code sha256:<hex>} of the set's content, without {@code based_on}. */
  public String hash() {
    return View.hashOf(raw);
  }

  /** Reads a set file; problems are recorded in the list. */
  static Optional<EntitySet> parse(
      Table t, List<Problem> problems, String fileId, Map<String, Object> raw) {
    int before = problems.size();
    t.onlyKeys(KEYS);
    Object version = raw.get("format_version");
    if (!(version instanceof Number n) || n.intValue() != SUPPORTED_FORMAT) {
      t.problem("format_version must be " + SUPPORTED_FORMAT);
    }
    String id = t.string("id", true).orElse(fileId);
    if (!EntityType.ID.matcher(id).matches()) {
      t.problem("id '" + id + "' must be lower case words joined by '.' (letters, digits, '_')");
    } else if (!id.equals(fileId)) {
      t.problem("id '" + id + "' must be the file name '" + fileId + "'");
    }
    String title = t.string("title", true).orElse(id);
    List<String> types = names(t, "types");
    List<String> sets = names(t, "sets");
    if (types.isEmpty() && sets.isEmpty()) {
      t.problem("a set needs at least one entry in 'types' or 'sets'");
    }
    if (sets.contains(id)) {
      t.problem("a set cannot be inside itself");
    }
    if (problems.size() > before) {
      return Optional.empty();
    }
    return Optional.of(
        new EntitySet(
            id,
            title,
            t.string("description", false),
            List.copyOf(types),
            List.copyOf(sets),
            t.string("based_on", false),
            raw));
  }

  private static List<String> names(Table t, String key) {
    List<String> out = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (String name : t.strings(key)) {
      if (!EntityType.ID.matcher(name).matches()) {
        t.problem("'" + key + "' has '" + name + "', which is not an id");
      } else if (!seen.add(name)) {
        t.problem("'" + key + "' has '" + name + "' twice");
      } else {
        out.add(name);
      }
    }
    return out;
  }
}
