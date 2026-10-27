// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/**
 * A module part that produces events from outside the engine (a mailbox, a file watcher, a
 * webhook). Listening needs a grant like any other capability. Implementations are found with
 * {@link java.util.ServiceLoader}.
 */
public interface EventSource {
  /** The name of this source, unique across modules. Matches an entry in the module descriptor. */
  String name();

  /** Starts producing events. Must return quickly; run any long-lived work on own threads. */
  void start(EventSink sink) throws CapabilityException;

  /** Stops producing events and releases resources. */
  void stop();
}
