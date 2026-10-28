// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/**
 * Gives a module the secret behind a credential name. The names and the secrets are bound locally
 * by the user, outside the rulesets, and the binding says which capabilities and event sources may
 * ask for which name. Rules never see a secret. Every request is audited, the secret is not.
 */
@FunctionalInterface
public interface Credentials {
  /**
   * Looks up a secret.
   *
   * @param name the credential name, e.g. {@code imap:work}
   * @return the secret
   * @throws CapabilityException if there is no such binding, it does not allow the asking part, or
   *     the secret cannot be read
   */
  String secret(String name) throws CapabilityException;
}
