// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.ruleset.LayerOps.Change;
import it.uhde.praksis.ruleset.LayerOps.Disable;
import it.uhde.praksis.ruleset.LayerOps.Patch;
import it.uhde.praksis.ruleset.LayerOps.Replace;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Puts layers together, bottom to top, into the ruleset that runs.
 *
 * <p>Every layer sees the rules as they are below it. {@code add} needs an id that is new, {@code
 * replace}, {@code patch} and {@code disable} need an id that exists and is unchanged from what the
 * layer was written for. Anything else is an error, never skipped: when a lower layer changes a
 * rule that a higher layer relies on, the combination is refused until a person has looked at it. A
 * layer may do one thing to a rule. A disabled rule cannot be added or changed by a higher layer;
 * it comes back when the layer that disabled it stops doing so.
 */
public final class LayerStack {
  private LayerStack() {}

  /** A layer's name and where its files are. */
  public record Source(String name, Path directory) {}

  /**
   * How a rule came to be what it is.
   *
   * @param ruleId the rule
   * @param layer the layer that added it
   * @param owner the layer that defined it last (the one that added, replaced or patched it); its
   *     grants decide what the rule may do
   * @param hash the hash of the effective rule, see {@link RuleView#hash}
   * @param history what each layer did to it, in order, e.g. {@code mine: patch}
   */
  public record Origin(
      String ruleId, String layer, String owner, String hash, List<String> history) {}

  /**
   * A rule that a layer turned off.
   *
   * @param ruleId the rule
   * @param layer the layer that disabled it
   * @param reason why, or empty
   */
  public record Disabled(String ruleId, String layer, Optional<String> reason) {}

  /**
   * The result.
   *
   * @param ruleset the ruleset to run
   * @param origins where each of its rules comes from, in firing order
   * @param disabled the rules that are turned off
   * @param viewOrigins where each view of the ruleset comes from
   */
  public record Effective(
      Ruleset ruleset,
      List<Origin> origins,
      List<Disabled> disabled,
      List<ViewOrigin> viewOrigins) {
    public Effective {
      viewOrigins = List.copyOf(viewOrigins);
    }

    /** An effective ruleset without views. */
    public Effective(Ruleset ruleset, List<Origin> origins, List<Disabled> disabled) {
      this(ruleset, origins, disabled, List.of());
    }
  }

  /**
   * Where a view comes from.
   *
   * @param viewId the view
   * @param layer the layer that added it
   * @param owner the layer that defined it last; its grants decide what the view's sources may read
   * @param hash the hash of the effective view, which a replacement names in {@code based_on}
   */
  public record ViewOrigin(String viewId, String layer, String owner, String hash) {}

  private record Entry(Rule rule, String layer, String owner, List<String> history) {}

  /**
   * @param layers bottom to top
   * @param params values for parameters from the local configuration, which win over every layer
   * @throws RulesetException with every problem found in the layers and in putting them together
   */
  public static Effective resolve(List<Source> layers, Map<String, Object> params)
      throws RulesetException {
    List<Problem> problems = new ArrayList<>();
    List<Layer> loaded = new ArrayList<>();
    for (Source source : layers) {
      try {
        loaded.add(Layer.load(source.name(), source.directory()));
      } catch (RulesetException e) {
        problems.addAll(e.problems());
      }
    }
    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }

