// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/** Where an {@link EventSource} delivers its events. */
public interface EventSink {
  /**
   * Hands an event to the engine. Returns once the engine has stored it, so the source may then
   * acknowledge the event to its own origin (e.g. mark a mail as seen).
   */
  void accept(Event event);
}
