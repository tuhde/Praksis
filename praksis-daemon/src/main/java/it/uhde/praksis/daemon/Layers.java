// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.git.GitException;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Permissions;
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
import java.util.Optional;
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
   * @param refused whether the update was stopped because it gives rules access that no grant
   *     covers; the report then holds the permission diff
   */
  record Update(boolean applied, List<String> report, List<Problem> problems, boolean refused) {
    Update(boolean applied, List<String> report, List<Problem> problems) {
      this(applied, report, problems, false);
    }
  }

  /** What a ruleset needs to be allowed to do, and what of that no grant covers. */
  record Access(Permissions.Needs needs, List<Permissions.Requirement> uncovered) {}

  /** Works out the access of a ruleset against the grants in the home directory. */
  @FunctionalInterface
  interface AccessCheck {
    Access of(LayerStack.Effective effective) throws DaemonException;

    /** A check that finds nothing needed, for callers that have no modules or policy at hand. */
    AccessCheck NONE = e -> new Access(new Permissions.Needs(Map.of()), List.of());
  }

  private final DaemonConfig config;
  private final GitSource git;

  /**
   * The modules that a rule or view may ask for; null if they cannot be told (nothing is left out).
   */
  private final java.util.Set<String> modules;

  Layers(DaemonConfig config, GitSource git) {
    this.config = config;
    this.git = git;
    java.util.Set<String> names;
    try {
      names =
          it.uhde.praksis.engine.ModuleLoader.moduleNames(
              config.moduleRoots(), config.enabledModules());
    } catch (it.uhde.praksis.engine.ModuleLoadException e) {
      names = null; // the modules are reported where they are loaded
    }
    this.modules = names;
  }

  /** The ruleset to run, from the pinned commits and the directories. */
  LayerStack.Effective effective() throws DaemonException, RulesetException {
    return LayerStack.resolve(sources(readLock()), config.params(), modules);
  }

  /** The commit every git layer is pinned to, by layer name. */
  Map<String, String> commits() throws DaemonException {
    Map<String, String> out = new LinkedHashMap<>();
    readLock().forEach((name, pin) -> out.put(name, pin.commit()));
    return out;
  }

  /**
   * Who wrote the current version of a rule: the last commit of the owner layer that changed the
   * file with which that layer added, replaced or patched it. Uses the local copy of the repository
   * only.
   */
  Provenance provenance(LayerStack.Origin origin, Map<String, String> commits) {
    String last =
        origin.history().isEmpty() ? "" : origin.history().get(origin.history().size() - 1);
    String operation = last.contains(": ") ? last.substring(last.indexOf(": ") + 2) : "add";
    String directory =
        switch (operation) {
          case "replace" -> "replace";
          case "patch" -> "patch";
          default -> "rules";
        };
    String file = directory + "/" + origin.ruleId() + ".toml";
    Optional<GitSource.Change> change = Optional.empty();
    String commit = commits.get(origin.owner());
    if (commit != null) {
      try {
        change = git.lastChange(origin.owner(), commit, file);
      } catch (GitException e) {
        // no history is not an error
      }
    }
    return new Provenance(origin.owner(), file, change);
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

  /**
   * Pins a git layer to a commit, keeping the other pins. The pin keeps the configured address and
   * ref, so the layer runs this commit until the next {@code layers update} moves it to the latest
   * commit of the ref.
   */
  void pin(String layerName, String commit) throws DaemonException {
    LayerConfig layer =
        config.layers().stream()
            .filter(l -> l.name().equals(layerName) && l.isGit())
            .findFirst()
            .orElseThrow(
                () -> new DaemonException("there is no git layer named '" + layerName + "'"));
    Map<String, Pin> pins = new LinkedHashMap<>(readLock());
    pins.put(layerName, new Pin(layer.git().get(), layer.ref().get(), commit));
    writeLock(pins);
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
   * @param acceptNewAccess whether to go on although rules get access that no grant covers
   * @param validator checks the combined ruleset against the modules; returns the problems
   * @param access works out what the rulesets need and which of that is not granted
   */
  Update update(
      Set<String> names,
      boolean dryRun,
      boolean acceptNewAccess,
      Function<Ruleset, List<Problem>> validator,
      AccessCheck access)
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

    Map<String, Pin> next = nextPins(names, old);

    LayerStack.Effective after;
    try {
      after = LayerStack.resolve(sources(next), config.params(), modules);
    } catch (RulesetException e) {
      return new Update(false, List.of(), e.problems());
    }
    List<Problem> invalid = validator.apply(after.ruleset());
    if (!invalid.isEmpty()) {
      return new Update(false, List.of(), invalid);
    }

    List<String> report = new ArrayList<>();
    List<String> accessLines = new ArrayList<>();
    boolean refused = false;
    if (before != null) {
      Access needed = access.of(after);
      Permissions.Diff diff = Permissions.diff(access.of(before).needs(), needed.needs());
      List<Permissions.Requirement> ungranted = needed.uncovered();
      List<Permissions.Requirement> newlyUngranted =
          diff.added().stream().filter(ungranted::contains).toList();
      if (!diff.isEmpty()) {
        accessLines.add("Permissions the rules need, compared with now:");
        diff.added()
            .forEach(
                r ->
                    accessLines.add(
                        "  + "
                            + r.describe()
                            + (newlyUngranted.contains(r) ? "   NOT GRANTED" : "   granted")));
        diff.removed().forEach(r -> accessLines.add("  - " + r.describe()));
      }
      refused = !newlyUngranted.isEmpty() && !acceptNewAccess;
      if (refused) {
        accessLines.add(
            "Not updated: "
                + newlyUngranted.size()
                + " new kind(s) of access are not covered by a grant. Add grants for them, or run"
                + " the update again with --accept-new-access (the rules then run, but each call"
                + " is denied until it is granted).");
        accessLines.add("The pins are unchanged.");
        return new Update(false, accessLines, List.of(), true);
      }
    }
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
    report.addAll(accessLines);
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

  /** The pins after moving the named git layers (all if none are named) to the latest commit. */
  private Map<String, Pin> nextPins(Set<String> names, Map<String, Pin> old)
      throws DaemonException {
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
    return next;
  }

  /**
   * A ruleset that is not the one in use, put together for checking.
   *
   * @param sources the layers, bottom to top
   * @param effective the ruleset they give
   * @param commits the commit of each layer that comes from git, to look up who wrote what
   */
  record Candidate(
      List<LayerStack.Source> sources,
      LayerStack.Effective effective,
      Map<String, String> commits) {}

  /**
   * The ruleset that would run if the pins moved to the latest commits ({@code update}) and/or
   * layers were replaced.
   *
   * @param replacements layer name to a directory, or to {@code @<ref>} for a branch or tag of the
   *     layer's own repository
   * @param update whether the git layers move to the latest commit of their ref first
   */
  Candidate candidate(Map<String, String> replacements, boolean update)
      throws DaemonException, RulesetException {
    Map<String, Pin> pins = readLock();
    if (update) {
      pins = nextPins(Set.of(), pins);
    }
    List<LayerStack.Source> sources = new ArrayList<>();
    Set<String> known = new LinkedHashSet<>();
    Map<String, String> commits = new LinkedHashMap<>();
    pins.forEach((name, pin) -> commits.put(name, pin.commit()));
    for (LayerStack.Source source : sources(pins)) {
      known.add(source.name());
      String replacement = replacements.get(source.name());
      if (replacement == null) {
        sources.add(source);
        continue;
      }
      Replaced replaced = replace(source, replacement);
      sources.add(replaced.source());
      commits.remove(source.name());
      replaced.commit().ifPresent(c -> commits.put(source.name(), c));
    }
    for (String name : replacements.keySet()) {
      if (!known.contains(name)) {
        throw new DaemonException("there is no layer named '" + name + "'");
      }
    }
    return new Candidate(
        List.copyOf(sources), LayerStack.resolve(sources, config.params(), modules), commits);
  }

  /** A layer put in place of another: its files, and the commit if it came from git. */
  private record Replaced(LayerStack.Source source, Optional<String> commit) {}

  private Replaced replace(LayerStack.Source source, String replacement) throws DaemonException {
    if (!replacement.startsWith("@")) {
      Path directory = Path.of(replacement).toAbsolutePath();
      if (!Files.isDirectory(directory)) {
        throw new DaemonException(
            "layer '" + source.name() + "': " + replacement + " is not a directory");
      }
      return new Replaced(new LayerStack.Source(source.name(), directory), Optional.empty());
    }
    LayerConfig layer =
        config.layers().stream()
            .filter(l -> l.name().equals(source.name()))
            .findFirst()
            .orElseThrow(
                () ->
                    new DaemonException(
                        "layer '" + source.name() + "' has no repository to take a ref from"));
    if (!layer.isGit()) {
      throw new DaemonException(
          "layer '" + source.name() + "' is a directory; give a directory to replace it");
    }
    String ref = replacement.substring(1);
    try {
      String commit = git.resolve(layer.name(), layer.git().get(), ref);
      Path snapshot = config.snapshots().resolve(layer.name()).resolve(commit);
      git.export(layer.name(), layer.git().get(), commit, snapshot);
      return new Replaced(new LayerStack.Source(layer.name(), snapshot), Optional.of(commit));
    } catch (GitException e) {
      throw new DaemonException("layer '" + layer.name() + "': " + e.getMessage(), e);
    }
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
