// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.engine.AuditLog;
import it.uhde.praksis.expression.Bindings;
import it.uhde.praksis.expression.Expression;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Response.ViewComponent;
import it.uhde.praksis.ruleset.Arg;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.ParamDecl;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.Trigger;
import it.uhde.praksis.ruleset.View;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a view into a description that contains only values: runs its sources, which are read-only
 * capability calls checked against the grants of the layer that owns the view, evaluates the
 * expressions, and builds the components. The frontend never runs an expression or a query. A
 * source that fails does not fail the view: it is reported, and the components that depend on it
 * say so.
 */
final class ViewResolver {
  private final Ruleset ruleset;
  private final Map<String, String> owners;
  private final ModuleCatalog catalog;
  private final ExpressionCompiler compiler;
  private final Map<String, Capability> capabilities;
  private final AccessPolicy access;
  private final AuditLog audit;
  private final Clock clock;
  private final Map<String, Expression> compiled = new ConcurrentHashMap<>();

  ViewResolver(
      Ruleset ruleset,
      Map<String, String> owners,
      ModuleCatalog catalog,
      ExpressionCompiler compiler,
      Map<String, Capability> capabilities,
      AccessPolicy access,
      AuditLog audit,
      Clock clock) {
    this.ruleset = ruleset;
    this.owners = owners;
    this.catalog = catalog;
    this.compiler = compiler;
    this.capabilities = capabilities;
    this.access = access;
    this.audit = audit;
    this.clock = clock;
  }

  /** The views of the ruleset. */
  Response.ViewList list() {
    List<Response.ViewList.ViewInfo> out = new ArrayList<>();
    for (View v : ruleset.views()) {
      out.add(
          new Response.ViewList.ViewInfo(
              v.id(),
              v.title(),
              v.description().orElse(null),
              v.schema(),
              owners.getOrDefault(v.id(), ""),
              v.params().stream()
                  .map(
                      p ->
                          new Response.ViewList.Param(
                              p.name(), p.type().id(), show(p.defaultValue().orElse(null))))
                  .toList()));
    }
    return new Response.ViewList(out);
  }

  /** A view with its data, or an error that says why not. */
  Response resolve(String id, Map<String, Object> given, int maxSchema) {
    View view = ruleset.views().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    if (view == null) {
      return new Response.Error("there is no view '" + id + "'");
    }
    if (maxSchema > 0 && view.schema() > maxSchema) {
      return new Response.Error(
          "view '"
              + id
              + "' is written for UI schema "
              + view.schema()
              + " but this frontend understands up to "
              + maxSchema);
    }
    Map<String, Object> state = new LinkedHashMap<>();
    List<String> problems = new ArrayList<>();
    for (ParamDecl p : view.params()) {
      Object value = given.get(p.name());
      try {
        state.put(
            p.name(),
            value == null
                ? p.defaultValue().orElse(zero(p.type()))
                : Injection.coerce(new FieldDescriptor(p.name(), p.type(), false, false), value));
      } catch (IllegalArgumentException e) {
        return new Response.Error("parameter '" + p.name() + "': " + e.getMessage());
      }
    }
    for (String name : given.keySet()) {
      if (view.params().stream().noneMatch(p -> p.name().equals(name))) {
        return new Response.Error("view '" + id + "' has no parameter '" + name + "'");
      }
    }

    Map<String, Object> ruleParams = new HashMap<>();
    ruleset.params().forEach(p -> p.defaultValue().ifPresent(v -> ruleParams.put(p.name(), v)));
    Map<String, Object> dynamic = new HashMap<>();
    dynamic.put("view", state);
    Map<String, Object> sources = new LinkedHashMap<>();
    dynamic.put("source", sources); // filled one by one: a source may use the ones before it
    String layer = owners.getOrDefault(id, "?");
    for (var source : view.sources().entrySet()) {
      sources.put(
          source.getKey(),
          wanted(view, source.getValue(), dynamic, ruleParams, problems, source.getKey())
              ? read(view, source.getKey(), source.getValue(), dynamic, ruleParams, problems)
              : Map.of());
    }
    dynamic.put("source", sources);

    List<ViewComponent> components = new ArrayList<>();
    for (View.Component c : view.components()) {
      ViewComponent built = component(view, c, dynamic, ruleParams);
      if (built != null) {
        components.add(built);
      }
    }
    List<Response.ViewList.Param> shown = new ArrayList<>();
    for (ParamDecl p : view.params()) {
      shown.add(new Response.ViewList.Param(p.name(), p.type().id(), show(state.get(p.name()))));
    }
    return new Response.ResolvedView(
        view.id(), view.title(), view.schema(), shown, components, problems);
  }

