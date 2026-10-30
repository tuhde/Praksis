// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expression;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.ExpressionException;
import it.uhde.praksis.expression.Scope;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Checks a ruleset against the installed modules, without running anything: event types and
 * capabilities exist, arguments have the declared types, expressions compile against the types they
 * can see, and each rule declares the capabilities and effect class it really uses.
 */
public final class RulesetValidator {
  private final ExpressionCompiler compiler;
  private final ModuleCatalog catalog;

  public RulesetValidator(ExpressionCompiler compiler, ModuleCatalog catalog) {
    this.compiler = compiler;
    this.catalog = catalog;
  }

  /** The outcome of {@link #compile}: the problems, and the compiled rules if there are none. */
  public record Result(List<Problem> problems, List<CompiledRule> rules) {
    public Result {
      problems = List.copyOf(problems);
      rules = List.copyOf(rules);
    }
  }

  /** Returns every problem found; empty if the ruleset is valid. */
  public List<Problem> validate(Ruleset ruleset) {
    return compile(ruleset).problems();
  }

  /** Validates the ruleset and compiles its rules. The rules are empty if there are problems. */
  public Result compile(Ruleset ruleset) {
    List<Problem> problems = new ArrayList<>();
    List<CompiledRule> compiled = new ArrayList<>();
    if (ruleset.formatVersion() != Ruleset.SUPPORTED_FORMAT_VERSION) {
      problems.add(
          new Problem(
              "ruleset.toml",
              "format_version "
                  + ruleset.formatVersion()
                  + " is not supported (supported: "
                  + Ruleset.SUPPORTED_FORMAT_VERSION
                  + ")"));
    }
    Set<String> paramNames = new HashSet<>();
    for (ParamDecl p : ruleset.params()) {
      if (!paramNames.add(p.name())) {
        problems.add(new Problem("ruleset.toml", "parameter '" + p.name() + "' declared twice"));
      }
    }
    List<FieldDescriptor> params =
        ruleset.params().stream()
            .map(p -> new FieldDescriptor(p.name(), p.type(), p.defaultValue().isEmpty(), false))
            .toList();

    Set<String> ruleIds = new HashSet<>();
    for (Rule rule : ruleset.rules()) {
      String where = "rules/" + rule.id() + ".toml";
      if (!ruleIds.add(rule.id())) {
        problems.add(new Problem(where, "rule id declared twice"));
      }
      compiled.add(rule(rule, where, params, problems));
    }
    problems.addAll(new ViewValidator(compiler, catalog).validate(ruleset));
    return new Result(problems, problems.isEmpty() ? compiled : List.of());
  }

