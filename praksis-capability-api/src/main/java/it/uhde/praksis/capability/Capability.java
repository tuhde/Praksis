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
   * Tells the implementation, once, after {@link #configure}, that the program runs offline: text
   * and data of a person must not leave this machine. A module lists what reaches other machines in
   * {@code network = [...]} of its {@code module.toml} and that is then not called at all; a module
   * that decides per call (a model server that may be on this machine or not) uses this to refuse
   * what would leave it. The default does nothing.
   */
  default void useOffline() {}

  /**
   * Hands over the directory where a person's files wait to be attached to something, once, after
   * {@link #configure}. A person puts a file there by choosing it in the frontend; it is named by a
   * random token, readable by this user alone, and removed after a day. The implementation may only
   * read it, and only the files whose token it is given, so that nothing a rule says can make it
   * read a file the person did not choose. The default does nothing.
   */
  default void useUploadsDirectory(java.nio.file.Path directory) {}

  /**
   * Releases what the implementation holds, e.g. connections and their threads. Called once when
   * the daemon shuts down, after the last call.
   */
  default void close() {}

  /** The capability name, e.g. {@code mail.move}. Matches an entry in the module descriptor. */
  String name();

  /**
   * The arguments as they may be written to the audit log and to the record of a rule in trial: a
   * capability whose arguments can hold secrets returns a copy with those values replaced. The
   * default returns the arguments as they are.
   */
  default Map<String, Object> redact(Map<String, Object> args) {
    return args;
  }

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