  /**
   * The file of a download, because a person asked for it: the view must offer this download, and
   * the capability is read under the grants of the layer that owns the view, with an audit line.
   */
  Response fetch(
      String id,
      String capabilityName,
      Map<String, Object> given,
      String suggested,
      java.nio.file.Path directory) {
    View view = ruleset.views().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    if (view == null) {
      return new Response.Error("there is no view '" + id + "'");
    }
    if (!offers(view.components(), capabilityName)) {
      return new Response.Error("view '" + id + "' has no download of '" + capabilityName + "'");
    }
    CapabilityDescriptor descriptor = catalog.capability(capabilityName).orElse(null);
    Capability capability = capabilities.get(capabilityName);
    if (descriptor == null || capability == null) {
      return new Response.Error("capability '" + capabilityName + "' is not available");
    }
    Map<String, Object> args = new LinkedHashMap<>();
    try {
      for (var arg : given.entrySet()) {
        FieldDescriptor field =
            descriptor.params().stream()
                .filter(p -> p.name().equals(arg.getKey()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "'" + capabilityName + "' has no argument '" + arg.getKey() + "'"));
        args.put(arg.getKey(), Injection.coerce(field, arg.getValue()));
      }
    } catch (IllegalArgumentException e) {
      return new Response.Error(e.getMessage());
    }
    String component = "view:" + view.id();
    AccessPolicy.Decision decision = access.check(component, descriptor, args);
    audit.record(
        clock.instant(),
        component,
        capabilityName,
        args,
        decision.allowed(),
        decision.allowed() ? Optional.empty() : Optional.of(decision.reason()));
    if (!decision.allowed()) {
      return new Response.Error(decision.reason());
    }
    try {
      Map<String, Object> result =
          it.uhde.praksis.engine.ModuleCalls.invoke(
              capability, new ReadContext(component + "/download"), args);
      if (result == null || !(result.get("content") instanceof byte[] content)) {
        return new Response.Error("'" + capabilityName + "' gave no content");
      }
      if (content.length > Downloads.MAX_BYTES) {
        return new Response.Error("the file is too big: " + content.length + " bytes");
      }
      Downloads.cleanup(directory, clock.instant());
      java.nio.file.Path file = Downloads.store(directory, content);
      return new Response.Fetched(
          file.toString(),
          Downloads.cleanName(
              suggested != null && !suggested.isBlank()
                  ? suggested
                  : String.valueOf(result.getOrDefault("name", ""))),
          String.valueOf(result.getOrDefault("type", "application/octet-stream")),
          content.length);
    } catch (Exception e) {
      return new Response.Error(String.valueOf(e.getMessage()));
    }
  }

