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

  /**
   * Hands over the way to get secrets, once, after {@link #configure} and before any other method.
   * The default does nothing; implementations that need a secret keep it. Every request goes
   * through the user's credential bindings, which name the capabilities and event sources allowed
   * to ask.
   */
  default void useCredentials(Credentials credentials) {}

  /**
   * Hands over the directory this module may keep its own files in, such as a cache, once, before
   * any other method but {@link #configure}. It is private to the module (every module of the
   * daemon has its own), it exists, and it is not backed up or synced by Praksis: whatever is kept
   * there must be possible to rebuild. The default does nothing.
   */
  default void useDataDirectory(java.nio.file.Path directory) {}

  /**
   * Releases what the implementation holds, e.g. connections and their threads. Called once when
   * the daemon shuts down, after the last call.
   */
  default void close() {}

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
