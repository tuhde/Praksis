// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

/**
 * Something wrong in a ruleset.
 *
 * @param location where, e.g. {@code rules/mail.archive.toml: action 'move', arg 'folder'}
 * @param message what is wrong
 */
public record Problem(String location, String message) {
  @Override
  public String toString() {
    return location + ": " + message;
  }
}
