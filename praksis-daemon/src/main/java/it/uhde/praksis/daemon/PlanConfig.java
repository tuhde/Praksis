// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.ruleset.Table;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code [plan]} section of {@code praksis.toml}: plans that the agent proposes in words and
 * that a person approves run under the grants of this layer, step by step, like the rules of that
 * layer. Without it a plan can be proposed and read but never run.
 *
 * @param layer the layer whose grants decide what a plan may do
 * @param maxSteps the most steps one plan may have
 */
public record PlanConfig(Optional<String> layer, int maxSteps) {
  static PlanConfig parse(Table t, Set<String> layers) {
    t.onlyKeys(Set.of("layer", "max_steps"));
    Optional<String> layer = t.string("layer", false);
    if (layer.isPresent() && !layers.contains(layer.get())) {
      t.problem(
          "'layer' names '"
              + layer.get()
              + "', which is not a layer "
              + new java.util.TreeSet<>(layers));
    }
    int max = 30;
    Object v = t.raw("max_steps").orElse(null);
    if (v != null) {
      if (v instanceof Number n && n.longValue() >= 1 && n.longValue() <= 200) {
        max = n.intValue();
      } else {
        t.problem("'max_steps' must be a whole number from 1 to 200");
      }
    }
    return new PlanConfig(layer, max);
  }
}
