// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.engine.GrantPolicy;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Rule;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.Verifier;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * What the maintenance agent can see and do, as capabilities of the daemon itself (the module
 * {@code praksis}). The agent is not code in the daemon: it is rules that call a model through the
 * {@code ai} module and then these capabilities, so every step goes through the grants, the audit
 * log and the checks like anything else. What these capabilities allow is small on purpose:
 *
 * <ul>
 *   <li>reading the rules, their tests and which rules have none;
 *   <li>proposing new test files for a rule of an AI-maintained layer, which are checked at once
 *       (do they run, do they make the rule fire, do they break anything) and staged only if they
 *       pass: staged files are in the data directory, never in a layer and never in git;
 *   <li>leaving a note (an explanation) for a person to read.
 * </ul>
 *
 * It cannot change a rule, a grant or a layer, and it cannot commit or push; a staged change
 * becomes part of a layer only when the person exports it and commits it themselves.
 */
final class Agent {
  /** Only a test file for a rule: a plain name below tests/. */
  private static final Pattern TEST_FILE =
      Pattern.compile("tests/[a-z0-9][a-z0-9._-]{0,80}\\.toml");

  private static final int MAX_FILES = 5;
  private static final int MAX_BYTES = 64 * 1024;

  private volatile DaemonConfig config;
  private volatile Layers layers;
  private volatile LoadedModules modules;
  private volatile AgentStore store;
  private volatile History history;
  private volatile Plans plans;

  /** Gives the capabilities what they need; called when the daemon has put itself together. */
  void bind(
      DaemonConfig config,
      Layers layers,
      LoadedModules modules,
      it.uhde.praksis.store.Store log,
      java.util.function.Supplier<LayerStack.Effective> effective,
      GrantPolicy access,
      it.uhde.praksis.engine.AuditLog audit,
      java.time.Clock clock) {
    this.config = config;
    this.layers = layers;
    this.modules = modules;
    this.store = new AgentStore(config.dataDirectory().resolve(Builtins.MODULE));
    this.history = new History(log, effective);
    this.plans =
        new Plans(
            config, access, modules.catalog(), modules.capabilities(), audit, clock, () -> store);
  }

  Map<String, Capability> capabilities() {
    Map<String, Capability> m = new LinkedHashMap<>();
    cap(m, "praksis.rules.list", this::list);
    cap(m, "praksis.rules.read", this::read);
    cap(m, "praksis.agent.propose", this::propose);
    cap(m, "praksis.agent.proposals", this::proposals);
    cap(m, "praksis.notes.save", this::saveNote);
    cap(m, "praksis.notes.list", this::notes);
    cap(m, "praksis.history.find", (c, a) -> historyOf().find(a));
    cap(m, "praksis.history.trace", (c, a) -> historyOf().trace(a));
    cap(m, "praksis.history.summary", (c, a) -> historyOf().summary(a));
    cap(m, "praksis.plan.catalog", (c, a) -> plansOf().catalogOfGranted());
    cap(m, "praksis.plan.read", (c, a) -> plansOf().read(a));
    cap(m, "praksis.plan.propose", (c, a) -> plansOf().propose(c, a));
    return m;
  }

