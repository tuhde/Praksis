// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/**
 * A module part that produces events from outside the engine (a mailbox, a file watcher, a
 * webhook). Listening needs a grant like any other capability. Implementations are found with
 * {@link java.util.ServiceLoader}.
 */
public interface EventSource {
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

  /** The name of this source, unique across modules. Matches an entry in the module descriptor. */
  String name();

  /** Starts producing events. Must return quickly; run any long-lived work on own threads. */
  void start(EventSink sink) throws CapabilityException;

  /** Stops producing events and releases resources. */
  void stop();
}
