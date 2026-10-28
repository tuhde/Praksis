// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.Policy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Allows a call only if a grant of the rule's layer covers it. A rule belongs to the layer that
 * last defined it, so a private layer that patches a public rule needs its own grant.
 */
public final class GrantPolicy implements AccessPolicy {
  private volatile Policy policy;
  private final Map<String, String> layers;

  /**
   * @param policy the local grants
   * @param layers the layer of each rule, by rule id
   */
  public GrantPolicy(Policy policy, Map<String, String> layers) {
    this.policy = policy;
    this.layers = Map.copyOf(layers);
  }

  /** Uses these grants from now on, e.g. after {@code policy.toml} was changed. */
  public void replace(Policy newPolicy) {
    this.policy = newPolicy;
  }

  /** The grants in use. */
  public Policy policy() {
    return policy;
  }

  /** The layer that owns a rule, if the rule exists. */
  public Optional<String> layerOf(String ruleId) {
    return Optional.ofNullable(layers.get(ruleId));
  }

  @Override
  public Decision check(String ruleId, CapabilityDescriptor capability, Map<String, Object> args) {
    String layer = layers.get(ruleId);
    if (layer == null) {
      return Decision.deny("rule '" + ruleId + "' belongs to no layer");
    }
    Optional<Grant> grant = policy.find(layer, capability.name(), args);
    if (grant.isPresent()) {
      return Decision.allow();
    }
    return Decision.deny(
        "layer '" + layer + "' has no grant for " + capability.name() + shown(capability, args));
  }

  /** The arguments that a grant could restrict, e.g. {@code (folder=Trash)}; others stay out. */
  private static String shown(CapabilityDescriptor capability, Map<String, Object> args) {
    Map<String, Object> scoped = new LinkedHashMap<>();
    for (FieldDescriptor p : capability.params()) {
      if (p.scopable() && args.containsKey(p.name())) {
        scoped.put(p.name(), args.get(p.name()));
      }
    }
    return scoped.isEmpty()
        ? ""
        : scoped.entrySet().stream()
            .map(e -> e.getKey() + "=" + e.getValue())
            .collect(Collectors.joining(", ", " (", ")"));
  }
}
