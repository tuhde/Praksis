// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

/** What makes a rule fire. Schedule and command triggers are added with later milestones. */
public sealed interface Trigger {
  /**
   * Fires for events of one type.
   *
   * @param type the event type, e.g. {@code mail.received}
   * @param filter an optional expression over {@code event} and {@code params} that must be true
   */
  record OnEvent(String type, java.util.Optional<String> filter) implements Trigger {}
}
