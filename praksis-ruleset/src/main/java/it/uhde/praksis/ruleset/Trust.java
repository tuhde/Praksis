// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.Locale;

/**
 * Whose commits count as the user's own. A commit is the user's only if git finds its signature
 * good and the key is one of these; the author name and the commit message prove nothing, since
 * anyone who can commit can write them.
 *
 * @param humanKeys fingerprints or key ids of the user's signing keys: gpg fingerprints or key ids
 *     (a key id is matched against the end of the fingerprint, 8 characters at least), or SSH
 *     fingerprints such as {@code SHA256:...}
 */
public record Trust(List<String> humanKeys) {
  public Trust {
    humanKeys = List.copyOf(humanKeys);
  }

  /** Nobody is trusted. */
  public static Trust none() {
    return new Trust(List.of());
  }

  /** Whether this signer (a fingerprint or key id reported by git) is one of the user's keys. */
  public boolean isHuman(String signer) {
    if (signer == null || signer.isBlank()) {
      return false;
    }
    String s = signer.strip().toLowerCase(Locale.ROOT);
    for (String key : humanKeys) {
      String k = key.strip().toLowerCase(Locale.ROOT);
      if (k.equals(s) || (k.length() >= 8 && !k.startsWith("sha256:") && s.endsWith(k))) {
        return true;
      }
    }
    return false;
  }
}
