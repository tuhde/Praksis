// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks the views of a ruleset: the schema is one this Praksis understands, sources call
 * capabilities that exist and only read, commands named by buttons, row actions and forms exist and
 * get the arguments they need, selections set parameters of the view, and every expression compiles
 * against the variables it may use.
 */
final class ViewValidator {
  private final ExpressionCompiler compiler;
  private final ModuleCatalog catalog;

  ViewValidator(ExpressionCompiler compiler, ModuleCatalog catalog) {
    this.compiler = compiler;
    this.catalog = catalog;
  }

  List<Problem> validate(Ruleset ruleset) {
    List<Problem> problems = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    List<FieldDescriptor> params =
        ruleset.params().stream()
            .map(p -> new FieldDescriptor(p.name(), p.type(), p.defaultValue().isEmpty(), false))
            .toList();
    for (View view : ruleset.views()) {
      String where = "views/" + view.id() + ".toml";
      if (!ids.add(view.id())) {
        problems.add(new Problem(where, "view id declared twice"));
      }
      one(view, where, ruleset, params, problems);
    }
    return problems;
  }

  private void one(
      View view, String where, Ruleset ruleset, List<FieldDescriptor> params, List<Problem> out) {
    if (view.schema() > View.SUPPORTED_SCHEMA) {
      out.add(
          new Problem(
              where,
              "is written for UI schema "
                  + view.schema()
                  + " but this Praksis understands up to "
                  + View.SUPPORTED_SCHEMA));
      return;
    }
    Set<String> viewParams = new HashSet<>();
    for (ParamDecl p : view.params()) {
      if (!viewParams.add(p.name())) {
        out.add(new Problem(where, "parameter '" + p.name() + "' declared twice"));
      }
    }
    // a source may use the sources declared before it, which is how a reply is made from the mail
    Scope sourceScope = new Scope(Optional.empty(), params, Map.of(), Set.of("view", "source"));
    Scope screen = new Scope(Optional.empty(), params, Map.of(), Set.of("view", "source"));
    Scope row = new Scope(Optional.empty(), params, Map.of(), Set.of("view", "source", "item"));

    for (var source : view.sources().entrySet()) {
      String at = where + ", source '" + source.getKey() + "'";
      Optional<CapabilityDescriptor> capability =
          catalog.capability(source.getValue().capability());
      if (capability.isEmpty()) {
        out.add(new Problem(at, "unknown capability '" + source.getValue().capability() + "'"));
        continue;
      }
      if (capability.get().effect() != EffectClass.READ_ONLY) {
        out.add(
            new Problem(
                at,
                "'"
                    + capability.get().name()
                    + "' has the effect '"
                    + capability.get().effect().id()
                    + "'; a view may only read"));
      }
      sourceArgs(source.getValue(), capability.get(), sourceScope, at, out);
      if (source.getValue().when().orElse(null) instanceof Arg.Expr e) {
        expression(e.source(), sourceScope, at + " when", out);
      }
    }
    for (View.Component c : view.components()) {
      component(c, where, view, ruleset, screen, row, viewParams, out);
    }
    // one key does not mean two things; but a key may be shared by parts that are shown one at a
    // time, such as a button that turns something on and one that turns it off
    Map<String, int[]> keys = new java.util.TreeMap<>(); // key -> {parts, parts without visible}
    keysOf(view.components(), keys);
    keys.forEach(
        (key, n) -> {
          if (n[0] > 1 && n[1] > 0) {
            out.add(
                new Problem(
                    where,
                    "the key '"
                        + key
                        + "' is used by "
                        + n[0]
                        + " parts; that is only allowed if each of them has 'visible'"));
          }
        });
  }

  private static void keysOf(List<View.Component> parts, Map<String, int[]> keys) {
    for (View.Component c : parts) {
      if (c.values().get("key") instanceof Arg.Literal l) {
        int[] n = keys.computeIfAbsent(String.valueOf(l.value()), k -> new int[2]);
        n[0]++;
        if (!c.values().containsKey("visible")) {
          n[1]++;
        }
      }
      keysOf(c.children(), keys);
    }
  }

