// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.ValueType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads a ruleset from a directory: {@code ruleset.toml} and one {@code rules/<id>.toml} per rule.
 * Checks the structure of the files only. {@link RulesetValidator} checks them against the modules.
 */
public final class RulesetLoader {
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
  private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
  private static final TomlMapper MAPPER = new TomlMapper();

  private RulesetLoader() {}

  /** Loads the ruleset in the directory, or throws with every structural problem found. */
  public static Ruleset load(Path directory) throws RulesetException {
    List<Problem> problems = new ArrayList<>();

    Path header = directory.resolve("ruleset.toml");
    Table t = read(header, "ruleset.toml", problems);
    int version = 0;
    String name = "";
    List<ParamDecl> params = new ArrayList<>();
    if (t != null) {
      t.onlyKeys(Set.of("format_version", "name", "description", "param"));
      version = formatVersion(t);
      name = t.string("name", true).orElse("");
      t.table("param", false).ifPresent(p -> params.addAll(params(p)));
    }

    List<Rule> rules = new ArrayList<>();
    Path rulesDir = directory.resolve("rules");
    if (Files.isDirectory(rulesDir)) {
      try (Stream<Path> files = Files.list(rulesDir)) {
        for (Path file : files.filter(f -> f.toString().endsWith(".toml")).sorted().toList()) {
          String where = "rules/" + file.getFileName();
          Table rt = read(file, where, problems);
          if (rt != null) {
            rule(rt, file.getFileName().toString().replaceFirst("\\.toml$", ""))
                .ifPresent(rules::add);
          }
        }
      } catch (IOException e) {
        problems.add(new Problem("rules", "cannot list: " + e.getMessage()));
      }
    }
    rules.sort(Comparator.comparing(Rule::id));

    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }
    return new Ruleset(version, name, params, rules);
  }

  static Table read(Path file, String where, List<Problem> problems) {
    try {
      Map<String, Object> map = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
      return new Table(map, where, problems);
    } catch (IOException e) {
      problems.add(new Problem(where, "cannot read: " + e.getMessage()));
      return null;
    }
  }

  static int formatVersion(Table t) {
    Optional<Object> v = t.raw("format_version");
    if (v.isEmpty()) {
      t.problem("missing 'format_version'");
      return 0;
    }
    if (v.get() instanceof Number n && n.intValue() == n.doubleValue()) {
      return n.intValue();
    }
    t.problem("'format_version' must be an integer");
    return 0;
  }

  static List<ParamDecl> params(Table table) {
    List<ParamDecl> out = new ArrayList<>();
    for (var e : table.subTables().entrySet()) {
      Table p = e.getValue();
      p.onlyKeys(Set.of("type", "default"));
      if (!IDENTIFIER.matcher(e.getKey()).matches()) {
        p.problem("parameter name must be an identifier");
      }
      Optional<ValueType> type = type(p);
      Optional<Object> def = p.raw("default");
      if (type.isPresent() && def.isPresent()) {
        Optional<Object> value = literal(type.get(), def.get());
        if (value.isEmpty()) {
          p.problem("'default' is not a " + type.get().id());
        }
        def = value;
      }
      if (type.isPresent()) {
        out.add(new ParamDecl(e.getKey(), type.get(), def));
      }
    }
    return out;
  }

  private static Optional<ValueType> type(Table t) {
    Optional<String> id = t.string("type", true);
    if (id.isEmpty()) {
      return Optional.empty();
    }
    Optional<ValueType> type = ValueType.fromId(id.get());
    if (type.isEmpty()) {
      t.problem("unknown type '" + id.get() + "'");
    }
    return type;
  }

  static Optional<Rule> rule(Table t, String fileId) {
    t.onlyKeys(
        Set.of(
            "id",
            "description",
            "effect",
            "needs",
            "required",
            "trigger",
            "action",
            "requires_modules"));
    String id = t.string("id", true).orElse(fileId);
    if (!ID.matcher(id).matches()) {
      t.problem("rule id '" + id + "' must match " + ID.pattern());
    }
    if (!id.equals(fileId)) {
      t.problem("rule id '" + id + "' must equal the file name '" + fileId + "'");
    }
    Optional<String> description = t.string("description", false);
    EffectClass effect = null;
    Optional<String> effectId = t.string("effect", true);
    if (effectId.isPresent()) {
      effect = EffectClass.fromId(effectId.get()).orElse(null);
      if (effect == null) {
        t.problem("unknown effect '" + effectId.get() + "'");
      }
    }
    List<String> needs = t.strings("needs");
    boolean required = t.bool("required", false);
    Trigger trigger = t.table("trigger", true).map(RulesetLoader::trigger).orElse(null);
    List<Action> actions = new ArrayList<>();
    for (Table a : t.tables("action")) {
      action(a).ifPresent(actions::add);
    }
    if (actions.isEmpty() && !t.has("action")) {
      t.problem("a rule needs at least one [[action]]");
    }
    if (effect == null || trigger == null || effectId.isEmpty() || id.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Rule(
            id,
            description,
            effect,
            needs,
            required,
            trigger,
            actions,
            t.strings("requires_modules")));
  }

  private static final Pattern PARAM_NAME = Pattern.compile("[a-z][a-z0-9_]*");

  private static Trigger trigger(Table t) {
    Optional<String> kind = t.string("kind", true);
    if (kind.isEmpty()) {
      return null;
    }
    if (kind.get().equals("command")) {
      return command(t);
    }
    if (!kind.get().equals("event")) {
      t.problem("trigger kind '" + kind.get() + "' is not supported yet ('event' and 'command')");
      return null;
    }
    t.onlyKeys(Set.of("kind", "type", "filter"));
    Optional<String> type = t.string("type", true);
    Optional<String> filter = t.string("filter", false);
    return type.<Trigger>map(ty -> new Trigger.OnEvent(ty, filter)).orElse(null);
  }

  private static Trigger command(Table t) {
    t.onlyKeys(Set.of("kind", "title", "description", "param"));
    Optional<String> title = t.string("title", true);
    Optional<String> description = t.string("description", false);
    List<Trigger.CommandParam> params = new ArrayList<>();
    Set<String> names = new java.util.HashSet<>();
    for (Table p : t.tables("param")) {
      p.onlyKeys(Set.of("name", "type", "required", "default", "label", "widget"));
      Optional<String> name = p.string("name", true);
      Optional<ValueType> type = type(p);
      Optional<String> label = p.string("label", false);
      Optional<String> widget = p.string("widget", false);
      if (widget.isPresent()) {
        ValueType wanted = Trigger.CommandParam.WIDGETS.get(widget.get());
        if (widget.get().equals(Trigger.CommandParam.HIDDEN)) {
          wanted = type.orElse(null);
        } else if (wanted == null) {
          p.problem(
              "unknown widget '"
                  + widget.get()
                  + "'; use one of "
                  + new java.util.TreeSet<>(
                      java.util.stream.Stream.concat(
                              Trigger.CommandParam.WIDGETS.keySet().stream(),
                              java.util.stream.Stream.of(Trigger.CommandParam.HIDDEN))
                          .toList()));
        } else if (type.isPresent() && type.get() != wanted) {
          p.problem("the widget '" + widget.get() + "' is for a " + wanted.id());
        }
      }
      if (name.isPresent()) {
        if (!PARAM_NAME.matcher(name.get()).matches()) {
          p.problem("parameter name '" + name.get() + "' must match " + PARAM_NAME.pattern());
        } else if (!names.add(name.get())) {
          p.problem("parameter '" + name.get() + "' declared twice");
        }
      }
      Optional<Object> value = Optional.empty();
      if (p.has("default") && type.isPresent()) {
        value = literal(type.get(), p.raw("default").get());
        if (value.isEmpty()) {
          p.problem("'default' is not a " + type.get().id());
        }
      }
      boolean required = p.bool("required", value.isEmpty());
      if (required && value.isPresent()) {
        p.problem("a parameter with a default cannot be required");
      }
      if (name.isPresent() && type.isPresent()) {
        params.add(
            new Trigger.CommandParam(name.get(), type.get(), required, value, label, widget));
      }
    }
    return title.<Trigger>map(ti -> new Trigger.OnCommand(ti, description, params)).orElse(null);
  }

  private static Optional<Action> action(Table t) {
    if (t.has("stop")) {
      t.onlyKeys(Set.of("stop"));
      if (!t.bool("stop", false)) {
        t.problem("'stop' must be true");
      }
      return Optional.of(new Action.Stop());
    }
    t.onlyKeys(Set.of("id", "capability", "args", "requires_modules"));
    Optional<String> id = t.string("id", true);
    Optional<String> capability = t.string("capability", true);
    Map<String, Arg> args = new LinkedHashMap<>();
    Optional<Table> argTable = t.table("args", false);
    if (argTable.isPresent()) {
      for (var e : argTable.get().rawEntries().entrySet()) {
        args.put(e.getKey(), arg(argTable.get(), e.getKey(), e.getValue()));
      }
    }
    if (id.isEmpty() || capability.isEmpty()) {
      return Optional.empty();
    }
    if (!IDENTIFIER.matcher(id.get()).matches()) {
      t.problem("action id '" + id.get() + "' must be an identifier");
    }
    return Optional.of(
        new Action.Call(id.get(), capability.get(), args, t.strings("requires_modules")));
  }

  static Arg arg(Table table, String name, Object value) {
    if (value instanceof Map<?, ?> m && m.containsKey("cel")) {
      if (m.size() == 1 && m.get("cel") instanceof String source) {
        return new Arg.Expr(source);
      }
      table.problem(
          "arg '" + name + "': an expression is written { cel = \"...\" } and nothing else");
    }
    return new Arg.Literal(value);
  }

  /** Converts a TOML value to the Java type of the value type, or empty if it does not fit. */
  public static Optional<Object> literal(ValueType type, Object v) {
    return switch (type) {
      case STRING -> v instanceof String ? Optional.of(v) : Optional.empty();
      case INT ->
          v instanceof Integer || v instanceof Long
              ? Optional.of(((Number) v).longValue())
              : Optional.empty();
      case DOUBLE -> v instanceof Number n ? Optional.of(n.doubleValue()) : Optional.empty();
      case BOOL -> v instanceof Boolean ? Optional.of(v) : Optional.empty();
      case TIMESTAMP -> {
        try {
          yield v instanceof String s ? Optional.of(Instant.parse(s)) : Optional.empty();
        } catch (DateTimeParseException e) {
          yield Optional.empty();
        }
      }
      case BYTES -> Optional.empty();
      case LIST -> v instanceof List<?> ? Optional.of(v) : Optional.empty();
      case MAP -> v instanceof Map<?, ?> ? Optional.of(v) : Optional.empty();
    };
  }
}