    Map<String, Entry> rules = new LinkedHashMap<>();
    Map<String, ViewEntry> views = new LinkedHashMap<>();
    Map<String, Disabled> disabled = new LinkedHashMap<>();
    Map<String, ParamDecl> declared = new LinkedHashMap<>();
    for (Layer layer : loaded) {
      mergeParams(layer, declared, problems);
      apply(layer, rules, disabled, problems);
      applyViews(layer, views, problems);
    }
    applyParamValues(params, declared, problems);
    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }

    List<Entry> ordered =
        rules.values().stream().sorted(Comparator.comparing(e -> e.rule().id())).toList();
    String name = String.join("+", loaded.stream().map(l -> l.header().name()).toList());
    Ruleset ruleset =
        new Ruleset(
            Ruleset.SUPPORTED_FORMAT_VERSION,
            name,
            List.copyOf(declared.values()),
            ordered.stream().map(Entry::rule).toList(),
            views.values().stream()
                .sorted(Comparator.comparing(v -> v.view().id()))
                .map(ViewEntry::view)
                .toList());
    List<Origin> origins =
        ordered.stream()
            .map(
                e ->
                    new Origin(
                        e.rule().id(), e.layer(), e.owner(), RuleView.hash(e.rule()), e.history()))
            .toList();
    List<ViewOrigin> viewOrigins =
        ruleset.views().stream()
            .map(
                v -> {
                  ViewEntry e = views.get(v.id());
                  return new ViewOrigin(v.id(), e.layer(), e.owner(), v.hash());
                })
            .toList();
    return new Effective(ruleset, origins, List.copyOf(disabled.values()), viewOrigins);
  }

  private record ViewEntry(View view, String layer, String owner) {}

  /** A view is added; with {@code based_on} it replaces the view below, if that is unchanged. */
  private static void applyViews(
      Layer layer, Map<String, ViewEntry> views, List<Problem> problems) {
    Map<String, ViewEntry> below = new LinkedHashMap<>(views);
    Set<String> touched = new HashSet<>();
    for (View view : layer.views()) {
      String where = "layer '" + layer.name() + "': views/" + view.id() + ".toml";
      ViewEntry existing = below.get(view.id());
      if (!touched.add(view.id())) {
        problems.add(new Problem(where, "more than one operation on view '" + view.id() + "'"));
      } else if (view.basedOn().isEmpty() && existing != null) {
        problems.add(
            new Problem(
                where,
                "view '"
                    + view.id()
                    + "' already exists (added by '"
                    + existing.layer()
                    + "'); to replace it name its hash in 'based_on': "
                    + existing.view().hash()));
      } else if (view.basedOn().isPresent() && existing == null) {
        problems.add(
            new Problem(
                where, "'based_on' names a view, but there is no view '" + view.id() + "' below"));
      } else if (view.basedOn().isPresent()
          && !view.basedOn().get().equals(existing.view().hash())) {
        problems.add(
            new Problem(
                where,
                "the view '"
                    + view.id()
                    + "' has changed since this replacement was written: it is now "
                    + existing.view().hash()
                    + ", not "
                    + view.basedOn().get()));
      } else {
        views.put(
            view.id(),
            new ViewEntry(view, existing == null ? layer.name() : existing.layer(), layer.name()));
      }
    }
  }

  private static void apply(
      Layer layer,
      Map<String, Entry> rules,
      Map<String, Disabled> disabled,
      List<Problem> problems) {
    Map<String, Entry> below = new LinkedHashMap<>(rules);
    Set<String> touched = new HashSet<>();
    String at = "layer '" + layer.name() + "': ";

    for (Rule rule : layer.adds()) {
      String where = at + "rules/" + rule.id() + ".toml";
      if (!touched.add(rule.id())) {
        problems.add(new Problem(where, "more than one operation on rule '" + rule.id() + "'"));
      } else if (below.containsKey(rule.id())) {
        problems.add(
            new Problem(
                where,
                "rule '"
                    + rule.id()
                    + "' already exists (added by '"
                    + below.get(rule.id()).layer()
                    + "'); use replace/ or patch/ to change it"));
      } else if (disabled.containsKey(rule.id())) {
        problems.add(
            new Problem(
                where,
                "rule '"
                    + rule.id()
                    + "' was disabled by '"
                    + disabled.get(rule.id()).layer()
                    + "'; it cannot be added again"));
      } else {
        rules.put(
            rule.id(),
            new Entry(rule, layer.name(), layer.name(), List.of(layer.name() + ": add")));
      }
    }

    for (Replace replace : layer.replaces()) {
      String id = replace.rule().id();
      String where = at + "replace/" + id + ".toml";
      Entry target =
          target(id, "replace", replace.basedOn(), where, below, disabled, touched, problems);
      if (target != null) {
        rules.put(id, extended(target, replace.rule(), layer.name(), layer.name() + ": replace"));
      }
    }

    for (Patch patch : layer.patches()) {
      String where = at + "patch/" + patch.id() + ".toml";
      Entry target =
          target(
              patch.id(),
              "patch",
              patch.basedOn().orElse(null),
              where,
              below,
              disabled,
              touched,
              problems);
      if (target == null) {
        continue;
      }
      Optional<Rule> patched = patched(target.rule(), patch.changes(), where, problems);
      patched.ifPresent(
          r -> rules.put(patch.id(), extended(target, r, layer.name(), layer.name() + ": patch")));
    }

    for (Disable disable : layer.disables()) {
      String where = at + "disable/" + disable.id() + ".toml";
      Entry target =
          target(
              disable.id(),
              "disable",
              disable.basedOn(),
              where,
              below,
              disabled,
              touched,
              problems);
      if (target != null) {
        rules.remove(disable.id());
        disabled.put(disable.id(), new Disabled(disable.id(), layer.name(), disable.reason()));
      }
    }
  }

  private static Entry extended(Entry target, Rule rule, String owner, String step) {
    List<String> history = new ArrayList<>(target.history());
    history.add(step);
    return new Entry(rule, target.layer(), owner, history);
  }

  /** The rule an operation is aimed at, or null after reporting why there is none. */
  private static Entry target(
      String id,
      String operation,
      String basedOn,
      String where,
      Map<String, Entry> below,
      Map<String, Disabled> disabled,
      Set<String> touched,
      List<Problem> problems) {
    if (!touched.add(id)) {
      problems.add(new Problem(where, "more than one operation on rule '" + id + "'"));
      return null;
    }
    Entry target = below.get(id);
    if (target == null) {
      String why =
          disabled.containsKey(id)
              ? "was disabled by '" + disabled.get(id).layer() + "'"
              : "does not exist in the layers below";
      problems.add(new Problem(where, "cannot " + operation + " rule '" + id + "': it " + why));
      return null;
    }
    if (basedOn != null && !basedOn.equals(RuleView.hash(target.rule()))) {
      problems.add(
          new Problem(
              where,
              "rule '"
                  + id
                  + "' (from '"
                  + target.layer()
                  + "') has changed since this "
                  + operation
                  + " was written: based_on is "
                  + basedOn
                  + " but the rule now is "
                  + RuleView.hash(target.rule())
                  + ". Look at the change and update based_on."));
      return null;
    }
    return target;
  }

  private static Optional<Rule> patched(
      Rule rule, List<Change> changes, String where, List<Problem> problems) {
    Map<String, Object> map = RuleView.toMap(rule);
    boolean ok = true;
    for (Change change : changes) {
      ok &= applyChange(map, change, where, problems);
    }
    if (!ok) {
      return Optional.empty();
    }
    int before = problems.size();
    Optional<Rule> result = RulesetLoader.rule(new Table(map, where, problems), rule.id());
    return problems.size() > before ? Optional.empty() : result;
  }

  private static boolean applyChange(
      Map<String, Object> rule, Change change, String where, List<Problem> problems) {
    String[] path = change.path().split("\\.");
    Object parent = rule;
    String key;
    // walk to the container of the last path element
    int i = 0;
    while (true) {
      if (!(parent instanceof Map<?, ?>)) {
        return bad(problems, where, change, "does not name a field of the rule");
      }
      if (path.length - i == 1) {
        key = path[i];
        break;
      }
      if (path[i].equals("action") && parent == rule) {
        if (path.length - i < 3) {
          return bad(
              problems,
              where,
              change,
              "an action field is action.<id>.capability or action.<id>.args.<name>");
        }
        Object found = null;
        for (Object a : (List<?>) rule.get("action")) {
          if (a instanceof Map<?, ?> m && path[i + 1].equals(m.get("id"))) {
            found = a;
          }
        }
        if (found == null) {
          return bad(problems, where, change, "the rule has no action '" + path[i + 1] + "'");
        }
        parent = found;
        i += 2;
        continue;
      }
      Object next = ((Map<?, ?>) parent).get(path[i]);
      if (next == null) {
        return bad(problems, where, change, "does not name a field of the rule");
      }
      parent = next;
      i++;
    }
    @SuppressWarnings("unchecked")
    Map<String, Object> container = (Map<String, Object>) parent;
    if (Set.of("id", "kind", "stop", "action", "trigger").contains(key)) {
      return bad(problems, where, change, "cannot be changed");
    }
    Object current = container.get(key);
    if (change.hasExpect()) {
      if (current == null || !same(current, change.expect())) {
        return bad(
            problems,
            where,
            change,
            "has changed since this patch was written: expected "
                + show(change.expect())
                + " but it is "
                + (current == null ? "not set" : show(current)));
      }
    } else if (current != null) {
      return bad(
          problems,
          where,
          change,
          "is already set to " + show(current) + "; write the old value as 'expect'");
    }
    if (change.remove()) {
      container.remove(key);
    } else {
      container.put(key, change.value());
    }
    return true;
  }

  private static boolean bad(List<Problem> problems, String where, Change change, String message) {
    problems.add(new Problem(where, "change of '" + change.path() + "' " + message));
    return false;
  }

  /** Equality of TOML values: numbers by value, lists and maps element by element. */
  private static boolean same(Object a, Object b) {
    if (a instanceof Number x && b instanceof Number y) {
      return x.doubleValue() == y.doubleValue();
    }
    if (a instanceof List<?> x && b instanceof List<?> y) {
      if (x.size() != y.size()) {
        return false;
      }
      for (int i = 0; i < x.size(); i++) {
        if (!same(x.get(i), y.get(i))) {
          return false;
        }
      }
      return true;
    }
    if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
      if (!x.keySet().equals(y.keySet())) {
        return false;
      }
      for (Object k : x.keySet()) {
        if (!same(x.get(k), y.get(k))) {
          return false;
        }
      }
      return true;
    }
    return a.equals(b);
  }

  private static String show(Object value) {
    return value instanceof String s ? "\"" + s + "\"" : String.valueOf(value);
  }

  private static void mergeParams(
      Layer layer, Map<String, ParamDecl> declared, List<Problem> problems) {
    for (ParamDecl p : layer.header().params()) {
      ParamDecl old = declared.get(p.name());
      if (old != null && old.type() != p.type()) {
        problems.add(
            new Problem(
                "layer '" + layer.name() + "': ruleset.toml, param." + p.name(),
                "type "
                    + p.type().id()
                    + " differs from "
                    + old.type().id()
                    + " in the layers below"));
        continue;
      }
      Optional<Object> value =
          p.defaultValue().isPresent()
              ? p.defaultValue()
              : old == null ? Optional.empty() : old.defaultValue();
      declared.put(p.name(), new ParamDecl(p.name(), p.type(), value));
    }
  }

  private static void applyParamValues(
      Map<String, Object> values, Map<String, ParamDecl> declared, List<Problem> problems) {
    for (var e : values.entrySet()) {
      ParamDecl decl = declared.get(e.getKey());
      if (decl == null) {
        problems.add(
            new Problem("praksis.toml, param." + e.getKey(), "no layer declares this parameter"));
        continue;
      }
      Optional<Object> value = RulesetLoader.literal(decl.type(), e.getValue());
      if (value.isEmpty()) {
        problems.add(
            new Problem("praksis.toml, param." + e.getKey(), "is not a " + decl.type().id()));
        continue;
      }
      declared.put(e.getKey(), new ParamDecl(decl.name(), decl.type(), value));
    }
  }
}
