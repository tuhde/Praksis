// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A local name for a secret, and who may ask for it. The secret is whatever the command prints; it
 * is never stored by Praksis.
 *
 * @param name the name modules ask for, e.g. {@code imap:work}
 * @param command the command and its arguments; its output, without the final line break, is the
 *     secret
 * @param components the capabilities and event sources that may ask for this name; any other is
 *     refused
 * @param note what it is for, shown to the user
 */
public record CredentialBinding(
    String name, List<String> command, List<String> components, Optional<String> note) {
  public CredentialBinding {
    Objects.requireNonNull(name, "name");
    command = List.copyOf(command);
    components = List.copyOf(components);
    Objects.requireNonNull(note, "note");
  }
}
