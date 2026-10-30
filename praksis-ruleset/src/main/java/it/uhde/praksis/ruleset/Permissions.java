// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What a ruleset needs to be allowed to do, worked out from the rules alone: for every call, the
 * layer whose grants decide, the capability and the arguments that a grant may restrict. An
 * argument whose value is known before the rule runs (a literal, a parameter, a quoted string in an
 * expression) is listed with that value; any other argument is unbounded, and only a grant that
 * does not restrict it covers the call.
 *
 * <p>This is the same question the engine asks at run time, asked early, so that a change to a
 * ruleset can be refused before it is pinned.
 */
public final class Permissions {
  private static final Pattern PARAM = Pattern.compile("\\s*params\\.([A-Za-z_][A-Za-z0-9_]*)\\s*");
  private static final Pattern QUOTED =
      Pattern.compile("\\s*(?:'([^'\\\\]*)'|\"([^\"\\\\]*)\")\\s*");

  private Permissions() {}

  /**
   * One kind of call.
   *
   * @param owner the layer whose grants decide
   * @param capability the capability
   * @param known the restrictable arguments with a value that is known in advance
   * @param unbounded the restrictable arguments that are only known when the rule runs
   */
  public record Requirement(
      String owner, String capability, Map<String, Object> known, Set<String> unbounded) {
    public Requirement {
      known = java.util.Collections.unmodifiableMap(new TreeMap<>(known));
      unbounded = java.util.Collections.unmodifiableSortedSet(new TreeSet<>(unbounded));
    }

    /** E.g. {@code mine: mail.move folder="Spam", path=*}. */
    public String describe() {
      List<String> parts = new ArrayList<>();
      Map<String, String> shown = new TreeMap<>();
      known.forEach((k, v) -> shown.put(k, ArgMatcher.show(v)));
      unbounded.forEach(k -> shown.put(k, "*"));
      shown.forEach((k, v) -> parts.add(k + "=" + v));
      return owner + ": " + capability + (parts.isEmpty() ? "" : " " + String.join(", ", parts));
    }
  }

  /**
   * The requirements of a ruleset.
   *
   * @param rules the rules that cause each requirement
   */
  public record Needs(Map<Requirement, SortedSet<String>> rules) {
    public Needs {
      rules = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(rules));
    }

