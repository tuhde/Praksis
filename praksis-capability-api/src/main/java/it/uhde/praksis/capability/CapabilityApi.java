// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/**
 * The version of the capability API: this package, the format of {@code module.toml} and what the
 * daemon promises a module (settings, credentials, directories, the context class loader).
 *
 * <p>A module declares the version it was built for as {@code api_version} in its {@code
 * module.toml}, and the daemon loads only modules of its own version: a module is code in the
 * process of the daemon, and a mismatch would show as an error far from its cause. The number is
 * raised by one whenever a change could break a module that was built before it; until 1.0.0 that
 * can happen with any minor step, so no range is promised.
 */
public final class CapabilityApi {
  /** The version of the capability API that this Praksis provides. */
  public static final int VERSION = 1;

  private CapabilityApi() {}

  /**
   * Says why a module that was built for another version cannot be used, or nothing if it can.
   *
   * @param module the name of the module
   * @param built the {@code api_version} of its {@code module.toml}
   */
  public static java.util.Optional<String> mismatch(String module, int built) {
    if (built == VERSION) {
      return java.util.Optional.empty();
    }
    return java.util.Optional.of(
        "module '"
            + module
            + "' was built for capability API version "
            + built
            + ", this Praksis provides version "
            + VERSION
            + (built > VERSION
                ? ": update Praksis, or use an older version of the module"
                : ": update the module, or use an older version of Praksis"));
  }
}