  private void sourceArgs(
      View.Source source, CapabilityDescriptor cap, Scope scope, String at, List<Problem> out) {
    Map<String, FieldDescriptor> declared = new java.util.LinkedHashMap<>();
    cap.params().forEach(p -> declared.put(p.name(), p));
    for (var arg : source.args().entrySet()) {
      FieldDescriptor field = declared.get(arg.getKey());
      String argAt = at + ", arg '" + arg.getKey() + "'";
      if (field == null) {
        out.add(new Problem(argAt, "capability '" + cap.name() + "' has no such parameter"));
      } else if (arg.getValue() instanceof Arg.Literal l
          && RulesetLoader.literal(field.type(), l.value()).isEmpty()) {
        out.add(new Problem(argAt, "must be a " + field.type().id()));
      } else if (arg.getValue() instanceof Arg.Expr e) {
        expression(e.source(), scope, argAt, out);
      }
    }
    for (FieldDescriptor p : cap.params()) {
      if (p.required() && !source.args().containsKey(p.name())) {
        out.add(new Problem(at, "missing required argument '" + p.name() + "'"));
      }
    }
  }

  private void component(
      View.Component c,
      String where,
      View view,
      Ruleset ruleset,
      Scope screen,
      Scope row,
      Set<String> viewParams,
      List<Problem> out) {
    String at = where + ", " + c.type();
    Scope valueScope = c.type().equals("table") || c.type().equals("list") ? row : screen;
    for (var v : c.values().entrySet()) {
      if (v.getKey().startsWith("default.")) {
        continue; // checked with the command of the form
      }
      boolean perRow =
          v.getKey().equals("title")
              || v.getKey().equals("subtitle")
              || v.getKey().equals("emphasis")
              || v.getKey().equals("drag")
              || v.getKey().startsWith("drop.arg.");
      boolean list = v.getKey().equals("rows") || v.getKey().equals("items");
      if (v.getValue() instanceof Arg.Expr e) {
        expression(e.source(), list || !perRow ? screen : valueScope, at + " " + v.getKey(), out);
      }
    }
    for (Map<String, Arg> column : c.columns()) {
      if (column.get("value") instanceof Arg.Expr e) {
        expression(e.source(), row, at + " column", out);
      }
    }
    for (Map<String, Arg> field : c.fields()) {
      if (field.get("value") instanceof Arg.Expr e) {
        expression(e.source(), screen, at + " field", out);
      }
    }
    boolean perRow = c.type().equals("table") || c.type().equals("list");
    for (View.Action a : c.actions()) {
      if (c.type().equals("form")) {
        break; // the extra buttons of a form are checked with the form
      }
      if (a.opens().isPresent()) {
        opens(a.opens().get(), perRow ? row : screen, ruleset, at, out);
      } else if (a.download().isPresent()) {
        download(a.download().get(), perRow ? row : screen, at, out);
      } else {
        command(a.command(), a.args(), perRow ? row : screen, ruleset, at, out);
      }
    }
    c.select()
        .ifPresent(
            s -> {
              String verb = c.type().equals("button") ? "sets" : "select sets";
              if (!viewParams.contains(s.param())) {
                out.add(
                    new Problem(
                        at, verb + " '" + s.param() + "', which the view does not declare"));
              }
              for (String name : s.clear()) {
                if (!viewParams.contains(name)) {
                  out.add(
                      new Problem(
                          at, "clear names '" + name + "', which the view does not declare"));
                }
              }
              Scope selectScope = perRow ? row : screen;
              if (s.value() instanceof Arg.Expr e) {
                expression(e.source(), selectScope, at + " " + verb, out);
              }
              s.then()
                  .ifPresent(a -> command(a.command(), a.args(), selectScope, ruleset, at, out));
            });
    if (c.values().get("drop.command") instanceof Arg.Literal drop) {
      dropTarget(c, String.valueOf(drop.value()), ruleset, row, at, out);
    }
    if (c.type().equals("richtext")
        && c.values().get("images_param") instanceof Arg.Literal l
        && !viewParams.contains(String.valueOf(l.value()))) {
      out.add(
          new Problem(
              at, "images_param names '" + l.value() + "', which the view does not declare"));
    }
    if (c.type().equals("input")
        && c.values().get("param") instanceof Arg.Literal l
        && !viewParams.contains(String.valueOf(l.value()))) {
      out.add(
          new Problem(
              at, "input is bound to '" + l.value() + "', which the view does not declare"));
    }
    if (c.type().equals("form")) {
      Arg command = c.values().get("command");
      String id = command instanceof Arg.Literal l ? String.valueOf(l.value()) : "";
      Optional<Trigger.OnCommand> found = commandOf(ruleset, id);
      if (found.isEmpty()) {
        out.add(new Problem(at, "there is no command '" + id + "'"));
      } else {
        for (var v : c.values().entrySet()) {
          if (v.getKey().startsWith("default.")) {
            String name = v.getKey().substring("default.".length());
            if (found.get().params().stream().noneMatch(p -> p.name().equals(name))) {
              out.add(new Problem(at, "command '" + id + "' has no argument '" + name + "'"));
            } else if (v.getValue() instanceof Arg.Expr e) {
              expression(e.source(), screen, at + " default " + name, out);
            }
          }
        }
      }
    }
    if (c.type().equals("form")) {
      formExtras(c, ruleset, at, out);
    }
    for (View.Component child : c.children()) {
      component(child, where, view, ruleset, screen, row, viewParams, out);
    }
  }

