// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.git.GitException;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.Table;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The ruleset layers of a home directory: which commit each git layer is pinned to ({@code
 * rulesets.lock}), where the files of that commit are, and the combined ruleset.
 *
 * <p>Starting the daemon never asks a server anything when the pinned commits are on disk. Only
 * {@link #update} talks to the repositories, and it moves a pin only if the ruleset that results is
 * valid.
 */
final class Layers {
  /** What a git layer is pinned to. */
  record Pin(String git, String ref, String commit) {}

  /**
   * The outcome of {@link #update}.
   *
   * @param applied whether the pins were written
   * @param report what changed, line by line
   * @param problems why nothing was changed, if that is so
   */
  record Update(boolean applied, List<String> report, List<Problem> problems) {}

  private final DaemonConfig config;
  private final GitSource git;

  Layers(DaemonConfig config, GitSource git) {
    this.config = config;
    this.git = git;
  }

  /** The ruleset to run, from the pinned commits and the directories. */
  LayerStack.Effective effective() throws DaemonException, RulesetException {
    return LayerStack.resolve(sources(readLock()), config.params());
  }

  /** The directories of all layers, bottom to top, for the given pins. */
  List<LayerStack.Source> sources(Map<String, Pin> pins) throws DaemonException {
    if (config.layers().isEmpty()) {
      return List.of(new LayerStack.Source("ruleset", config.ruleset()));
    }
    List<LayerStack.Source> sources = new ArrayList<>();
    for (LayerConfig layer : config.layers()) {
      if (!layer.isGit()) {
        sources.add(new LayerStack.Source(layer.name(), layer.path().orElseThrow()));
        continue;
      }
      Pin pin = pins.get(layer.name());
      if (pin == null) {
        throw new DaemonException(
            "layer '"
                + layer.name()
                + "' is not pinned to a commit yet. Run: praksis layers update");
      }
      if (!pin.git().equals(layer.git().get()) || !pin.ref().equals(layer.ref().get())) {
        throw new DaemonException(
            "layer '"
                + layer.name()
                + "' is configured as "
                + layer.git().get()
                + " @ "
                + layer.ref().get()
                + " but pinned as "
                + pin.git()
                + " @ "
                + pin.ref()
                + ". Run: praksis layers update "
                + layer.name());
      }
      Path snapshot = config.snapshots().resolve(layer.name()).resolve(pin.commit());
      try {
        git.export(layer.name(), pin.git(), pin.commit(), snapshot);
      } catch (GitException e) {
        throw new DaemonException(
            "layer '"
                + layer.name()
                + "': cannot get commit "
                + pin.commit()
                + ": "
                + e.getMessage(),
            e);
      }
      sources.add(new LayerStack.Source(layer.name(), snapshot));
    }
    return sources;
  }

  Map<String, Pin> readLock() throws DaemonException {
    Path file = config.rulesetLock();
    Map<String, Pin> pins = new LinkedHashMap<>();
    if (!Files.exists(file)) {
      return pins;
    }
    Map<String, Object> values;
    try {
      values = new TomlMapper().readValue(file.toFile(), new TypeReference<>() {});
    } catch (IOException e) {
      throw new DaemonException("cannot read " + file + ": " + e.getMessage(), e);
    }
    List<Problem> problems = new ArrayList<>();
    Table t = new Table(values, "rulesets.lock", problems);
    t.onlyKeys(Set.of("format_version", "layer"));
    t.table("layer", false)
        .ifPresent(
            layers ->
                layers
                    .subTables()
                    .forEach(
                        (name, p) -> {
                          p.onlyKeys(Set.of("git", "ref", "commit"));
                          var git = p.string("git", true);
                          var ref = p.string("ref", true);
                          var commit = p.string("commit", true);
                          if (git.isPresent() && ref.isPresent() && commit.isPresent()) {
                            pins.put(name, new Pin(git.get(), ref.get(), commit.get()));
                          }
                        }));
    if (!problems.isEmpty()) {
      throw new DaemonException(
          "invalid "
              + file
              + ":\n  "
              + String.join("\n  ", problems.stream().map(Problem::toString).toList()));
    }
    return pins;
  }

  private void writeLock(Map<String, Pin> pins) throws DaemonException {
    StringBuilder text =
        new StringBuilder(
            "# Written by 'praksis layers update': the commit every git layer is pinned to.\n"
                + "format_version = 1\n");
    new TreeMap<>(pins)
        .forEach(
            (name, pin) ->
                text.append("\n[layer.")
                    .append(name.matches("[A-Za-z0-9_-]+") ? name : quote(name))
                    .append("]\ngit = ")
                    .append(quote(pin.git()))
                    .append("\nref = ")
                    .append(quote(pin.ref()))
                    .append("\ncommit = ")
                    .append(quote(pin.commit()))
                    .append("\n"));
    Path file = config.rulesetLock();
    try {
      Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), ".rulesets-", ".tmp");
      Files.writeString(temp, text.toString());
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new DaemonException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  private static String quote(String value) {
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }

  /**
   * Moves git layers to the latest commit of their ref, if the ruleset that results is valid.
   *
   * @param names the layers to move; empty means all git layers
   * @param dryRun whether to only report what would happen
   * @param validator checks the combined ruleset against the modules; returns the problems
   */
  Update update(Set<String> names, boolean dryRun, Function<Ruleset, List<Problem>> validator)
      throws DaemonException {
    Set<String> gitLayers = new LinkedHashSet<>();
    config.layers().stream().filter(LayerConfig::isGit).forEach(l -> gitLayers.add(l.name()));
    for (String name : names) {
      if (!gitLayers.contains(name)) {
        throw new DaemonException("there is no git layer named '" + name + "'");
      }
    }
    if (gitLayers.isEmpty()) {
      throw new DaemonException("no layer comes from git; nothing to update");
    }

    Map<String, Pin> old = readLock();
    LayerStack.Effective before = null;
    try {
      before = effective();
    } catch (DaemonException | RulesetException e) {
      // there is nothing to compare with, e.g. the first time
    }

    Map<String, Pin> next = new LinkedHashMap<>();
    for (LayerConfig layer : config.layers()) {
      if (!layer.isGit()) {
        continue;
      }
      Pin pin = old.get(layer.name());
      boolean move = names.isEmpty() || names.contains(layer.name()) || pin == null;
      if (!move && (!pin.git().equals(layer.git().get()) || !pin.ref().equals(layer.ref().get()))) {
        move = true; // the configuration changed, so the old pin means nothing
      }
      if (move) {
        try {
          String commit = git.resolve(layer.name(), layer.git().get(), layer.ref().get());
          pin = new Pin(layer.git().get(), layer.ref().get(), commit);
        } catch (GitException e) {
          throw new DaemonException("layer '" + layer.name() + "': " + e.getMessage(), e);
        }
      }
      next.put(layer.name(), pin);
    }

    LayerStack.Effective after;
    try {
      after = LayerStack.resolve(sources(next), config.params());
    } catch (RulesetException e) {
      return new Update(false, List.of(), e.problems());
    }
    List<Problem> invalid = validator.apply(after.ruleset());
    if (!invalid.isEmpty()) {
      return new Update(false, List.of(), invalid);
    }

    List<String> report = new ArrayList<>();
    for (var e : next.entrySet()) {
      Pin was = old.get(e.getKey());
      Pin now = e.getValue();
      if (was == null) {
        report.add(e.getKey() + ": pinned to " + abbreviate(now.commit()) + " (" + now.ref() + ")");
      } else if (was.commit().equals(now.commit())) {
        report.add(e.getKey() + ": unchanged at " + abbreviate(now.commit()));
      } else {
        report.add(
            e.getKey()
                + ": "
                + abbreviate(was.commit())
                + " -> "
                + abbreviate(now.commit())
                + " ("
                + now.ref()
                + ")");
      }
    }
    report.addAll(ruleChanges(before, after));
    report.add("The new ruleset is valid (" + after.ruleset().rules().size() + " rules).");
    if (dryRun) {
      report.add("Dry run: the pins were not changed.");
    } else if (next.equals(old)) {
      report.add("Nothing to change.");
    } else {
      writeLock(next);
      report.add(
          "The pins were written to "
              + config.rulesetLock().getFileName()
              + ". Restart the daemon to use them.");
    }
    return new Update(!dryRun, report, List.of());
  }

  private static List<String> ruleChanges(LayerStack.Effective before, LayerStack.Effective after) {
    if (before == null) {
      return List.of();
    }
    Map<String, String> was = new TreeMap<>();
    before.origins().forEach(o -> was.put(o.ruleId(), o.hash()));
    Map<String, LayerStack.Origin> is = new TreeMap<>();
    after.origins().forEach(o -> is.put(o.ruleId(), o));
    List<String> lines = new ArrayList<>();
    for (var e : is.entrySet()) {
      String old = was.get(e.getKey());
      if (old == null) {
        lines.add("  + " + e.getKey() + " (from " + e.getValue().layer() + ")");
      } else if (!old.equals(e.getValue().hash())) {
        lines.add("  ~ " + e.getKey() + " changed");
      }
    }
    for (String id : was.keySet()) {
      if (!is.containsKey(id)) {
        lines.add("  - " + id + " removed");
      }
    }
    return lines;
  }

  static String abbreviate(String commit) {
    return commit.length() > 10 ? commit.substring(0, 10) : commit;
  }
}