    public Set<Requirement> requirements() {
      return rules.keySet();
    }
  }

  /**
   * What changed between two rulesets.
   *
   * @param added requirements that the new ruleset has and the old one has not
   * @param removed requirements that the old ruleset had and the new one has not
   */
  public record Diff(List<Requirement> added, List<Requirement> removed) {
    public Diff {
      added = List.copyOf(added);
      removed = List.copyOf(removed);
    }

    public boolean isEmpty() {
      return added.isEmpty() && removed.isEmpty();
    }
  }

  /** What the rules of this ruleset need, with the owner of each rule from the layers. */
  public static Needs of(LayerStack.Effective effective, ModuleCatalog catalog) {
    Map<String, String> owners = new HashMap<>();
    effective.origins().forEach(o -> owners.put(o.ruleId(), o.owner()));
    Map<String, Object> params = new HashMap<>();
    effective
        .ruleset()
        .params()
        .forEach(p -> p.defaultValue().ifPresent(v -> params.put(p.name(), v)));
    Map<Requirement, SortedSet<String>> found = new LinkedHashMap<>();
    for (Rule rule : effective.ruleset().rules()) {
      String owner = owners.getOrDefault(rule.id(), "?");
      for (Action action : rule.actions()) {
        if (action instanceof Action.Call call) {
          found
              .computeIfAbsent(requirement(owner, call, params, catalog), k -> new TreeSet<>())
              .add(rule.id());
        }
      }
    }
    // the sources of a view read with the grants of the layer that owns the view
    Map<String, String> viewOwners = new HashMap<>();
    effective.viewOrigins().forEach(o -> viewOwners.put(o.viewId(), o.owner()));
    for (View view : effective.ruleset().views()) {
      String owner = viewOwners.getOrDefault(view.id(), "?");
      for (View.Source source : view.sources().values()) {
        Action.Call call = new Action.Call("source", source.capability(), source.args());
        found
            .computeIfAbsent(requirement(owner, call, params, catalog), k -> new TreeSet<>())
            .add("view:" + view.id());
      }
      // and so do its lookups, which read as a person types
      for (String capability : lookups(view.components())) {
        Action.Call call = new Action.Call("lookup", capability, Map.of());
        found
            .computeIfAbsent(requirement(owner, call, params, catalog), k -> new TreeSet<>())
            .add("view:" + view.id());
      }
      // and so do the microphones of its inputs, which listen when a person presses the button
      for (View.Component c : inputsWithMicrophone(view.components())) {
        Map<String, Arg> args = new java.util.LinkedHashMap<>();
        c.values()
            .forEach(
                (k, v) -> {
                  if (k.startsWith("dictate.arg.")) {
                    args.put(k.substring("dictate.arg.".length()), v);
                  }
                });
        Action.Call call =
            new Action.Call(
                "dictate",
                String.valueOf(((Arg.Literal) c.values().get("dictate.capability")).value()),
                args);
        found
            .computeIfAbsent(requirement(owner, call, params, catalog), k -> new TreeSet<>())
            .add("view:" + view.id());
      }
      // and so do its downloads, which read when a person asks for them
      for (View.Download download : downloads(view.components())) {
        Action.Call call = new Action.Call("download", download.capability(), download.args());
        found
            .computeIfAbsent(requirement(owner, call, params, catalog), k -> new TreeSet<>())
            .add("view:" + view.id());
      }
    }
    return new Needs(found);
  }

  private static List<View.Component> inputsWithMicrophone(List<View.Component> components) {
    List<View.Component> out = new java.util.ArrayList<>();
    for (View.Component c : components) {
      if (c.values().get("dictate.capability") instanceof Arg.Literal) {
        out.add(c);
      }
      out.addAll(inputsWithMicrophone(c.children()));
    }
    return out;
  }

  private static List<String> lookups(List<View.Component> components) {
    List<String> out = new java.util.ArrayList<>();
    for (View.Component c : components) {
      if (c.values().get("lookup.capability") instanceof Arg.Literal l) {
        out.add(String.valueOf(l.value()));
      }
      out.addAll(lookups(c.children()));
    }
    return out;
  }

  private static List<View.Download> downloads(List<View.Component> components) {
    List<View.Download> out = new java.util.ArrayList<>();
    for (View.Component c : components) {
      c.actions().forEach(a -> a.download().ifPresent(out::add));
      out.addAll(downloads(c.children()));
    }
    return out;
  }

  private static Requirement requirement(
      String owner, Action.Call call, Map<String, Object> params, ModuleCatalog catalog) {
    Map<String, Object> known = new TreeMap<>();
    Set<String> unbounded = new TreeSet<>();
    Optional<CapabilityDescriptor> capability = catalog.capability(call.capability());
    for (FieldDescriptor field : capability.map(CapabilityDescriptor::params).orElse(List.of())) {
      if (!field.scopable()) {
        continue;
      }
      Arg arg = call.args().get(field.name());
      Optional<Object> value = arg == null ? Optional.empty() : value(arg, params);
      if (value.isPresent()) {
        known.put(field.name(), value.get());
      } else {
        unbounded.add(field.name());
      }
    }
    return new Requirement(owner, call.capability(), known, unbounded);
  }

  private static Optional<Object> value(Arg arg, Map<String, Object> params) {
    return switch (arg) {
      case Arg.Literal l -> Optional.of(normalize(l.value()));
      case Arg.Expr e -> {
        Matcher param = PARAM.matcher(e.source());
        if (param.matches()) {
          yield Optional.ofNullable(params.get(param.group(1))).map(Permissions::normalize);
        }
        Matcher quoted = QUOTED.matcher(e.source());
        if (quoted.matches()) {
          yield Optional.of(quoted.group(1) != null ? quoted.group(1) : quoted.group(2));
        }
        yield Optional.empty();
      }
    };
  }

  private static Object normalize(Object value) {
    return value instanceof Integer || value instanceof Short || value instanceof Byte
        ? Long.valueOf(((Number) value).longValue())
        : value;
  }

  /** The requirements that are new or gone in {@code after}. */
  public static Diff diff(Needs before, Needs after) {
    List<Requirement> added =
        after.requirements().stream().filter(r -> !before.requirements().contains(r)).toList();
    List<Requirement> removed =
        before.requirements().stream().filter(r -> !after.requirements().contains(r)).toList();
    return new Diff(added, removed);
  }

  /** The requirements that no grant covers. */
  public static List<Requirement> uncovered(Collection<Requirement> requirements, Policy policy) {
    return requirements.stream()
        .filter(r -> policy.find(r.owner(), r.capability(), r.known()).isEmpty())
        .toList();
  }
}