  /**
   * The extra buttons of a form start commands with the fields of the form, so every argument they
   * need must be a field of the form; and the lookup must be a capability that only reads.
   */
  private void formExtras(View.Component c, Ruleset ruleset, String at, List<Problem> out) {
    if (c.values().get("done") instanceof Arg.Literal d
        && ruleset.views().stream().noneMatch(v -> v.id().equals(String.valueOf(d.value())))) {
      out.add(new Problem(at, "done: there is no view '" + d.value() + "'"));
    }
    Arg primary = c.values().get("command");
    Optional<Trigger.OnCommand> main =
        commandOf(ruleset, primary instanceof Arg.Literal l ? String.valueOf(l.value()) : "");
    for (View.Action a : c.actions()) {
      Optional<Trigger.OnCommand> other = commandOf(ruleset, a.command());
      if (other.isEmpty()) {
        out.add(new Problem(at, "there is no command '" + a.command() + "'"));
        continue;
      }
      for (Trigger.CommandParam p : other.get().params()) {
        boolean given =
            main.isPresent()
                && main.get().params().stream()
                    .anyMatch(m -> m.name().equals(p.name()) && m.type() == p.type());
        if (p.required() && !given) {
          out.add(
              new Problem(
                  at,
                  "the button '"
                      + a.label()
                      + "' needs '"
                      + p.name()
                      + "' ("
                      + p.type().id()
                      + "), which the form does not have"));
        }
      }
    }
    if (c.values().get("lookup.capability") instanceof Arg.Literal l) {
      String name = String.valueOf(l.value());
      Optional<CapabilityDescriptor> capability = catalog.capability(name);
      String where = at + ", lookup";
      if (capability.isEmpty()) {
        out.add(new Problem(where, "unknown capability '" + name + "'"));
        return;
      }
      if (capability.get().effect() != EffectClass.READ_ONLY) {
        out.add(new Problem(where, "'" + name + "' does not only read"));
      }
      String arg = String.valueOf(((Arg.Literal) c.values().get("lookup.arg")).value());
      if (capability.get().params().stream()
          .noneMatch(
              p ->
                  p.name().equals(arg)
                      && p.type() == it.uhde.praksis.capability.ValueType.STRING)) {
        out.add(new Problem(where, "'" + name + "' has no text argument '" + arg + "'"));
      }
      if (capability.get().results().stream()
          .noneMatch(
              r ->
                  r.name().equals("items")
                      && r.type() == it.uhde.praksis.capability.ValueType.LIST)) {
        out.add(new Problem(where, "'" + name + "' does not answer with 'items' (a list)"));
      }
    }
  }

  /**
   * A row that accepts a dragged row starts a command with the dragged value in one parameter and
   * the rest from the row, so the command must exist, have that parameter, and get all it needs.
   */
  private void dropTarget(
      View.Component c, String id, Ruleset ruleset, Scope row, String at, List<Problem> out) {
    String where = at + ", drop";
    Optional<Trigger.OnCommand> command = commandOf(ruleset, id);
    if (command.isEmpty()) {
      out.add(new Problem(where, "there is no command '" + id + "'"));
      return;
    }
    String param =
        c.values().get("drop.param") instanceof Arg.Literal l ? String.valueOf(l.value()) : "";
    if (command.get().params().stream().noneMatch(p -> p.name().equals(param))) {
      out.add(new Problem(where, "command '" + id + "' has no parameter '" + param + "'"));
    }
    Map<String, Arg> given = new java.util.LinkedHashMap<>();
    c.values()
        .forEach(
            (k, v) -> {
              if (k.startsWith("drop.arg.")) {
                given.put(k.substring("drop.arg.".length()), v);
              }
            });
    if (given.containsKey(param)) {
      out.add(new Problem(where, "'" + param + "' is the dragged value and cannot be given too"));
    }
    Map<String, Arg> withDragged = new java.util.LinkedHashMap<>(given);
    withDragged.put(param, new Arg.Literal("dragged"));
    command(id, withDragged, row, ruleset, where, out);
  }

