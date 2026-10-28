// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.ruleset.CredentialBinding;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Hands out secrets to modules through the credential bindings of the policy. A secret is whatever
 * the bound command prints; it is read each time, so a rotated secret is picked up, and it is never
 * stored, logged or put into an error message. Each request, allowed or refused, is an audit line
 * with the credential name and who asked.
 */
public final class CredentialResolver {
  private static final long TIMEOUT_SECONDS = 30;

  private final Supplier<Policy> policy;
  private final Store store;
  private final Clock clock;

  /**
   * @param policy the grants and bindings in use, asked again for every request so that a changed
   *     policy applies at once
   */
  public CredentialResolver(Supplier<Policy> policy, Store store, Clock clock) {
    this.policy = policy;
    this.store = store;
    this.clock = clock;
  }

  /** The way to get secrets for one capability or event source. */
  public Credentials forComponent(String component) {
    return name -> secret(component, name);
  }

  private String secret(String component, String name) throws CapabilityException {
    Optional<CredentialBinding> binding = policy.get().credential(name);
    if (binding.isEmpty()) {
      String why = "credential '" + name + "' is not bound in policy.toml";
      audit(component, name, false, why);
      throw new CapabilityException(why, false);
    }
    if (!binding.get().components().contains(component)) {
      String why = "credential '" + name + "' is not bound for " + component;
      audit(component, name, false, why);
      throw new CapabilityException(why, false);
    }
    try {
      String secret = run(name, binding.get().command());
      audit(component, name, true, null);
      return secret;
    } catch (CapabilityException e) {
      audit(component, name, false, e.getMessage());
      throw e;
    }
  }

  private static String run(String name, List<String> command) throws CapabilityException {
    String who = "credential '" + name + "': command '" + command.get(0) + "'";
    Process p;
    try {
      p = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    } catch (IOException e) {
      throw new CapabilityException("cannot run " + who + ": " + e.getMessage(), false, e);
    }
    try {
      p.getOutputStream().close();
      byte[] out = p.getInputStream().readNBytes(64 * 1024);
      if (!p.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        p.destroyForcibly();
        throw new CapabilityException(
            who + " did not finish within " + TIMEOUT_SECONDS + " seconds", true);
      }
      if (p.exitValue() != 0) {
        throw new CapabilityException(who + " failed with exit code " + p.exitValue(), true);
      }
      String secret = new String(out, StandardCharsets.UTF_8);
      if (secret.endsWith("\n")) {
        secret = secret.substring(0, secret.length() - (secret.endsWith("\r\n") ? 2 : 1));
      }
      if (secret.isEmpty()) {
        throw new CapabilityException(who + " printed nothing", false);
      }
      return secret;
    } catch (IOException e) {
      throw new CapabilityException("cannot read the output of " + who, true, e);
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new CapabilityException("interrupted while running " + who, true, e);
    }
  }

  private void audit(String component, String name, boolean allowed, String error) {
    try {
      store.audit(
          new AuditEntry(
              clock.instant(),
              AuditEntry.Kind.CREDENTIAL,
              Optional.empty(),
              name,
              allowed,
              "{\"component\":\"" + component.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
              Optional.ofNullable(error)));
    } catch (StoreException e) {
      System.err.println("praksis: cannot write the audit log: " + e.getMessage());
    }
  }
}
