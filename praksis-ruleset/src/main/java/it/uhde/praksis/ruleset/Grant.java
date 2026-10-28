// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Permission for the rules of one layer to call one capability, optionally limited by arguments.
 *
 * @param id the name used to revoke the grant and in the audit log
 * @param layer the layer whose rules may use it
 * @param capability the capability name
 * @param args matchers by argument name; arguments that are not listed are not restricted
 * @param note why the grant exists, shown to the user
 */
public record Grant(
    String id,
    String layer,
    String capability,
    Map<String, ArgMatcher> args,
    Optional<String> note) {
  public Grant {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(layer, "layer");
    Objects.requireNonNull(capability, "capability");
    args = Map.copyOf(args);
    Objects.requireNonNull(note, "note");
  }

  /** Whether a call of this capability with these arguments is covered by the grant. */
  public boolean covers(String forLayer, String forCapability, Map<String, Object> callArgs) {
    if (!layer.equals(forLayer) || !capability.equals(forCapability)) {
      return false;
    }
    for (var matcher : args.entrySet()) {
      Object value = callArgs.get(matcher.getKey());
      if (value == null || !matcher.getValue().matches(value)) {
        return false;
      }
    }
    return true;
  }
}
