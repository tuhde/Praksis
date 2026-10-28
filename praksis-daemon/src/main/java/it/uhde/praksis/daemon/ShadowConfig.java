// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.ruleset.Table;
import java.util.Set;

/**
 * The {@code [shadow]} section of {@code praksis.toml}.
 *
 * @param enabled whether new and changed rules are observed before they run
 * @param trialDays how many days a rule is observed at least
 * @param minEvents how many events it must have been seen with at least
 */
public record ShadowConfig(boolean enabled, int trialDays, int minEvents) {
  static ShadowConfig parse(Table t) {
    t.onlyKeys(Set.of("enabled", "trial_days", "min_events"));
    return new ShadowConfig(
        t.bool("enabled", false), count(t, "trial_days", 7), count(t, "min_events", 20));
  }

  private static int count(Table t, String key, int fallback) {
    Object v = t.raw(key).orElse(null);
    if (v == null) {
      return fallback;
    }
    if (v instanceof Number n && n.longValue() >= 0 && n.longValue() <= 100_000) {
      return n.intValue();
    }
    t.problem("'" + key + "' must be a number from 0 to 100000");
    return fallback;
  }
}
