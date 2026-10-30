// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Table;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Where everything is. All of it lives in one home directory, which the CLI knows as well; the
 * socket is always {@code <home>/praksis.sock}.
 *
 * <p>The optional file {@code <home>/praksis.toml} can change the defaults. Relative paths in it
 * are relative to the home directory:
 *
 * <pre>
 * format_version = 1
 * ruleset    = "ruleset"       # directory with ruleset.toml and rules/
 * modules    = "modules"       # directory with one subdirectory per module (where 'modules
 *                              # install' puts them); if it is not set, the ones that ship with
 *                              # the runtime image are used as well, below the ones in the home
 * enabled_modules = ["maildir"]  # load only these; without it the modules with a [module.*]
 *                              # section below, or every module if there is none
 * lockfile   = "modules.lock"
 * database   = "praksis.db"
 * expression = "lib/expression"  # directory with the CEL jar; else the one that ships with the
 *                              # runtime image; else the class path is used
 * dry_run    = false
 *
 * [[layer]]                    # ruleset layers, bottom to top; without any, "ruleset" is used
 * name = "mail"
 * git  = "https://github.com/tuhde/Praksis-Mail"   # pinned in rulesets.lock
 * ref  = "main"
 *
 * [[layer]]
 * name = "local"
 * path = "my-rules"            # a directory, read as it is and not pinned
 * ai_maintained = true         # may an AI propose changes? true for a directory, false for git
 *
 * [shadow]                     # new and changed rules are observed before they run
 * enabled    = false           # off unless set
 * trial_days = 7               # how long a rule is observed
 * min_events = 20              # and with how many events at least
 *
 * [param]                      # values for ruleset parameters
 * newsletter_folder = "News"
 *
 * [module.maildir]             # settings handed to the module with this name
 * root = "/home/me/Maildir"
 * </pre>
 *
 * @param home the home directory
 * @param ruleset the ruleset directory
 * @param modules the modules directory
 * @param lockfile the module lockfile
 * @param database the SQLite file
 * @param expression the directory with the expression implementation, if it is not on the path
 * @param dryRun whether capabilities are told to change nothing
 * @param moduleSettings the settings per module name
 * @param layers the ruleset layers, bottom to top; empty means the single directory {@code ruleset}
 * @param params values for ruleset parameters, which win over the defaults in the layers
 * @param shadow the settings for rules on trial
 * @param enabledModules the modules to load; empty means every module in the directory. Unless
 *     {@code enabled_modules} says otherwise these are the modules that have a {@code [module.*]}
 *     section.
 */
