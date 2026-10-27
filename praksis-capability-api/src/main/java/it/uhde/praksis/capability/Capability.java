// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.Map;

/**
 * Something a module can do. Rules call capabilities through the engine, which checks arguments
 * against the descriptor and permissions against the grants before {@link #invoke} runs.
 * Implementations are found with {@link java.util.ServiceLoader}.
 */
public interface Capability {
  /**
   * Gives the implementation the settings of its module from the local configuration, once, before
   * any other method is called. The values use the Java types listed at {@link ValueType}.
   *
   * @throws CapabilityException if the settings are missing or invalid
   */
  default void configure(java.util.Map<String, Object> settings) throws CapabilityException {}

  /** The capability name, e.g. {@code mail.move}. Matches an entry in the module descriptor. */
  String name();

  /**
   * Performs the capability.
   *
   * @param context the circumstances of this call
   * @param args the arguments, typed as declared in the descriptor
   * @return the results, typed as declared in the descriptor
   * @throws CapabilityException if the call fails
   */
  Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException;
}
