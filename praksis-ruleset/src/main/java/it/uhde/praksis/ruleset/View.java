// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import it.uhde.praksis.capability.ValueType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A screen, described and not programmed: data from read-only capability calls, shown by a fixed
 * set of components. A view is a file {@code views/<id>.toml} of a layer. The values of the
 * description are literals or expressions ({@code { cel = "..." }}) over {@code view} (the
 * parameters of the view, which a client sets, for example to the selected message), {@code source}
 * (the results of the sources, by name), {@code params} (the ruleset parameters) and, in the rows
 * of a table or list, {@code item}.
 *
 * <pre>
 * schema_version = 1
 * id = "mail.inbox"
 * title = "Inbox"
 *
 * [param.folder]                      # state of the view, set by the client
 * type = "string"
 * default = "INBOX"
 *
 * [source.messages]                   # a read-only capability call
 * capability = "mail.list"
 * args = { folder = { cel = "view.folder" } }
 *
 * [[component]]
 * type = "table"
 * rows = { cel = "source.messages.items" }
 * columns = [ { title = "Subject", value = { cel = "item.subject" } } ]
 * select = { param = "message", value = { cel = "item.id" } }
 * </pre>
 *
 * Components: {@code text}, {@code table}, {@code list}, {@code detail}, {@code button}, {@code
 * form} (made from the parameters of a command) and the containers {@code row} and {@code column}.
 * A higher layer may replace a view of a lower one by naming its hash in {@code based_on}.
 *
 * @param id the id, which is the file name without {@code .toml}
 * @param title what a person sees
 * @param description what it is for, or empty
 * @param schema the version of this vocabulary that the view is written in
 * @param params the state of the view, with defaults
 * @param sources named read-only capability calls
 * @param components what is shown, top to bottom
 * @param basedOn for a replacement, the hash of the view that it replaces
 * @param raw the view as the plain values of its file, which the hash is made of
 */
