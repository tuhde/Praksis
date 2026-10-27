// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/** What a capability call can change. Rules declare the strongest class of their actions. */
public enum EffectClass {
  /** Changes nothing. */
  READ_ONLY,
  /** Changes state on this machine only. */
  LOCAL_WRITE,
  /** Removes or overwrites data that cannot be restored. Needs an explicit grant. */
  DESTRUCTIVE,
  /** Has effects outside this machine (sends mail, calls a remote API). Needs an explicit grant. */
  EXTERNAL
}