  /**
   * Suggestions for what a person types into a field of a form: the capability of a lookup of the
   * view, read under the grants of the layer that owns the view. A refusal is an audit line; a
   * lookup that works is not, because it is asked for as a person types.
   */
  Response lookup(String id, String capabilityName, Map<String, Object> given) {
    View view = ruleset.views().stream().filter(v -> v.id().equals(id)).findFirst().orElse(null);
    if (view == null) {
      return new Response.Error("there is no view '" + id + "'");
    }
    if (!hasLookup(view.components(), capabilityName)) {
      return new Response.Error("view '" + id + "' has no lookup of '" + capabilityName + "'");
    }
    CapabilityDescriptor descriptor = catalog.capability(capabilityName).orElse(null);
    Capability capability = capabilities.get(capabilityName);
    if (descriptor == null || capability == null) {
      return new Response.Error("capability '" + capabilityName + "' is not available");
    }
    Map<String, Object> args = new LinkedHashMap<>();
    try {
      for (var arg : given.entrySet()) {
        FieldDescriptor field =
            descriptor.params().stream()
                .filter(p -> p.name().equals(arg.getKey()))
                .findFirst()
                .orElseThrow(
                    () ->
                        new IllegalArgumentException(
                            "'" + capabilityName + "' has no argument '" + arg.getKey() + "'"));
        args.put(arg.getKey(), Injection.coerce(field, arg.getValue()));
      }
    } catch (IllegalArgumentException e) {
      return new Response.Error(e.getMessage());
    }
    String component = "view:" + view.id();
    AccessPolicy.Decision decision = access.check(component, descriptor, args);
    if (!decision.allowed()) {
      audit.record(
          clock.instant(), component, capabilityName, args, false, Optional.of(decision.reason()));
      return new Response.Error(decision.reason());
    }
    try {
      Map<String, Object> result =
          it.uhde.praksis.engine.ModuleCalls.invoke(
              capability, new ReadContext(component + "/lookup"), args);
      List<Map<String, Object>> items = new ArrayList<>();
      if (result != null && result.get("items") instanceof List<?> list) {
        for (Object o : list) {
          if (items.size() >= 20) {
            break;
          }
          if (o instanceof Map<?, ?> m) {
            Map<String, Object> item = new LinkedHashMap<>();
            m.forEach((k, v) -> item.put(String.valueOf(k), show(v)));
            items.add(item);
          }
        }
      }
      return new Response.Suggestions(items);
    } catch (Exception e) {
      return new Response.Error(String.valueOf(e.getMessage()));
    }
  }

  private static boolean hasLookup(List<View.Component> components, String capability) {
    for (View.Component c : components) {
      if (c.values().get("lookup.capability") instanceof Arg.Literal l
          && String.valueOf(l.value()).equals(capability)) {
        return true;
      }
      if (hasLookup(c.children(), capability)) {
        return true;
      }
    }
    return false;
  }

  private static boolean offers(List<View.Component> components, String capability) {
    for (View.Component c : components) {
      for (View.Action a : c.actions()) {
        if (a.download().isPresent() && a.download().get().capability().equals(capability)) {
          return true;
        }
      }
      if (offers(c.children(), capability)) {
        return true;
      }
    }
    return false;
  }

  /** Whether the source is to be read at all: its {@code when} is true, or it has none. */
  private boolean wanted(
      View view,
      View.Source source,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      List<String> problems,
      String name) {
    if (source.when().isEmpty()) {
      return true;
    }
    try {
      return Boolean.TRUE.equals(
          evaluate(view, source.when().get(), dynamic, ruleParams, Set.of("view", "source")));
    } catch (ExpressionException e) {
      problems.add("source '" + name + "': when: " + e.getMessage());
      return false;
    }
  }

  /** One source: its call, if the grants of the owner layer allow it. */
  private Object read(
      View view,
      String name,
      View.Source source,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      List<String> problems) {
    String at = "source '" + name + "'";
    CapabilityDescriptor descriptor = catalog.capability(source.capability()).orElse(null);
    Capability capability = capabilities.get(source.capability());
    if (descriptor == null || capability == null) {
      problems.add(at + ": capability '" + source.capability() + "' is not available");
      return Map.of();
    }
    Map<String, Object> args = new LinkedHashMap<>();
    try {
      for (var arg : source.args().entrySet()) {
        FieldDescriptor field =
            descriptor.params().stream()
                .filter(p -> p.name().equals(arg.getKey()))
                .findFirst()
                .get();
        Object value =
            switch (arg.getValue()) {
              case Arg.Literal l -> l.value();
              case Arg.Expr e ->
                  evaluate(view, e.source(), dynamic, ruleParams, Set.of("view", "source"));
            };
        args.put(arg.getKey(), Injection.coerce(field, value));
      }
    } catch (ExpressionException | IllegalArgumentException e) {
      problems.add(at + ": " + e.getMessage());
      return Map.of();
    }
    AccessPolicy.Decision decision = access.check("view:" + view.id(), descriptor, args);
    if (!decision.allowed()) {
      audit.record(
          clock.instant(),
          "view:" + view.id(),
          source.capability(),
          args,
          false,
          Optional.of(decision.reason()));
      problems.add(at + ": " + decision.reason());
      return Map.of();
    }
    try {
      Map<String, Object> result =
          it.uhde.praksis.engine.ModuleCalls.invoke(
              capability, new ReadContext("view:" + view.id() + "/" + name), args);
      return result == null ? Map.of() : result;
    } catch (Exception e) {
      problems.add(at + ": " + e.getMessage());
      return Map.of();
    }
  }

