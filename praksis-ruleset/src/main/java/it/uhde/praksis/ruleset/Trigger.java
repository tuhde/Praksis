// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

/** What makes a rule fire. Schedule triggers are added with a later milestone. */
public sealed interface Trigger {
  /**
   * Fires for events of one type.
   *
   * @param type the event type, e.g. {@code mail.received}
   * @param filter an optional expression over {@code event} and {@code params} that must be true
   */
  record OnEvent(String type, java.util.Optional<String> filter) implements Trigger {}

  /**
   * Fires when a person starts it. A command is an ordinary rule that nothing but a person starts:
   * its calls need grants like any other rule's. The arguments the person gives are the data of an
   * event of the type {@code command.<rule id>}, so the actions read them as {@code
   * event.data.<name>}.
   *
   * @param title what a person sees, e.g. on a button
   * @param description what it does, or empty
   * @param params the typed arguments
   */
  record OnCommand(
      String title, java.util.Optional<String> description, java.util.List<CommandParam> params)
      implements Trigger {
    public OnCommand {
      params = java.util.List.copyOf(params);
    }
  }

  /**
   * An argument of a command.
   *
   * @param name the name, an identifier
   * @param type the value type
   * @param required whether the person must give it (never with a default)
   * @param defaultValue the value used if the person gives none
   * @param label what a person sees next to the field, or empty
   */
  record CommandParam(
      String name,
      it.uhde.praksis.capability.ValueType type,
      boolean required,
      java.util.Optional<Object> defaultValue,
      java.util.Optional<String> label) {}
}