public record DaemonConfig(
    Path home,
    Path ruleset,
    Path modules,
    List<Path> moduleRoots,
    Path lockfile,
    Path database,
    Optional<Path> expression,
    boolean dryRun,
    Map<String, Map<String, Object>> moduleSettings,
    List<LayerConfig> layers,
    Map<String, Object> params,
    java.util.Set<String> enabledModules,
    ShadowConfig shadow) {
  private static final String FILE = "praksis.toml";
  private static final Set<String> KEYS =
      Set.of(
          "format_version",
          "ruleset",
          "modules",
          "lockfile",
          "database",
          "expression",
          "dry_run",
          "module",
          "layer",
          "param",
          "enabled_modules",
          "shadow");

  /** The layers that an AI may propose changes to. */
  public List<LayerConfig> aiMaintainedLayers() {
    return layers.stream().filter(LayerConfig::aiMaintained).toList();
  }

  /** The file that pins the commit of every git layer. */
  public Path rulesetLock() {
    return home.resolve("rulesets.lock");
  }

  /** The local grants. Always in the home, never configurable and never part of a ruleset. */
  public Path policy() {
    return home.resolve("policy.toml");
  }

  /** The names of the ruleset layers: the configured ones, else the single layer "ruleset". */
  public Set<String> layerNames() {
    return layers.isEmpty()
        ? Set.of("ruleset")
        : layers.stream().map(LayerConfig::name).collect(java.util.stream.Collectors.toSet());
  }

  /** Where the local copies of the git repositories are kept. */
  public Path gitCache() {
    return home.resolve("cache").resolve("git");
  }

  /** Where the files of each pinned commit are kept: {@code <name>/<commit>}. */
  public Path snapshots() {
    return home.resolve("layers");
  }

  /** Where a file waits that a person asked for, until the frontend has copied it. */
  public Path downloadsDirectory() {
    return home.resolve("downloads");
  }

  /** Where the files wait that a person chose to attach to something they send. */
  public Path uploadsDirectory() {
    return home.resolve("uploads");
  }

  /** Where the modules keep their own files: {@code <home>/data/<module>}. */
  public Path dataDirectory() {
    return home.resolve("data");
  }

  /** The socket of the daemon with this home directory. */
  public Path socket() {
    return home.resolve("praksis.sock");
  }

  /**
   * Reads the configuration of a home directory.
   *
   * @throws DaemonException if the home directory is missing or the file is invalid
   */
  public static DaemonConfig load(Path home) throws DaemonException {
    Path dir = home.toAbsolutePath().normalize();
    if (!Files.isDirectory(dir)) {
      throw new DaemonException("home directory " + dir + " does not exist");
    }
    Map<String, Object> values = new LinkedHashMap<>();
    Path file = dir.resolve(FILE);
    if (Files.exists(file)) {
      try {
        values = new TomlMapper().readValue(file.toFile(), new TypeReference<>() {});
      } catch (IOException e) {
        throw new DaemonException("cannot read " + file + ": " + e.getMessage(), e);
      }
    }
    List<Problem> problems = new ArrayList<>();
    Table table = new Table(values, FILE, problems);
    table.onlyKeys(KEYS);
    if (!values.isEmpty() && !Long.valueOf(1).equals(number(values.get("format_version")))) {
      table.problem("'format_version' must be 1");
    }
    Path ruleset = path(table, dir, "ruleset", "ruleset");
    // Modules are installed below the home. They are looked for there first, then in the ones that
    // ship with the runtime image; a module of the home hides one of the same name. A directory
    // that the file names is the only one.
    Path modules = path(table, dir, "modules", "modules");
    List<Path> moduleRoots = new ArrayList<>(List.of(modules));
    if (!table.has("modules")) {
      installed("modules").filter(i -> !i.equals(modules)).ifPresent(moduleRoots::add);
    }
    Path lockfile = path(table, dir, "lockfile", "modules.lock");
    Path database = path(table, dir, "database", "praksis.db");
    Optional<Path> expression =
        table
            .string("expression", false)
            .map(s -> dir.resolve(s).normalize())
            .or(() -> installed("expression"));
    boolean dryRun = table.bool("dry_run", false);
    Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
    table
        .table("module", false)
        .ifPresent(
            modulesTable ->
                modulesTable
                    .subTables()
                    .forEach((name, t) -> settings.put(name, normalizeMap(t.rawEntries()))));
    List<LayerConfig> layers = new ArrayList<>();
    java.util.Set<String> names = new java.util.HashSet<>();
    for (Table l : table.tables("layer")) {
      LayerConfig.parse(l, dir).ifPresent(layers::add);
      l.string("name", false)
          .ifPresent(
              n -> {
                if (!names.add(n)) {
                  l.problem("layer name '" + n + "' is used twice");
                }
              });
    }
    // Without a list, the modules that have settings are the ones in use: the runtime image ships
    // several, e.g. two mail backends, and only the one that was configured should start.
    java.util.Set<String> enabled = new java.util.LinkedHashSet<>(table.strings("enabled_modules"));
    if (!table.has("enabled_modules")) {
      enabled.addAll(settings.keySet());
    }
    if (!layers.isEmpty() && table.has("ruleset")) {
      table.problem("'ruleset' and [[layer]] cannot be used together; make it a layer with path");
    }
    Map<String, Object> params = new LinkedHashMap<>();
    table.table("param", false).ifPresent(p -> params.putAll(normalizeMap(p.rawEntries())));
    ShadowConfig shadow =
        table
            .table("shadow", false)
            .map(ShadowConfig::parse)
            .orElse(new ShadowConfig(false, 7, 20));
    if (!problems.isEmpty()) {
      throw new DaemonException(
          "invalid "
              + file
              + ":\n  "
              + String.join("\n  ", problems.stream().map(Problem::toString).toList()));
    }
    return new DaemonConfig(
        dir,
        ruleset,
        modules,
        List.copyOf(moduleRoots),
        lockfile,
        database,
        expression,
        dryRun,
        settings,
        layers,
        params,
        enabled,
        shadow);
  }

  /** The directory of what ships with the runtime image, if this is one: {@code lib/praksis}. */
  private static Optional<Path> installed(String name) {
    Path dir = Path.of(System.getProperty("java.home"), "lib", "praksis", name);
    return Files.isDirectory(dir) ? Optional.of(dir) : Optional.empty();
  }

  private static Path path(Table table, Path home, String key, String fallback) {
    return home.resolve(table.string(key, false).orElse(fallback)).normalize();
  }

  private static Long number(Object value) {
    return value instanceof Number n ? n.longValue() : null;
  }

  /** TOML reading gives small integers as Integer; modules are promised Long (see ValueType). */
  private static Object normalize(Object value) {
    if (value instanceof Integer i) {
      return i.longValue();
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new LinkedHashMap<>();
      map.forEach((k, v) -> out.put((String) k, normalize(v)));
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(DaemonConfig::normalize).toList();
    }
    return value;
  }

  private static Map<String, Object> normalizeMap(Map<String, Object> map) {
    Map<String, Object> out = new LinkedHashMap<>();
    map.forEach((k, v) -> out.put(k, normalize(v)));
    return out;
  }
}
