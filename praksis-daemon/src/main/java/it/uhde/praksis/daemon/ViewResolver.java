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
          evaluate(view, source.when().get(), dynamic, ruleParams, Set.of("view")));
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
              case Arg.Expr e -> evaluate(view, e.source(), dynamic, ruleParams, Set.of("view"));
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
          capability.invoke(new ReadContext("view:" + view.id() + "/" + name), args);
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
    Arg width = c.values().get("width");
    return width instanceof Arg.Literal l && l.value() instanceof Number n && built.error() == null
        ? withWidth(built, n.intValue())
        : built;
  }

  private static ViewComponent withWidth(ViewComponent c, int width) {
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
        width);
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
            new ViewComponent.Select(
                c.select().get().param(),
                show(
                    evaluate(
                        view,
                        c.select().get().value(),
                        inRow,
                        ruleParams,
                        Set.of("view", "source", "item"))),
                c.select().get().clear());
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
      rows.add(new ViewComponent.Row(cells, select, actions, emphasis));
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
        null);
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
              p.name(), p.type().id(), p.required(), p.label().orElse(null), show(value)));
    }
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
            id, command.title(), literal(c, "submit_label", command.title()), fields),
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