public record View(
    String id,
    String title,
    Optional<String> description,
    int schema,
    List<ParamDecl> params,
    Map<String, Source> sources,
    List<Component> components,
    Optional<String> basedOn,
    Map<String, Object> raw,
    List<String> requiresModules) {
  /** The version of the vocabulary that this Praksis understands. */
  public static final int SUPPORTED_SCHEMA = 1;

  /** The most components in one view, containers included. */
  static final int MAX_COMPONENTS = 60;

  /** The deepest nesting of containers. */
  static final int MAX_DEPTH = 4;

  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_]*");
  private static final Pattern HASH = Pattern.compile("sha256:[0-9a-f]{64}");
  private static final Set<String> TYPES =
      Set.of(
          "text",
          "table",
          "list",
          "detail",
          "button",
          "form",
          "input",
          "richtext",
          "row",
          "column");
  private static final Set<String> FORMATS = Set.of("text", "datetime", "date", "number", "bytes");
  private static final Set<String> STYLES = Set.of("normal", "title", "muted", "code");
  private static final JsonMapper CANONICAL =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .build();

  /**
   * A read-only capability call whose result is available as {@code source.<name>}.
   *
   * @param capability the capability
   * @param args its arguments
   * @param when an expression over {@code view}; the call is made only if it is true, otherwise the
   *     result is empty (for a source that needs a selection that may not be made yet)
   * @param requiresModules modules that must be there; if one is not, the source is left out
   */
  public record Source(
      String capability, Map<String, Arg> args, Optional<Arg> when, List<String> requiresModules) {
    public Source {
      args = Map.copyOf(args);
      requiresModules = List.copyOf(requiresModules);
    }

    public Source(String capability, Map<String, Arg> args, Optional<Arg> when) {
      this(capability, args, when, List.of());
    }

    public Source(String capability, Map<String, Arg> args) {
      this(capability, args, Optional.empty(), List.of());
    }
  }

  /**
   * One part of a view.
   *
   * @param type {@code text}, {@code table}, {@code list}, {@code detail}, {@code button}, {@code
   *     form}, {@code row} or {@code column}
   * @param values the simple attributes, by name
   * @param columns the columns of a table: {@code title} and {@code format} (literals) and {@code
   *     value}
   * @param fields the fields of a detail: {@code label} and {@code format} (literals) and {@code
   *     value}
   * @param actions what a person can start: the actions of a row, or the one of a button
   * @param select what a click on a row sets: a parameter of the view and its value
   * @param children the parts of a container
   */
  public record Component(
      String type,
      Map<String, Arg> values,
      List<Map<String, Arg>> columns,
      List<Map<String, Arg>> fields,
      List<Action> actions,
      Optional<Select> select,
      List<Component> children) {
    public Component {
      values = Map.copyOf(values);
      columns = List.copyOf(columns);
      fields = List.copyOf(fields);
      actions = List.copyOf(actions);
      children = List.copyOf(children);
    }
  }

  /**
   * Something a person can start from the view: a command with arguments from the view, or the
   * download of a file. A download reads one capability (read-only, under the grants of the layer
   * that owns the view) and hands the {@code content} it answers with to the person, who chooses
   * where to save it. Nothing is opened or run.
   */
  public record Action(
      String label,
      String command,
      Map<String, Arg> args,
      Optional<String> confirm,
      Optional<Download> download,
      Optional<Opens> opens) {
    public Action {
      args = Map.copyOf(args);
    }

    public Action(
        String label,
        String command,
        Map<String, Arg> args,
        Optional<String> confirm,
        Optional<Download> download) {
      this(label, command, args, confirm, download, Optional.empty());
    }

    public Action(String label, String command, Map<String, Arg> args, Optional<String> confirm) {
      this(label, command, args, confirm, Optional.empty(), Optional.empty());
    }
  }

  /**
   * Another view to show, with this state: how a reply opens the view for writing with the mail it
   * answers. The parameters must be declared by that view.
   */
  public record Opens(String view, Map<String, Arg> params) {
    public Opens {
      params = Map.copyOf(params);
    }
  }

  /**
   * A file to download: the capability to call (it must answer with {@code content}, bytes), its
   * arguments, and the suggested name of the file.
   */
  public record Download(String capability, Map<String, Arg> args, Arg filename) {
    public Download {
      args = Map.copyOf(args);
    }
  }

  /**
   * Clicking a row, or pressing a button that has {@code sets}, sets the view parameter {@code
   * param} to {@code value} and puts the parameters in {@code clear} back to their defaults
   * (choosing another folder forgets the selected mail). If there is a {@code then}, that command
   * is started as well (opening a mail marks it read).
   */
  public record Select(String param, Arg value, List<String> clear, Optional<Action> then) {
    public Select {
      clear = List.copyOf(clear);
    }

    public Select(String param, Arg value, List<String> clear) {
      this(param, value, clear, Optional.empty());
    }

    public Select(String param, Arg value) {
      this(param, value, List.of(), Optional.empty());
    }
  }

  /**
   * The view as it is with these modules, or empty if the whole view needs a module that is not
   * there. A part (any component) that asks for a module that is not there is left out, with what
   * is inside it.
   */
  public Optional<View> available(Set<String> modules) {
    if (!modules.containsAll(requiresModules)) {
      return Optional.empty();
    }
    return Optional.of(
        new View(
            id,
            title,
            description,
            schema,
            params,
            availableSources(sources, modules),
            availableParts(components, modules),
            basedOn,
            raw,
            requiresModules));
  }

  private static Map<String, Source> availableSources(
      Map<String, Source> sources, Set<String> modules) {
    Map<String, Source> out = new LinkedHashMap<>();
    sources.forEach(
        (name, source) -> {
          if (modules.containsAll(source.requiresModules())) {
            out.put(name, source);
          }
        });
    return out;
  }

  /** Whether a part asks only for modules that are there. */
  private static boolean wanted(Component c, Set<String> modules) {
    return !(c.values().get("requires_modules") instanceof Arg.Literal l
            && l.value() instanceof List<?> list)
        || modules.containsAll(list.stream().map(String::valueOf).toList());
  }

  private static List<Component> availableParts(List<Component> parts, Set<String> modules) {
    List<Component> out = new ArrayList<>();
    for (Component c : parts) {
      if (wanted(c, modules)) {
        Map<String, Arg> values = c.values();
        // the suggestions of a form and the microphone of an input are dropped, and the part stays,
        // if their module is not there
        for (String prefix : List.of("lookup.", "dictate.")) {
          if (values.get(prefix + "requires_modules") instanceof Arg.Literal l
              && l.value() instanceof List<?> list
              && !modules.containsAll(list.stream().map(String::valueOf).toList())) {
            values = new LinkedHashMap<>(values);
            values.keySet().removeIf(k -> k.startsWith(prefix));
          }
        }
        out.add(
            new Component(
                c.type(),
                values,
                c.columns(),
                c.fields(),
                c.actions(),
                c.select(),
                availableParts(c.children(), modules)));
      }
    }
    return out;
  }

  /** {@code sha256:<hex>} of the view\'s content, without {@code based_on}. */
  public String hash() {
    return hashOf(raw);
  }

  static String hashOf(Map<String, Object> raw) {
    Map<String, Object> content = new LinkedHashMap<>(raw);
    content.remove("based_on");
    try {
      byte[] json = CANONICAL.writeValueAsString(content).getBytes(StandardCharsets.UTF_8);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Reads a view file; problems are recorded in the table. */
  static Optional<View> parse(Table t, String fileId, Map<String, Object> raw) {
    t.onlyKeys(
        Set.of(
            "schema_version",
            "id",
            "title",
            "description",
            "based_on",
            "param",
            "source",
            "component",
            "requires_modules"));
    String id = t.string("id", false).orElse(fileId);
    if (!ID.matcher(id).matches()) {
      t.problem("view id '" + id + "' must match " + ID.pattern());
    }
    if (!id.equals(fileId)) {
      t.problem("view id '" + id + "' must equal the file name '" + fileId + "'");
    }
    int schema = SUPPORTED_SCHEMA;
    Optional<Object> version = t.raw("schema_version");
    if (version.isPresent()) {
      if (version.get() instanceof Number n && n.longValue() >= 1 && n.longValue() < 1000) {
        schema = n.intValue();
      } else {
        t.problem("'schema_version' must be a positive whole number");
      }
    }
    Optional<String> title = t.string("title", true);
    Optional<String> description = t.string("description", false);
    Optional<String> basedOn = t.string("based_on", false);
    if (basedOn.isPresent() && !HASH.matcher(basedOn.get()).matches()) {
      t.problem("'based_on' must look like sha256:<64 hex digits>, see the effective view");
      basedOn = Optional.empty();
    }
    List<ParamDecl> params = new ArrayList<>();
    t.table("param", false)
        .ifPresent(
            pt ->
                pt.subTables()
                    .forEach(
                        (name, p) -> {
                          p.onlyKeys(Set.of("type", "default"));
                          if (!NAME.matcher(name).matches()) {
                            p.problem("parameter name '" + name + "' must match " + NAME.pattern());
                          }
                          Optional<String> typeId = p.string("type", true);
                          Optional<ValueType> type = typeId.flatMap(ValueType::fromId);
                          if (typeId.isPresent() && type.isEmpty()) {
                            p.problem("unknown type '" + typeId.get() + "'");
                          }
                          Optional<Object> value = Optional.empty();
                          if (p.has("default") && type.isPresent()) {
                            value = RulesetLoader.literal(type.get(), p.raw("default").get());
                            if (value.isEmpty()) {
                              p.problem("'default' is not a " + type.get().id());
                            }
                          }
                          Optional<Object> declared = value;
                          type.ifPresent(ty -> params.add(new ParamDecl(name, ty, declared)));
                        }));
    Map<String, Source> sources = new LinkedHashMap<>();
    t.table("source", false)
        .ifPresent(
            st ->
                st.subTables()
                    .forEach(
                        (name, s) -> {
                          s.onlyKeys(Set.of("capability", "args", "when", "requires_modules"));
                          if (!NAME.matcher(name).matches()) {
                            s.problem("source name '" + name + "' must match " + NAME.pattern());
                          }
                          Optional<String> capability = s.string("capability", true);
                          Map<String, Arg> args = args(s);
                          Optional<Arg> when =
                              s.raw("when").map(w -> RulesetLoader.arg(s, "when", w));
                          capability.ifPresent(
                              c ->
                                  sources.put(
                                      name,
                                      new Source(c, args, when, s.strings("requires_modules"))));
                        }));
    List<Component> components = new ArrayList<>();
    int[] count = {0};
    for (Table c : t.tables("component")) {
      component(c, 1, count).ifPresent(components::add);
    }
    if (components.isEmpty() && !t.has("component")) {
      t.problem("a view needs at least one [[component]]");
    }
    if (title.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new View(
            id,
            title.get(),
            description,
            schema,
            params,
            sources,
            components,
            basedOn,
            raw,
            t.strings("requires_modules")));
  }

  private static Map<String, Arg> args(Table t) {
    Map<String, Arg> out = new LinkedHashMap<>();
    t.table("args", false)
        .ifPresent(
            a ->
                a.rawEntries()
                    .forEach((name, value) -> out.put(name, RulesetLoader.arg(a, name, value))));
    return out;
  }

  private static Optional<Component> component(Table t, int depth, int[] count) {
    if (++count[0] > MAX_COMPONENTS) {
      if (count[0] == MAX_COMPONENTS + 1) {
        t.problem("a view has at most " + MAX_COMPONENTS + " components");
      }
      return Optional.empty();
    }
    Optional<String> type = t.string("type", true);
    if (type.isEmpty()) {
      return Optional.empty();
    }
    if (!TYPES.contains(type.get())) {
      t.problem(
          "unknown component type '"
              + type.get()
              + "'; use one of "
              + new java.util.TreeSet<>(TYPES));
      return Optional.empty();
    }
    Map<String, Arg> values = new LinkedHashMap<>();
    List<String> requires = t.strings("requires_modules");
    if (!requires.isEmpty()) {
      values.put("requires_modules", new Arg.Literal(requires));
    }
    value(t, "visible", false, values);
    width(t, values);
    List<Map<String, Arg>> columns = new ArrayList<>();
    List<Map<String, Arg>> fields = new ArrayList<>();
    List<Action> actions = new ArrayList<>();
    Optional<Select> select = Optional.empty();
    List<Component> children = new ArrayList<>();
    switch (type.get()) {
      case "text" -> {
        t.onlyKeys(Set.of("type", "value", "style", "visible", "width", "requires_modules"));
        value(t, "value", true, values);
        literal(t, "style", STYLES, values);
      }
      case "table" -> {
        t.onlyKeys(
            Set.of(
                "type",
                "rows",
                "empty",
                "columns",
                "select",
                "row_action",
                "emphasis",
                "drag",
                "drop",
                "visible",
                "width",
                "requires_modules"));
        value(t, "emphasis", false, values);
        value(t, "rows", true, values);
        literal(t, "empty", null, values);
        columns.addAll(entries(t, "columns", Set.of("title", "value", "format"), true));
        rowActions(t, actions);
        select = select(t, "select");
        dragAndDrop(t, values);
      }
      case "list" -> {
        t.onlyKeys(
            Set.of(
                "type",
                "items",
                "title",
                "subtitle",
                "empty",
                "select",
                "row_action",
                "emphasis",
                "drag",
                "drop",
                "visible",
                "width",
                "requires_modules"));
        value(t, "emphasis", false, values);
        value(t, "items", true, values);
        value(t, "title", true, values);
        value(t, "subtitle", false, values);
        literal(t, "empty", null, values);
        rowActions(t, actions);
        select = select(t, "select");
        dragAndDrop(t, values);
      }
      case "detail" -> {
        t.onlyKeys(Set.of("type", "data", "fields", "visible", "width", "requires_modules"));
        value(t, "data", false, values);
        fields.addAll(entries(t, "fields", Set.of("label", "value", "format"), true));
      }
      case "button" -> {
        t.onlyKeys(
            Set.of(
                "type",
                "label",
                "enabled",
                "command",
                "args",
                "confirm",
                "sets",
                "download",
                "opens",
                "key",
                "visible",
                "width",
                "requires_modules"));
        literal(t, "label", null, values);
        key(t, values);
        value(t, "enabled", false, values);
        select = select(t, "sets");
        Optional<Download> download = download(t);
        Optional<Opens> opens = opens(t);
        if (opens.isPresent()) {
          if (t.has("command") || download.isPresent()) {
            t.problem("a button starts a command, downloads a file or opens a view: only one");
          }
          actions.add(
              new Action(
                  t.string("label", false).orElse("Open"),
                  "",
                  Map.of(),
                  Optional.empty(),
                  Optional.empty(),
                  opens));
        } else if (download.isPresent()) {
          if (t.has("command")) {
            t.problem("a button starts a command or downloads a file, not both");
          }
          actions.add(
              new Action(
                  t.string("label", false).orElse("Save"),
                  "",
                  Map.of(),
                  t.string("confirm", false),
                  download));
        } else {
          Optional<String> command = t.string("command", select.isEmpty());
          command.ifPresent(
              c ->
                  actions.add(
                      new Action(
                          t.string("label", false).orElse(c),
                          c,
                          args(t),
                          t.string("confirm", false))));
        }
      }
      case "richtext" -> {
        t.onlyKeys(
            Set.of(
                "type",
                "blocks",
                "blocked",
                "images_param",
                "visible",
                "width",
                "requires_modules"));
        value(t, "blocks", true, values);
        value(t, "blocked", false, values);
        literal(t, "images_param", null, values);
      }
      case "input" -> {
        t.onlyKeys(
            Set.of(
                "type",
                "param",
                "placeholder",
                "key",
                "dictate",
                "visible",
                "width",
                "requires_modules"));
        key(t, values);
        literal(t, "param", null, values);
        literal(t, "placeholder", null, values);
        // a microphone button: a person speaks, a read-only capability gives the text
        t.table("dictate", false)
            .ifPresent(
                d -> {
                  d.onlyKeys(Set.of("capability", "args", "requires_modules"));
                  List<String> needs = d.strings("requires_modules");
                  if (!needs.isEmpty()) {
                    values.put("dictate.requires_modules", new Arg.Literal(needs));
                  }
                  d.string("capability", true)
                      .ifPresent(c -> values.put("dictate.capability", new Arg.Literal(c)));
                  d.table("args", false)
                      .ifPresent(
                          a ->
                              a.rawEntries()
                                  .forEach(
                                      (name, v) -> {
                                        if (v instanceof Map<?, ?>) {
                                          a.problem("'" + name + "' is a value, not an expression");
                                        } else {
                                          values.put("dictate.arg." + name, new Arg.Literal(v));
                                        }
                                      }));
                });
        if (!t.has("param")) {
          t.problem("missing 'param'");
        }
      }
      case "form" -> {
        t.onlyKeys(
            Set.of(
                "type",
                "command",
                "defaults",
                "submit_label",
                "also",
                "lookup",
                "done",
                "visible",
                "width",
                "requires_modules"));
        literal(t, "command", null, values);
        literal(t, "submit_label", null, values);
        literal(t, "done", null, values); // the view to show after a button worked
        // more buttons that start other commands with the same fields
        for (Table a : t.tables("also")) {
          a.onlyKeys(Set.of("label", "command"));
          Optional<String> label = a.string("label", true);
          Optional<String> command = a.string("command", true);
          if (label.isPresent() && command.isPresent()) {
            actions.add(new Action(label.get(), command.get(), Map.of(), Optional.empty()));
          }
        }
        // where the fields with the widget "recipients" get their suggestions from
        t.table("lookup", false)
            .ifPresent(
                l -> {
                  l.onlyKeys(Set.of("capability", "arg", "requires_modules"));
                  List<String> lookupNeeds = l.strings("requires_modules");
                  if (!lookupNeeds.isEmpty()) {
                    values.put("lookup.requires_modules", new Arg.Literal(lookupNeeds));
                  }
                  Optional<String> capability = l.string("capability", true);
                  capability.ifPresent(c -> values.put("lookup.capability", new Arg.Literal(c)));
                  values.put("lookup.arg", new Arg.Literal(l.string("arg", false).orElse("query")));
                });
        t.table("defaults", false)
            .ifPresent(
                d ->
                    d.rawEntries()
                        .forEach(
                            (name, v) ->
                                values.put("default." + name, RulesetLoader.arg(d, name, v))));
        if (!t.has("command")) {
          t.problem("missing 'command'");
        }
      }
      default -> {
        t.onlyKeys(Set.of("type", "children", "visible", "width", "requires_modules"));
        if (depth >= MAX_DEPTH) {
          t.problem("containers are nested at most " + MAX_DEPTH + " deep");
        } else {
          for (Table c : t.tables("children")) {
            component(c, depth + 1, count).ifPresent(children::add);
          }
        }
        if (children.isEmpty() && !t.has("children")) {
          t.problem("a " + type.get() + " needs [[component.children]]");
        }
      }
    }
    return Optional.of(
        new Component(type.get(), values, columns, fields, actions, select, children));
  }

  /** The key that triggers a button or focuses an input: one printable character, or a name. */
  static final Set<String> KEY_NAMES = Set.of("Delete", "Backspace", "Enter", "Escape", "Insert");

  private static void key(Table t, Map<String, Arg> out) {
    t.string("key", false)
        .ifPresent(
            k -> {
              boolean single =
                  k.codePointCount(0, k.length()) == 1 && !Character.isWhitespace(k.codePointAt(0));
              if (single || KEY_NAMES.contains(k)) {
                out.put("key", new Arg.Literal(k));
              } else {
                t.problem(
                    "key must be one character or one of " + new java.util.TreeSet<>(KEY_NAMES));
              }
            });
  }

  /**
   * {@code drag = <expression>} makes the rows draggable, with this value (in the scope of the
   * row); {@code drop = { command, param, args }} lets a row accept a dragged row: the command is
   * started with the arguments (in the scope of the row that accepts) and the dragged value as
   * {@code param}.
   */
  private static void dragAndDrop(Table t, Map<String, Arg> values) {
    value(t, "drag", false, values);
    t.table("drop", false)
        .ifPresent(
            d -> {
              d.onlyKeys(Set.of("command", "param", "args"));
              Optional<String> command = d.string("command", true);
              Optional<String> param = d.string("param", true);
              if (command.isPresent() && param.isPresent()) {
                values.put("drop.command", new Arg.Literal(command.get()));
                values.put("drop.param", new Arg.Literal(param.get()));
                args(d).forEach((name, arg) -> values.put("drop.arg." + name, arg));
              }
            });
  }

  private static void value(Table t, String key, boolean required, Map<String, Arg> out) {
    Optional<Object> raw = t.raw(key);
    if (raw.isEmpty()) {
      if (required) {
        t.problem("missing '" + key + "'");
      }
      return;
    }
    out.put(key, RulesetLoader.arg(t, key, raw.get()));
  }

  /** {@code width}: the preferred width in pixels, a whole number from 40 to 4000. */
  private static void width(Table t, Map<String, Arg> out) {
    Optional<Object> raw = t.raw("width");
    if (raw.isEmpty()) {
      return;
    }
    if (raw.get() instanceof Number n && n.longValue() >= 40 && n.longValue() <= 4000) {
      out.put("width", new Arg.Literal(n.longValue()));
    } else {
      t.problem("'width' must be a whole number of pixels from 40 to 4000");
    }
  }

  /** An attribute that must be a plain string, optionally one of a fixed set. */
  private static void literal(Table t, String key, Set<String> allowed, Map<String, Arg> out) {
    Optional<String> s = t.string(key, false);
    if (s.isEmpty()) {
      return;
    }
    if (allowed != null && !allowed.contains(s.get())) {
      t.problem("'" + key + "' must be one of " + new java.util.TreeSet<>(allowed));
      return;
    }
    out.put(key, new Arg.Literal(s.get()));
  }

  private static List<Map<String, Arg>> entries(
      Table t, String key, Set<String> allowed, boolean required) {
    List<Map<String, Arg>> out = new ArrayList<>();
    Optional<Object> raw = t.raw(key);
    if (raw.isEmpty()) {
      if (required) {
        t.problem("missing '" + key + "'");
      }
      return out;
    }
    if (!(raw.get() instanceof List<?> list) || list.isEmpty()) {
      t.problem("'" + key + "' must be a list of tables");
      return out;
    }
    int i = 0;
    for (Object item : list) {
      Table e =
          new Table(castMap(item), t.location() + ", " + key + "[" + i++ + "]", problemsOf(t));
      if (!(item instanceof Map<?, ?>)) {
        t.problem("'" + key + "' must be a list of tables");
        continue;
      }
      e.onlyKeys(allowed);
      Map<String, Arg> entry = new LinkedHashMap<>();
      for (String name : allowed) {
        Optional<Object> v = e.raw(name);
        if (v.isPresent()) {
          if (name.equals("title") || name.equals("label")) {
            e.string(name, false).ifPresent(s -> entry.put(name, new Arg.Literal(s)));
          } else if (name.equals("format")) {
            literal(e, name, FORMATS, entry);
          } else {
            entry.put(name, RulesetLoader.arg(e, name, v.get()));
          }
        }
      }
      if (!entry.containsKey("value")) {
        e.problem("missing 'value'");
      }
      out.add(entry);
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> castMap(Object o) {
    return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
  }

  private static List<Problem> problemsOf(Table t) {
    return t.problems();
  }

  private static void rowActions(Table t, List<Action> out) {
    for (Table a : t.tables("row_action")) {
      a.onlyKeys(Set.of("label", "command", "args", "confirm", "download", "opens"));
      Optional<String> label = a.string("label", true);
      Optional<Opens> opens = opens(a);
      if (opens.isPresent()) {
        if (a.has("command") || a.has("download")) {
          a.problem("an action starts a command, downloads a file or opens a view: only one");
        }
        label.ifPresent(
            l -> out.add(new Action(l, "", Map.of(), Optional.empty(), Optional.empty(), opens)));
        continue;
      }
      Optional<Download> download = download(a);
      if (download.isPresent()) {
        if (a.has("command")) {
          a.problem("an action starts a command or downloads a file, not both");
        }
        label.ifPresent(
            l -> out.add(new Action(l, "", Map.of(), a.string("confirm", false), download)));
        continue;
      }
      Optional<String> command = a.string("command", true);
      if (label.isPresent() && command.isPresent()) {
        out.add(new Action(label.get(), command.get(), args(a), a.string("confirm", false)));
      }
    }
  }

  /** The {@code opens = { view, params }} of an action, if it has one. */
  private static Optional<Opens> opens(Table t) {
    Optional<Table> o = t.table("opens", false);
    if (o.isEmpty()) {
      return Optional.empty();
    }
    o.get().onlyKeys(Set.of("view", "params"));
    Optional<String> view = o.get().string("view", true);
    Map<String, Arg> params = new LinkedHashMap<>();
    o.get()
        .table("params", false)
        .ifPresent(
            p ->
                p.rawEntries()
                    .forEach((name, value) -> params.put(name, RulesetLoader.arg(p, name, value))));
    return view.map(v -> new Opens(v, params));
  }

  /** The {@code download = { capability, args, filename }} of an action, if it has one. */
  private static Optional<Download> download(Table t) {
    Optional<Table> d = t.table("download", false);
    if (d.isEmpty()) {
      return Optional.empty();
    }
    d.get().onlyKeys(Set.of("capability", "args", "filename"));
    Optional<String> capability = d.get().string("capability", true);
    Optional<Object> filename = d.get().raw("filename");
    if (filename.isEmpty()) {
      d.get().problem("missing 'filename'");
    }
    if (capability.isEmpty() || filename.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Download(
            capability.get(),
            args(d.get()),
            RulesetLoader.arg(d.get(), "filename", filename.get())));
  }

  private static Optional<Select> select(Table t, String key) {
    Optional<Table> s = t.table(key, false);
    if (s.isEmpty()) {
      return Optional.empty();
    }
    s.get().onlyKeys(Set.of("param", "value", "clear", "then"));
    Optional<String> param = s.get().string("param", true);
    Optional<Object> value = s.get().raw("value");
    if (value.isEmpty()) {
      s.get().problem("missing 'value'");
    }
    Optional<Action> then = Optional.empty();
    Optional<Table> thenTable = s.get().table("then", false);
    if (thenTable.isPresent()) {
      Table th = thenTable.get();
      th.onlyKeys(Set.of("command", "args"));
      Optional<String> command = th.string("command", true);
      if (command.isPresent()) {
        then = Optional.of(new Action(command.get(), command.get(), args(th), Optional.empty()));
      }
    }
    if (param.isEmpty() || value.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        new Select(
            param.get(),
            RulesetLoader.arg(s.get(), "value", value.get()),
            s.get().strings("clear"),
            then));
  }
}