  private CompiledRule rule(
      Rule rule, String where, List<FieldDescriptor> params, List<Problem> out) {
    Optional<EventTypeDescriptor> eventType;
    Optional<Expression> filter = Optional.empty();
    List<CompiledRule.CompiledAction> actions = new ArrayList<>();
    if (rule.trigger() instanceof Trigger.OnCommand command) {
      // the arguments of a command are the data of an event of its own type
      eventType =
          Optional.of(
              new EventTypeDescriptor(
                  rule.eventType(),
                  command.params().stream()
                      .map(p -> new FieldDescriptor(p.name(), p.type(), p.required(), false))
                      .toList()));
    } else {
      Trigger.OnEvent trigger = (Trigger.OnEvent) rule.trigger();
      eventType = catalog.eventType(trigger.type());
      if (eventType.isEmpty()) {
        out.add(new Problem(where + ", trigger", "unknown event type '" + trigger.type() + "'"));
      } else if (trigger.filter().isPresent()) {
        Scope scope = new Scope(eventType, params, Map.of());
        filter =
            Optional.ofNullable(
                check(
                    trigger.filter().get(),
                    scope,
                    ValueType.BOOL,
                    where + ", trigger filter",
                    out));
      }
    }

    Set<String> used = new HashSet<>();
    Set<String> actionIds = new HashSet<>();
    Map<String, List<FieldDescriptor>> results = new LinkedHashMap<>();
    int strongest = 0;
    boolean stopped = false;
    for (Action action : rule.actions()) {
      if (stopped) {
        out.add(new Problem(where, "action after 'stop' can never run"));
        break;
      }
      if (action instanceof Action.Stop stop) {
        Optional<Expression> when =
            condition(stop.when(), new Scope(eventType, params, results), where + ", stop", out);
        actions.add(new CompiledRule.CompiledAction.Stop(when));
        stopped = stop.when().isEmpty(); // a stop with a condition leaves the later steps reachable
        continue;
      }
      Action.Call call = (Action.Call) action;
      String at = where + ", action '" + call.id() + "'";
      if (!actionIds.add(call.id())) {
        out.add(new Problem(at, "action id declared twice"));
      }
      Optional<CapabilityDescriptor> cap = catalog.capability(call.capability());
      if (cap.isEmpty()) {
        out.add(new Problem(at, "unknown capability '" + call.capability() + "'"));
        results.put(call.id(), List.of());
        continue;
      }
      used.add(call.capability());
      strongest = Math.max(strongest, cap.get().effect().ordinal());
      Scope scope = new Scope(eventType, params, results);
      actions.add(
          new CompiledRule.CompiledAction.Call(
              call.id(),
              call.capability(),
              args(call, cap.get(), scope, at, out),
              condition(call.when(), scope, at, out)));
      results.put(call.id(), cap.get().results());
    }

    for (String need : rule.needs()) {
      if (catalog.capability(need).isEmpty()) {
        out.add(new Problem(where, "'needs' lists unknown capability '" + need + "'"));
      }
    }
    for (String capability : used) {
      if (!rule.needs().contains(capability)) {
        out.add(
            new Problem(
                where, "uses capability '" + capability + "' but does not list it in 'needs'"));
      }
    }
    if (rule.effect().ordinal() < strongest) {
      out.add(
          new Problem(
              where,
              "declares effect '"
                  + rule.effect().id()
                  + "' but uses a capability with effect '"
                  + EffectClass.values()[strongest].id()
                  + "'"));
    }
    return new CompiledRule(rule, filter, actions);
  }

  /**
   * The compiled condition of a step; it can read what happened before it, and is true or false.
   */
  private Optional<Expression> condition(
      Optional<String> when, Scope scope, String at, List<Problem> out) {
    if (when.isEmpty()) {
      return Optional.empty();
    }
    return Optional.ofNullable(check(when.get(), scope, ValueType.BOOL, at + ", when", out));
  }

  private Map<String, CompiledRule.CompiledArg> args(
      Action.Call call, CapabilityDescriptor cap, Scope scope, String at, List<Problem> out) {
    Map<String, CompiledRule.CompiledArg> compiled = new LinkedHashMap<>();
    Map<String, FieldDescriptor> declared = new LinkedHashMap<>();
    cap.params().forEach(p -> declared.put(p.name(), p));
    for (var arg : call.args().entrySet()) {
      FieldDescriptor field = declared.get(arg.getKey());
      String argAt = at + ", arg '" + arg.getKey() + "'";
      if (field == null) {
        out.add(new Problem(argAt, "capability '" + cap.name() + "' has no such parameter"));
        continue;
      }
      switch (arg.getValue()) {
        case Arg.Literal l -> {
          Optional<Object> value = RulesetLoader.literal(field.type(), l.value());
          if (value.isEmpty()) {
            out.add(new Problem(argAt, "must be a " + field.type().id()));
          } else {
            compiled.put(arg.getKey(), new CompiledRule.CompiledArg.Value(value.get()));
          }
        }
        case Arg.Expr e -> {
          Expression expression = check(e.source(), scope, field.type(), argAt, out);
          if (expression != null) {
            compiled.put(arg.getKey(), new CompiledRule.CompiledArg.Computed(expression));
          }
        }
      }
    }
    for (FieldDescriptor p : cap.params()) {
      if (p.required() && !call.args().containsKey(p.name())) {
        out.add(new Problem(at, "missing required argument '" + p.name() + "'"));
      }
    }
    return compiled;
  }

  private Expression check(
      String source, Scope scope, ValueType expected, String at, List<Problem> out) {
    try {
      return compiler.compile(source, scope, expected);
    } catch (ExpressionException e) {
      out.add(new Problem(at, firstLine(e.getMessage())));
      return null;
    }
  }

  private static String firstLine(String message) {
    int i = message.indexOf('\n');
    return i < 0 ? message : message.substring(0, i);
  }
}
