// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.entity.EntityCatalog;
import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.RulesetException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the daemon needs to know about entity sets: the catalog of the layers, the policy with the
 * members of the sets that its grants name, and what a change of the layers adds to the sets that
 * grants name (which is new access).
 */
final class EntitySets {
  private EntitySets() {}

  /** The entity types and sets of the layers; a problem in them stops the caller. */
  static EntityCatalog catalog(List<LayerStack.Source> sources) throws DaemonException {
    try {
      EntityCatalog.Loaded loaded = EntityCatalog.load(sources(sources));
      if (!loaded.problems().isEmpty()) {
        throw new DaemonException(
            "the entity types are not valid:\n  "
                + String.join("\n  ", loaded.problems().stream().map(Object::toString).toList()));
      }
      return loaded.catalog();
    } catch (IOException e) {
      throw new DaemonException("cannot read the entity types: " + e.getMessage(), e);
    }
  }

  static List<EntityCatalog.Source> sources(List<LayerStack.Source> sources) {
    return sources.stream().map(s -> new EntityCatalog.Source(s.name(), s.directory())).toList();
  }

  /** The members of every set of the layers, or none if the types and sets are not valid. */
  static Map<String, Set<String>> members(List<LayerStack.Source> sources) {
    try {
      EntityCatalog.Loaded loaded = EntityCatalog.load(sources(sources));
      return loaded.catalog().allMembers();
    } catch (IOException e) {
      return Map.of();
    }
  }

  /** {@code policy.toml} with the members of the sets that its grants name. */
  static Policy policy(DaemonConfig config, Map<String, Set<String>> sets) throws RulesetException {
    Map<String, Set<String>> all = new java.util.HashMap<>(sets);
    // a remote set is what its pin says, never what the remote offers now
    all.putAll(new RemotePins(config.home()).sets());
    return Policy.load(config.policy()).withSets(all);
  }

  /** A grant that names {@code <peer>/<set>} needs a pin for it. */
  static List<it.uhde.praksis.ruleset.Problem> pinProblems(DaemonConfig config, Policy policy) {
    Set<String> pinned = new RemotePins(config.home()).sets().keySet();
    List<it.uhde.praksis.ruleset.Problem> out = new ArrayList<>();
    for (var g : policy.grants()) {
      for (var a : g.args().entrySet()) {
        if (a.getValue() instanceof ArgMatcher.InSet in
            && in.set().contains("/")
            && !pinned.contains(in.set())) {
          out.add(
              new it.uhde.praksis.ruleset.Problem(
                  "policy.toml: grant '" + g.id() + "'",
                  "the remote set '"
                      + in.set()
                      + "' is not pinned (praksis peers pin "
                      + in.set().replace('/', ' ')
                      + ")"));
        }
      }
    }
    return out;
  }

  /**
   * What a change of the layers adds to the sets that grants name: for each grant with {@code type
   * = { set = "x" }}, the types that are in the set after the change and were not before. The grant
   * covers them without anyone having decided it, so this is new access.
   */
  static List<String> growth(
      Policy policy, Map<String, Set<String>> before, Map<String, Set<String>> after) {
    List<String> out = new ArrayList<>();
    for (Grant g : policy.grants()) {
      for (var arg : g.args().entrySet()) {
        if (arg.getValue() instanceof ArgMatcher.InSet in) {
          Set<String> added = new TreeSet<>(after.getOrDefault(in.set(), Set.of()));
          added.removeAll(before.getOrDefault(in.set(), Set.of()));
          for (String type : added) {
            out.add(
                "the set '"
                    + in.set()
                    + "' gains the type '"
                    + type
                    + "': grant '"
                    + g.id()
                    + "' (layer "
                    + g.layer()
                    + ", "
                    + g.capability()
                    + ") would cover it");
          }
        }
      }
    }
    return out;
  }
}
