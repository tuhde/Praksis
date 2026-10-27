// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/** What a capability call can change. Rules declare the strongest class of their actions. */
public enum EffectClass {
  /** Changes nothing. */
  READ_ONLY,
  /** Changes state on this machine only. */
  LOCAL_WRITE,
  /** Removes or overwrites data that cannot be restored. Needs an explicit grant. */
  DESTRUCTIVE,
  /** Has effects outside this machine (sends mail, calls a remote API). Needs an explicit grant. */
  EXTERNAL;

  /** The name used in rulesets and descriptors, e.g. {@code local-write}. */
  public String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /** The class with the given {@link #id()}, or empty if there is none. */
  public static Optional<EffectClass> fromId(String id) {
    return Arrays.stream(values()).filter(e -> e.id().equals(id)).findFirst();
  }
}