  /** Opening a view names a view that exists and parameters that it declares. */
  private void opens(View.Opens o, Scope scope, Ruleset ruleset, String at, List<Problem> out) {
    String where = at + ", opens";
    View target =
        ruleset.views().stream().filter(v -> v.id().equals(o.view())).findFirst().orElse(null);
    if (target == null) {
      out.add(new Problem(where, "there is no view '" + o.view() + "'"));
      return;
    }
    for (var param : o.params().entrySet()) {
      if (target.params().stream().noneMatch(p -> p.name().equals(param.getKey()))) {
        out.add(
            new Problem(
                where, "view '" + o.view() + "' has no parameter '" + param.getKey() + "'"));
      } else if (param.getValue() instanceof Arg.Expr e) {
        expression(e.source(), scope, where + ", parameter '" + param.getKey() + "'", out);
      }
    }
  }

  /** A download reads one read-only capability that answers with the bytes of a file. */
  private void download(View.Download d, Scope scope, String at, List<Problem> out) {
    String where = at + ", download";
    Optional<CapabilityDescriptor> capability = catalog.capability(d.capability());
    if (capability.isEmpty()) {
      out.add(new Problem(where, "unknown capability '" + d.capability() + "'"));
      return;
    }
    if (capability.get().effect() != EffectClass.READ_ONLY) {
      out.add(
          new Problem(
              where,
              "'"
                  + capability.get().name()
                  + "' has the effect '"
                  + capability.get().effect().id()
                  + "'; a download may only read"));
    }
    if (capability.get().results().stream()
        .noneMatch(
            r ->
                r.name().equals("content")
                    && r.type() == it.uhde.praksis.capability.ValueType.BYTES)) {
      out.add(
          new Problem(where, "'" + d.capability() + "' does not answer with 'content' (bytes)"));
    }
    sourceArgs(
        new View.Source(d.capability(), d.args(), Optional.empty()),
        capability.get(),
        scope,
        where,
        out);
    if (d.filename() instanceof Arg.Expr e) {
      expression(e.source(), scope, where + " filename", out);
    }
  }

  private static Optional<Trigger.OnCommand> commandOf(Ruleset ruleset, String id) {
    return ruleset.rules().stream()
        .filter(r -> r.id().equals(id) && r.trigger() instanceof Trigger.OnCommand)
        .map(r -> (Trigger.OnCommand) r.trigger())
        .findFirst();
  }

  private void command(
      String id,
      Map<String, Arg> args,
      Scope scope,
      Ruleset ruleset,
      String at,
      List<Problem> out) {
    Optional<Trigger.OnCommand> command = commandOf(ruleset, id);
    if (command.isEmpty()) {
      out.add(new Problem(at, "there is no command '" + id + "'"));
      return;
    }
    for (var arg : args.entrySet()) {
      Optional<Trigger.CommandParam> p =
          command.get().params().stream().filter(x -> x.name().equals(arg.getKey())).findFirst();
      String argAt = at + ", command '" + id + "', arg '" + arg.getKey() + "'";
      if (p.isEmpty()) {
        out.add(new Problem(argAt, "the command has no such argument"));
      } else if (arg.getValue() instanceof Arg.Literal l
          && RulesetLoader.literal(p.get().type(), l.value()).isEmpty()) {
        out.add(new Problem(argAt, "must be a " + p.get().type().id()));
      } else if (arg.getValue() instanceof Arg.Expr e) {
        expression(e.source(), scope, argAt, out);
      }
    }
    for (Trigger.CommandParam p : command.get().params()) {
      if (p.required() && !args.containsKey(p.name())) {
        out.add(new Problem(at, "command '" + id + "' needs the argument '" + p.name() + "'"));
      }
    }
  }

  private void expression(String source, Scope scope, String at, List<Problem> out) {
    try {
      compiler.compile(source, scope);
    } catch (ExpressionException e) {
      String message = e.getMessage() == null ? "" : e.getMessage();
      int line = message.indexOf('\n');
      out.add(new Problem(at, line < 0 ? message : message.substring(0, line)));
    }
  }
}
