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
    Scope sourceScope = new Scope(Optional.empty(), params, Map.of(), Set.of("view"));
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
              || v.getKey().equals("emphasis");
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
      command(a.command(), a.args(), perRow ? row : screen, ruleset, at, out);
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
    for (View.Component child : c.children()) {
      component(child, where, view, ruleset, screen, row, viewParams, out);
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