  private interface Body {
    Map<String, Object> run(CapabilityContext context, Map<String, Object> args) throws Exception;
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
            } catch (CapabilityException e) {
              throw e;
            } catch (DaemonException | RulesetException | it.uhde.praksis.store.StoreException e) {
              throw new CapabilityException(e.getMessage(), false, e);
            } catch (IOException e) {
              throw new CapabilityException("cannot read or write: " + e.getMessage(), true, e);
            } catch (Exception e) {
              throw new CapabilityException(name + " failed: " + e, false, e);
            }
          }
        });
  }

  Plans plansOf() throws CapabilityException {
    ready();
    return plans;
  }

  private History historyOf() throws CapabilityException {
    ready();
    return history;
  }

  private void ready() throws CapabilityException {
    if (config == null) {
      throw new CapabilityException("the daemon is not ready", true);
    }
  }

  // ---------------------------------------------------------------------------- reading rules

  private Map<String, Object> list(CapabilityContext context, Map<String, Object> args)
      throws Exception {
    ready();
    String layer = text(args, "layer", false);
    boolean untestedOnly = args.get("untested") instanceof Boolean b && b;
    LayerStack.Effective effective = layers.effective();
    Set<String> untested = untested(null);
    Map<String, String> owners = new LinkedHashMap<>();
    effective.origins().forEach(o -> owners.put(o.ruleId(), o.owner()));
    List<Map<String, Object>> items = new ArrayList<>();
    for (Rule rule : effective.ruleset().rules()) {
      String owner = owners.getOrDefault(rule.id(), "");
      boolean tested = !untested.contains(rule.id());
      if ((layer != null && !layer.equals(owner)) || (untestedOnly && tested)) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", rule.id());
      item.put("layer", owner);
      item.put("effect", rule.effect().id());
      item.put("description", rule.description().orElse(""));
      item.put("tested", tested);
      items.add(item);
    }
    return Map.of("items", items);
  }

  private Map<String, Object> read(CapabilityContext context, Map<String, Object> args)
      throws Exception {
    ready();
    String id = text(args, "rule", true);
    LayerStack.Effective effective = layers.effective();
    LayerStack.Origin origin =
        effective.origins().stream()
            .filter(o -> o.ruleId().equals(id))
            .findFirst()
            .orElseThrow(() -> new CapabilityException("there is no rule '" + id + "'", false));
    Rule rule =
        effective.ruleset().rules().stream()
            .filter(r -> r.id().equals(id))
            .findFirst()
            .orElseThrow();
    Path dir = layerDirectory(origin.owner());
    String file = Layers.fileOf(origin);
    Path testFile = dir.resolve("tests").resolve(id + ".toml");
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("rule", id);
    out.put("layer", origin.owner());
    out.put("file", file);
    out.put("text", bounded(Files.readString(dir.resolve(file), StandardCharsets.UTF_8)));
    out.put(
        "tests",
        Files.isRegularFile(testFile)
            ? bounded(Files.readString(testFile, StandardCharsets.UTF_8))
            : "");
    out.put("effect", rule.effect().id());
    out.put("description", rule.description().orElse(""));
    out.put("trigger", trigger(rule));
    out.put("needs", new ArrayList<Object>(rule.needs()));
    out.put("tested", !untested(null).contains(id));
    return out;
  }

  /**
   * What fires the rule and what the event carries, in words that a test writer needs: the type and
   * every field of its data with the type of the value.
   */
  private String trigger(Rule rule) {
    var catalog = modules.catalog();
    switch (rule.trigger()) {
      case it.uhde.praksis.ruleset.Trigger.OnEvent t -> {
        String fields =
            catalog
                .eventType(t.type())
                .map(
                    e ->
                        e.fields().stream()
                            .map(
                                f ->
                                    f.name()
                                        + " ("
                                        + f.type().id()
                                        + (f.required() ? "" : ", optional")
                                        + ")")
                            .collect(java.util.stream.Collectors.joining(", ")))
                .orElse("?");
        return "the event "
            + t.type()
            + " with the data fields: "
            + fields
            + t.filter().map(f -> "; only if " + f).orElse("");
      }
      case it.uhde.praksis.ruleset.Trigger.OnCommand c -> {
        String fields =
            c.params().stream()
                .map(
                    p ->
                        p.name()
                            + " ("
                            + p.type().id()
                            + (p.defaultValue().isPresent()
                                ? ", default " + p.defaultValue().get()
                                : "")
                            + ")")
                .collect(java.util.stream.Collectors.joining(", "));
        return "a command, which a test writes as the event command."
            + rule.id()
            + " with the data fields: "
            + (fields.isEmpty() ? "(none)" : fields);
      }
      default -> {
        return "another kind of trigger";
      }
    }
  }

  /** The rules that no test makes fire, according to the checks. */
  private Set<String> untested(Map<String, String> replacements) throws DaemonException {
    Admin.Checked checked = check(replacements);
    Set<String> out = new HashSet<>();
    for (Verifier.Finding f : checked.report().findings()) {
      if (f.check().equals("coverage") && f.where().startsWith("rules/")) {
        out.add(f.where().substring("rules/".length(), f.where().length() - ".toml".length()));
      }
    }
    return out;
  }

  private Admin.Checked check(Map<String, String> replacements) throws DaemonException {
    StringWriter err = new StringWriter();
    Admin.Checked checked =
        Admin.check(
            config,
            layers,
            new Admin.Check.Options(
                false, false, 0, replacements == null ? Map.of() : replacements),
            new PrintWriter(err),
            modules);
    if (checked == null) {
      throw new DaemonException("the checks could not be run: " + err.toString().strip());
    }
    return checked;
  }

  private Path layerDirectory(String name) throws DaemonException {
    for (LayerStack.Source source : layers.sources(layers.readLock())) {
      if (source.name().equals(name)) {
        return source.directory();
      }
    }
    throw new DaemonException("there is no layer '" + name + "'");
  }

  // ---------------------------------------------------------------------------- proposing tests

  private Map<String, Object> propose(CapabilityContext context, Map<String, Object> args)
      throws Exception {
    ready();
    String layer = text(args, "layer", true);
    String rule = text(args, "rule", true);
    String reason = text(args, "reason", false);
    reason = reason == null ? "" : reason.length() > 500 ? reason.substring(0, 500) : reason;
    if (config.aiMaintainedLayers().stream().noneMatch(l -> l.name().equals(layer))) {
      throw new CapabilityException(
          "layer '"
              + layer
              + "' is not AI-maintained: set ai_maintained = true on it to take proposals",
          false);
    }
    Map<String, String> files = files(args);
    LayerStack.Effective effective = layers.effective();
    LayerStack.Origin origin =
        effective.origins().stream()
            .filter(o -> o.ruleId().equals(rule))
            .findFirst()
            .orElseThrow(() -> new CapabilityException("there is no rule '" + rule + "'", false));
    if (!origin.owner().equals(layer)) {
      throw new CapabilityException(
          "the tests of rule '"
              + rule
              + "' belong in layer '"
              + origin.owner()
              + "', not '"
              + layer
              + "'",
          false);
    }
    Path source = layerDirectory(layer);
    for (String path : files.keySet()) {
      if (Files.exists(source.resolve(path))) {
        throw new CapabilityException(
            "'"
                + path
                + "' is in layer '"
                + layer
                + "' already: a proposal adds files and does not change any",
            false);
      }
    }

    Path candidate = Files.createTempDirectory("praksis-agent-");
    try {
      copy(source, candidate);
      for (var e : files.entrySet()) {
        Path target = candidate.resolve(e.getKey());
        Files.createDirectories(target.getParent());
        Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
      }
      Set<String> before = errors(check(null));
      Admin.Checked after = check(Map.of(layer, candidate.toString()));
      List<String> problems = new ArrayList<>();
      for (Verifier.Finding f : after.report().findings()) {
        if (f.severity() == Verifier.Severity.ERROR && !before.contains(key(f))) {
          problems.add(f.check() + ": " + f.where() + ": " + first(f.message()));
        }
      }
      boolean covers =
          after.report().findings().stream()
              .noneMatch(
                  f -> f.check().equals("coverage") && f.where().equals("rules/" + rule + ".toml"));
      boolean ok = problems.isEmpty() && covers;
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("ok", ok);
      out.put("covers", covers);
      out.put("problems", new ArrayList<Object>(problems.stream().limit(10).toList()));
      String id = "";
      boolean staged = false;
      if (ok && !context.dryRun()) {
        id = store.stage(layer, rule, reason, layers.commits().getOrDefault(layer, ""), files).id();
        staged = true;
      }
      out.put("staged", staged);
      out.put("id", id);
      return out;
    } finally {
      delete(candidate);
    }
  }

  private static Set<String> errors(Admin.Checked checked) {
    Set<String> out = new HashSet<>();
    for (Verifier.Finding f : checked.report().findings()) {
      if (f.severity() == Verifier.Severity.ERROR) {
        out.add(key(f));
      }
    }
    return out;
  }

  private static String key(Verifier.Finding f) {
    return f.check() + "|" + f.where() + "|" + f.message();
  }

  private static String first(String message) {
    String line = message.lines().findFirst().orElse("");
    return line.length() > 300 ? line.substring(0, 300) : line;
  }

  /** The files of a proposal: only test files, a few and small. */
  private static Map<String, String> files(Map<String, Object> args) throws CapabilityException {
    if (!(args.get("files") instanceof List<?> list) || list.isEmpty() || list.size() > MAX_FILES) {
      throw new CapabilityException(
          "'files' needs 1 to " + MAX_FILES + " entries of {path, content}", false);
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (Object o : list) {
      if (!(o instanceof Map<?, ?> m)
          || !(m.get("path") instanceof String path)
          || !(m.get("content") instanceof String content)) {
        throw new CapabilityException("every file needs a 'path' and a 'content'", false);
      }
      if (!TEST_FILE.matcher(path).matches()) {
        throw new CapabilityException(
            "'" + path + "' is not allowed: a proposal may only add test files, tests/<name>.toml",
            false);
      }
      if (content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES || content.isBlank()) {
        throw new CapabilityException(
            "the content of '" + path + "' is empty or more than " + MAX_BYTES / 1024 + " KiB",
            false);
      }
      if (out.put(path, content) != null) {
        throw new CapabilityException("'" + path + "' is given twice", false);
      }
    }
    return out;
  }

  static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> all = Files.walk(from)) {
      for (Path p : (Iterable<Path>) all::iterator) {
        Path rel = from.relativize(p);
        if (rel.toString().startsWith(".git")) {
          continue;
        }
        if (Files.isDirectory(p)) {
          Files.createDirectories(to.resolve(rel));
        } else {
          Files.copy(p, to.resolve(rel));
        }
      }
    }
  }

  static void delete(Path dir) {
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) all.sorted(java.util.Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      // a temporary directory that stays is no harm
    }
  }

  private Map<String, Object> proposals(CapabilityContext context, Map<String, Object> args)
      throws Exception {
    ready();
    List<Map<String, Object>> items = new ArrayList<>();
    for (AgentStore.Staged s : store.list()) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", s.id());
      m.put("layer", s.layer());
      m.put("rule", s.rule());
      m.put("reason", s.reason());
      m.put("created", s.created());
      m.put("files", new ArrayList<Object>(s.files()));
      items.add(m);
    }
    return Map.of("items", items);
  }

  // ---------------------------------------------------------------------------- notes

  private Map<String, Object> saveNote(CapabilityContext context, Map<String, Object> args)
      throws Exception {
    ready();
    String title = text(args, "title", true).strip();
    String body = text(args, "text", true);
    if (title.length() > 120 || title.contains("\n") || title.contains("\r")) {
      throw new CapabilityException("the title is a line of at most 120 characters", false);
    }
    if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
      throw new CapabilityException("the text is more than " + MAX_BYTES / 1024 + " KiB", false);
    }
    if (context.dryRun()) {
      return Map.of("id", "");
    }
    return Map.of("id", store.saveNote(title, body).id());
  }

  private Map<String, Object> notes(CapabilityContext context, Map<String, Object> args)
      throws Exception {
    ready();
    int limit =
        args.get("limit") instanceof Number n ? Math.max(1, Math.min(n.intValue(), 100)) : 20;
    List<Map<String, Object>> items = new ArrayList<>();
    for (AgentStore.Note n : store.notes(limit)) {
      Map<String, Object> m = new LinkedHashMap<>();
      m.put("id", n.id());
      m.put("at", n.at());
      m.put("title", n.title());
      m.put("text", n.text());
      items.add(m);
    }
    return Map.of("items", items);
  }

  // ---------------------------------------------------------------------------- the staged changes

  AgentStore store() {
    return store;
  }

  private static String bounded(String text) {
    return text.length() > MAX_BYTES ? text.substring(0, MAX_BYTES) : text;
  }

  private static String text(Map<String, Object> args, String name, boolean required)
      throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      if (required) {
        throw new CapabilityException("'" + name + "' is required", false);
      }
      return null;
    }
    if (!(v instanceof String s) || (required && s.isBlank())) {
      throw new CapabilityException("'" + name + "' must be a text", false);
    }
    return s;
  }
}