  /** The part, or null if its {@code visible} is false. */
  private ViewComponent component(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams) {
    try {
      if (c.values().containsKey("visible")
          && !Boolean.TRUE.equals(
              evaluate(
                  view,
                  c.values().get("visible"),
                  dynamic,
                  ruleParams,
                  Set.of("view", "source")))) {
        return null;
      }
    } catch (ExpressionException | RuntimeException e) {
      return error(c.type(), "visible: " + e.getMessage());
    }
    ViewComponent built = build(view, c, dynamic, ruleParams);
    if (built.error() != null) {
      return built;
    }
    Integer width =
        c.values().get("width") instanceof Arg.Literal l && l.value() instanceof Number n
            ? n.intValue()
            : null;
    String key = c.values().get("key") instanceof Arg.Literal l ? String.valueOf(l.value()) : null;
    return width == null && key == null ? built : with(built, width, key);
  }

  /** The part with a width and a key; nothing else about it changes. */
  private static ViewComponent with(ViewComponent c, Integer width, String key) {
    return new ViewComponent(
        c.type(),
        c.text(),
        c.style(),
        c.label(),
        c.enabled(),
        c.empty(),
        c.columns(),
        c.rows(),
        c.fields(),
        c.actions(),
        c.form(),
        c.children(),
        c.error(),
        width != null ? width : c.width(),
        c.select(),
        c.rich(),
        key != null ? key : c.key());
  }

