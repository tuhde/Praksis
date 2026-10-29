// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.ruleset.LayerOps.Change;
import it.uhde.praksis.ruleset.LayerOps.Disable;
import it.uhde.praksis.ruleset.LayerOps.Patch;
import it.uhde.praksis.ruleset.LayerOps.Replace;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * One layer of a ruleset, read from a directory:
 *
 * <pre>
 * ruleset.toml            format_version, name, optional [param.*]
 * rules/&lt;id&gt;.toml        adds a rule; the id must not exist in the layers below
 * replace/&lt;id&gt;.toml     replaces a rule completely; a normal rule file plus based_on
 * patch/&lt;id&gt;.toml       changes single fields: [[change]] with path, expect and value or remove
 * disable/&lt;id&gt;.toml     turns a rule off: based_on and an optional reason
 * </pre>
 *
 * A directory with only {@code rules/} is a plain ruleset. Reading checks the structure only;
 * whether the targets exist and are unchanged is checked when the layers are put together, see
 * {@link LayerStack}.
 *
 * @param name the name of the layer in the local configuration
 * @param header the {@code ruleset.toml} content: format version, name and parameters
 * @param adds the rules this layer adds
 * @param replaces the rules this layer replaces
 * @param patches the rules this layer changes
 * @param disables the rules this layer turns off
 * @param views the views this layer adds, or replaces by naming the hash of the view below
 */
public record Layer(
    String name,
    Ruleset header,
    List<Rule> adds,
    List<Replace> replaces,
    List<Patch> patches,
    List<Disable> disables,
    List<View> views) {
  private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> DIRECTORIES =
      Set.of("rules", "replace", "patch", "disable", "views", "tests", "docs");

  public Layer {
    adds = List.copyOf(adds);
    replaces = List.copyOf(replaces);
    patches = List.copyOf(patches);
    disables = List.copyOf(disables);
    views = List.copyOf(views);
  }

  /** Reads the layer in the directory, or throws with every structural problem found. */
  public static Layer load(String name, Path directory) throws RulesetException {
    List<Problem> problems = new ArrayList<>();
    Ruleset header = null;
    try {
      header = RulesetLoader.load(directory);
    } catch (RulesetException e) {
      problems.addAll(e.problems());
    }
    List<Replace> replaces = new ArrayList<>();
    List<Patch> patches = new ArrayList<>();
    List<Disable> disables = new ArrayList<>();

    for (Path file : files(directory.resolve("replace"), problems)) {
      replace(file, problems).ifPresent(replaces::add);
    }
    for (Path file : files(directory.resolve("patch"), problems)) {
      patch(file, problems).ifPresent(patches::add);
    }
    for (Path file : files(directory.resolve("disable"), problems)) {
      disable(file, problems).ifPresent(disables::add);
    }
    List<View> views = new ArrayList<>();
    for (Path file : files(directory.resolve("views"), problems)) {
      view(file, problems).ifPresent(views::add);
    }
    try (Stream<Path> entries = Files.list(directory)) {
      for (Path entry : entries.sorted().toList()) {
        String entryName = entry.getFileName().toString();
        if (Files.isDirectory(entry)
            && !entryName.startsWith(".")
            && !DIRECTORIES.contains(entryName)) {
          problems.add(
              new Problem(
                  entryName + "/",
                  "unknown directory; a layer has rules, replace, patch, disable and views"));
        }
      }
    } catch (IOException e) {
      problems.add(new Problem(directory.toString(), "cannot list: " + e.getMessage()));
    }

    if (!problems.isEmpty()) {
      throw new RulesetException(
          problems.stream()
              .map(p -> new Problem("layer '" + name + "': " + p.location(), p.message()))
              .toList());
    }
    return new Layer(name, header, header.rules(), replaces, patches, disables, views);
  }

  private static List<Path> files(Path dir, List<Problem> problems) {
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(dir)) {
      return files.filter(f -> f.toString().endsWith(".toml")).sorted().toList();
    } catch (IOException e) {
      problems.add(new Problem(dir.getFileName().toString(), "cannot list: " + e.getMessage()));
      return List.of();
    }
  }

  private static String id(Path file) {
    return file.getFileName().toString().replaceFirst("\\.toml$", "");
  }

  private static String where(Path file) {
    return file.getParent().getFileName() + "/" + file.getFileName();
  }

  private static Optional<String> basedOn(Table t, boolean required) {
    Optional<String> hash = t.string("based_on", required);
    if (hash.isPresent() && !HASH.matcher(hash.get()).matches()) {
      t.problem("'based_on' must look like sha256:<64 hex digits>, see the effective view");
      return Optional.empty();
    }
    return hash;
  }

  private static Optional<View> view(Path file, List<Problem> problems) {
    Table t = RulesetLoader.read(file, where(file), problems);
    if (t == null) {
      return Optional.empty();
    }
    return View.parse(t, id(file), t.rawEntries());
  }

  private static Optional<Replace> replace(Path file, List<Problem> problems) {
    Table t = RulesetLoader.read(file, where(file), problems);
    if (t == null) {
      return Optional.empty();
    }
    Optional<String> basedOn = basedOn(t, true);
    Map<String, Object> rest = new java.util.LinkedHashMap<>(t.rawEntries());
    rest.remove("based_on");
    Optional<Rule> rule = RulesetLoader.rule(new Table(rest, where(file), problems), id(file));
    if (basedOn.isEmpty() || rule.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Replace(rule.get(), basedOn.get()));
  }

  private static Optional<Disable> disable(Path file, List<Problem> problems) {
    Table t = RulesetLoader.read(file, where(file), problems);
    if (t == null) {
      return Optional.empty();
    }
    t.onlyKeys(Set.of("based_on", "reason"));
    Optional<String> basedOn = basedOn(t, true);
    Optional<String> reason = t.string("reason", false);
    return basedOn.map(h -> new Disable(id(file), h, reason));
  }

  private static Optional<Patch> patch(Path file, List<Problem> problems) {
    Table t = RulesetLoader.read(file, where(file), problems);
    if (t == null) {
      return Optional.empty();
    }
    t.onlyKeys(Set.of("based_on", "change"));
    Optional<String> basedOn = basedOn(t, false);
    List<Change> changes = new ArrayList<>();
    for (Table c : t.tables("change")) {
      c.onlyKeys(Set.of("path", "expect", "value", "remove"));
      Optional<String> path = c.string("path", true);
      boolean remove = c.bool("remove", false);
      if (remove == c.has("value")) {
        c.problem("give exactly one of 'value' and 'remove = true'");
      }
      path.ifPresent(
          p ->
              changes.add(
                  new Change(
                      p,
                      c.has("expect"),
                      c.raw("expect").orElse(null),
                      remove,
                      c.raw("value").orElse(null))));
    }
    if (changes.isEmpty() && !t.has("change")) {
      t.problem("a patch needs at least one [[change]]");
    }
    return Optional.of(new Patch(id(file), basedOn, changes));
  }
}