  private ViewComponent build(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams) {
    try {
      return switch (c.type()) {
        case "text" ->
            new ViewComponent(
                "text",
                text(value(view, c, "value", dynamic, ruleParams, Set.of("view", "source"))),
                literal(c, "style", "normal"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
        case "table", "list" -> rows(view, c, dynamic, ruleParams);
        case "detail" -> detail(view, c, dynamic, ruleParams);
        case "button" -> button(view, c, dynamic, ruleParams);
        case "form" -> form(view, c, dynamic, ruleParams);
        case "input" -> input(c, dynamic);
        case "richtext" -> richtext(view, c, dynamic, ruleParams);
        default -> {
          List<ViewComponent> children = new ArrayList<>();
          c.children()
              .forEach(
                  child -> {
                    ViewComponent built = component(view, child, dynamic, ruleParams);
                    if (built != null) {
                      children.add(built);
                    }
                  });
          yield new ViewComponent(
              c.type(), null, null, null, null, null, null, null, null, null, null, children, null);
        }
      };
    } catch (ExpressionException | RuntimeException e) {
      return error(c.type(), e.getMessage());
    }
  }

  private static ViewComponent error(String type, String message) {
    return new ViewComponent(
        type,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        message == null ? "cannot be shown" : message);
  }

  private ViewComponent rows(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams)
      throws ExpressionException {
    boolean table = c.type().equals("table");
    Object items =
        value(view, c, table ? "rows" : "items", dynamic, ruleParams, Set.of("view", "source"));
    if (!(items instanceof List<?> list)) {
      throw new ExpressionException("must be a list, not " + describe(items));
    }
    List<ViewComponent.Column> columns = new ArrayList<>();
    if (table) {
      for (Map<String, Arg> col : c.columns()) {
        columns.add(
            new ViewComponent.Column(
                literalText(col.get("title")), literalText(col.getOrDefault("format", null))));
      }
    } else {
      columns.add(new ViewComponent.Column("", "text"));
      columns.add(new ViewComponent.Column("", "text"));
    }
    List<ViewComponent.Row> rows = new ArrayList<>();
    for (Object item : list) {
      Map<String, Object> inRow = new HashMap<>(dynamic);
      inRow.put("item", item);
      List<Object> cells = new ArrayList<>();
      if (table) {
        for (Map<String, Arg> col : c.columns()) {
          cells.add(
              show(
                  evaluate(
                      view,
                      col.get("value"),
                      inRow,
                      ruleParams,
                      Set.of("view", "source", "item"))));
        }
      } else {
        cells.add(
            show(value(view, c, "title", inRow, ruleParams, Set.of("view", "source", "item"))));
        cells.add(
            c.values().containsKey("subtitle")
                ? show(
                    value(view, c, "subtitle", inRow, ruleParams, Set.of("view", "source", "item")))
                : "");
      }
      ViewComponent.Select select = null;
      if (c.select().isPresent()) {
        select =
            select(view, c.select().get(), inRow, ruleParams, Set.of("view", "source", "item"));
      }
      boolean emphasis =
          c.values().containsKey("emphasis")
              && Boolean.TRUE.equals(
                  evaluate(
                      view,
                      c.values().get("emphasis"),
                      inRow,
                      ruleParams,
                      Set.of("view", "source", "item")));
      List<ViewComponent.Action> actions = new ArrayList<>();
      for (View.Action a : c.actions()) {
        actions.add(action(view, a, inRow, ruleParams, Set.of("view", "source", "item")));
      }
      Object drag =
          c.values().containsKey("drag")
              ? show(
                  evaluate(
                      view,
                      c.values().get("drag"),
                      inRow,
                      ruleParams,
                      Set.of("view", "source", "item")))
              : null;
      ViewComponent.Drop drop = null;
      if (c.values().get("drop.command") instanceof Arg.Literal command) {
        Map<String, Object> dropArgs = new LinkedHashMap<>();
        for (var v : c.values().entrySet()) {
          if (v.getKey().startsWith("drop.arg.")) {
            dropArgs.put(
                v.getKey().substring("drop.arg.".length()),
                show(
                    evaluate(
                        view, v.getValue(), inRow, ruleParams, Set.of("view", "source", "item"))));
          }
        }
        drop =
            new ViewComponent.Drop(
                String.valueOf(command.value()), literal(c, "drop.param", ""), dropArgs);
      }
      rows.add(new ViewComponent.Row(cells, select, actions, emphasis, drag, drop));
    }
    return new ViewComponent(
        c.type(),
        null,
        null,
        null,
        null,
        literal(c, "empty", null),
        columns,
        rows,
        null,
        null,
        null,
        null,
        null);
  }

  private ViewComponent detail(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams)
      throws ExpressionException {
    Map<String, Object> scope = new HashMap<>(dynamic);
    Set<String> names = new java.util.HashSet<>(Set.of("view", "source"));
    List<ViewComponent.Field> fields = new ArrayList<>();
    for (Map<String, Arg> f : c.fields()) {
      fields.add(
          new ViewComponent.Field(
              literalText(f.get("label")),
              literalText(f.get("format")),
              show(evaluate(view, f.get("value"), scope, ruleParams, names))));
    }
    return new ViewComponent(
        "detail", null, null, null, null, null, null, null, fields, null, null, null, null);
  }

  private ViewComponent button(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams)
      throws ExpressionException {
    Set<String> names = Set.of("view", "source");
    boolean enabled = true;
    if (c.values().containsKey("enabled")) {
      enabled = Boolean.TRUE.equals(value(view, c, "enabled", dynamic, ruleParams, names));
    }
    List<ViewComponent.Action> actions = new ArrayList<>();
    for (View.Action a : c.actions()) {
      actions.add(action(view, a, dynamic, ruleParams, names));
    }
    ViewComponent.Select sets =
        c.select().isPresent() ? select(view, c.select().get(), dynamic, ruleParams, names) : null;
    return new ViewComponent(
        "button",
        null,
        null,
        literal(c, "label", null),
        enabled,
        null,
        null,
        null,
        null,
        actions,
        null,
        null,
        null,
        null,
        sets);
  }

  private ViewComponent richtext(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams)
      throws ExpressionException {
    Set<String> names = Set.of("view", "source");
    Object blocks = evaluate(view, c.values().get("blocks"), dynamic, ruleParams, names);
    Object blocked =
        c.values().containsKey("blocked")
            ? evaluate(view, c.values().get("blocked"), dynamic, ruleParams, names)
            : 0L;
    ViewComponent.Rich rich = RichText.convert(blocks, blocked, literal(c, "images_param", null));
    return new ViewComponent(
        "richtext",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        rich);
  }

  private ViewComponent input(View.Component c, Map<String, Object> dynamic) {
    String param = literal(c, "param", "");
    Object current = dynamic.get("view") instanceof Map<?, ?> m ? m.get(param) : null;
    return new ViewComponent(
        "input",
        current == null ? "" : String.valueOf(current),
        null,
        literal(c, "placeholder", null),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new ViewComponent.Select(param, null));
  }

  private ViewComponent.Select select(
      View view,
      View.Select s,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      Set<String> names)
      throws ExpressionException {
    return new ViewComponent.Select(
        s.param(),
        show(evaluate(view, s.value(), dynamic, ruleParams, names)),
        s.clear(),
        s.then().isPresent() ? action(view, s.then().get(), dynamic, ruleParams, names) : null);
  }

  private ViewComponent form(
      View view, View.Component c, Map<String, Object> dynamic, Map<String, Object> ruleParams)
      throws ExpressionException {
    String id = literal(c, "command", "");
    it.uhde.praksis.ruleset.Rule rule =
        ruleset.rules().stream().filter(r -> r.id().equals(id)).findFirst().orElse(null);
    if (rule == null || !(rule.trigger() instanceof Trigger.OnCommand command)) {
      throw new ExpressionException("there is no command '" + id + "'");
    }
    List<ViewComponent.FormField> fields = new ArrayList<>();
    for (Trigger.CommandParam p : command.params()) {
      Arg given = c.values().get("default." + p.name());
      Object value =
          given != null
              ? evaluate(view, given, dynamic, ruleParams, Set.of("view", "source"))
              : p.defaultValue().orElse(null);
      fields.add(
          new ViewComponent.FormField(
              p.name(),
              p.type().id(),
              p.required(),
              p.label().orElse(null),
              show(value),
              p.widget().orElse(null)));
    }
    List<ViewComponent.Submit> others = new ArrayList<>();
    for (View.Action a : c.actions()) {
      others.add(new ViewComponent.Submit(a.label(), a.command()));
    }
    ViewComponent.Lookup lookup =
        c.values().containsKey("lookup.capability")
            ? new ViewComponent.Lookup(
                literal(c, "lookup.capability", ""), literal(c, "lookup.arg", "query"))
            : null;
    return new ViewComponent(
        "form",
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        new ViewComponent.Form(
            id,
            command.title(),
            literal(c, "submit_label", command.title()),
            fields,
            others,
            lookup,
            c.values().containsKey("done") ? literal(c, "done", null) : null),
        null,
        null);
  }

  private ViewComponent.Action action(
      View view,
      View.Action a,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      Set<String> names)
      throws ExpressionException {
    if (a.opens().isPresent()) {
      View.Opens o = a.opens().get();
      Map<String, Object> params = new LinkedHashMap<>();
      for (var param : o.params().entrySet()) {
        params.put(
            param.getKey(), show(evaluate(view, param.getValue(), dynamic, ruleParams, names)));
      }
      return new ViewComponent.Action(
          a.label(), "", Map.of(), null, null, new ViewComponent.Opens(o.view(), params));
    }
    if (a.download().isPresent()) {
      View.Download d = a.download().get();
      Map<String, Object> given = new LinkedHashMap<>();
      for (var arg : d.args().entrySet()) {
        given.put(arg.getKey(), show(evaluate(view, arg.getValue(), dynamic, ruleParams, names)));
      }
      String filename = text(evaluate(view, d.filename(), dynamic, ruleParams, names));
      return new ViewComponent.Action(
          a.label(),
          "",
          Map.of(),
          a.confirm().orElse(null),
          new ViewComponent.Download(d.capability(), given, filename));
    }
    Map<String, Object> args = new LinkedHashMap<>();
    for (var arg : a.args().entrySet()) {
      args.put(arg.getKey(), show(evaluate(view, arg.getValue(), dynamic, ruleParams, names)));
    }
    return new ViewComponent.Action(a.label(), a.command(), args, a.confirm().orElse(null));
  }

  // ---------------------------------------------------------------- expressions and values

  private Object value(
      View view,
      View.Component c,
      String key,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      Set<String> names)
      throws ExpressionException {
    Arg arg = c.values().get(key);
    return arg == null ? null : evaluate(view, arg, dynamic, ruleParams, names);
  }

  private Object evaluate(
      View view,
      Arg arg,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      Set<String> names)
      throws ExpressionException {
    return switch (arg) {
      case Arg.Literal l -> l.value();
      case Arg.Expr e -> evaluate(view, e.source(), dynamic, ruleParams, names);
    };
  }

  private Object evaluate(
      View view,
      String source,
      Map<String, Object> dynamic,
      Map<String, Object> ruleParams,
      Set<String> names)
      throws ExpressionException {
    Expression expression =
        compiled.computeIfAbsent(
            view.hash() + "|" + names + "|" + source,
            k -> {
              try {
                return compiler.compile(
                    source,
                    new Scope(
                        Optional.empty(),
                        ruleset.params().stream()
                            .map(
                                p ->
                                    new FieldDescriptor(
                                        p.name(), p.type(), p.defaultValue().isEmpty(), false))
                            .toList(),
                        Map.of(),
                        names));
              } catch (ExpressionException e) {
                throw new IllegalStateException(e.getMessage(), e);
              }
            });
    Map<String, Object> visible = new HashMap<>();
    for (String n : names) {
      visible.put(n, dynamic.get(n));
    }
    return expression.evaluate(
        new Bindings(Optional.empty(), ruleParams, Map.of(), clock.instant(), visible));
  }

  private static String literal(View.Component c, String key, String fallback) {
    Arg a = c.values().get(key);
    return a instanceof Arg.Literal l ? String.valueOf(l.value()) : fallback;
  }

  private static String literalText(Arg a) {
    return a instanceof Arg.Literal l ? String.valueOf(l.value()) : null;
  }

  private static String text(Object o) {
    return o == null ? "" : o instanceof String s ? s : String.valueOf(show(o));
  }

  private static String describe(Object o) {
    return o == null ? "nothing" : o.getClass().getSimpleName();
  }

  private static Object zero(ValueType type) {
    return switch (type) {
      case STRING -> "";
      case INT -> 0L;
      case DOUBLE -> 0.0;
      case BOOL -> false;
      case TIMESTAMP -> Instant.EPOCH;
      case BYTES -> new byte[0];
      case LIST -> List.of();
      case MAP -> Map.of();
    };
  }

  /** A value as JSON can hold it: timestamps as ISO-8601 text, bytes as Base64. */
  static Object show(Object value) {
    return switch (value) {
      case null -> null;
      case Instant i -> i.toString();
      case byte[] b -> Base64.getEncoder().encodeToString(b);
      case Map<?, ?> m -> {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), show(v)));
        yield out;
      }
      case List<?> l -> l.stream().map(ViewResolver::show).toList();
      case String s -> s;
      case Number n -> n;
      case Boolean b -> b;
      default -> String.valueOf(value);
    };
  }

  /** The circumstances of a read. */
  private record ReadContext(String key) implements CapabilityContext {
    @Override
    public boolean dryRun() {
      return false;
    }

    @Override
    public String idempotencyKey() {
      return key;
    }

    @Override
    public Optional<Event> event() {
      return Optional.empty();
    }
  }
}
