// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * What a person needs when they run one daemon per role (see the identities in the plan). Both
 * tools work on homes on this machine, which the same person owns, and neither grants anything.
 */
final class IdentityTools {
  private IdentityTools() {}

  /**
   * Makes two identities peers of each other: each lists the key and the socket of the other under
   * its name. This is only the introduction; what one may do at the other is a grant ({@code
   * [[peer_grant]]}) that is added by hand, on each side, as for any peer.
   *
   * @return what was done, one line per side
   */
  static List<String> link(Path homeA, String nameA, Path homeB, String nameB) throws IOException {
    if (nameA.equals(nameB) || homeA.equals(homeB)) {
      throw new IOException("an identity is not linked to itself");
    }
    Peers a = new Peers(homeA);
    Peers b = new Peers(homeB);
    List<String> done = new ArrayList<>();
    done.add(introduce(a, nameA, nameB, b.publicKey(), homeB));
    done.add(introduce(b, nameB, nameA, a.publicKey(), homeA));
    return done;
  }

  private static String introduce(Peers own, String ownName, String other, String key, Path home)
      throws IOException {
    String socket = home.resolve("praksis.sock").toString();
    Optional<Peers.Peer> listed = own.byName(other);
    if (listed.isPresent()) {
      if (!listed.get().key().equals(key)) {
        throw new IOException(
            ownName
                + " lists a peer named '"
                + other
                + "' with another key; remove it first (praksis peers remove) if it is stale");
      }
      return ownName + " lists " + other + " already";
    }
    own.add(other, key, Optional.of(socket), false);
    return ownName + " now lists " + other + " (" + Peers.fingerprint(key) + ")";
  }

  /** Setting names whose value tells which account or which data a module works on. */
  private static boolean identifies(String key) {
    String k = key.toLowerCase(java.util.Locale.ROOT);
    for (String part :
        List.of(
            "user",
            "account",
            "email",
            "address",
            "login",
            "mailbox",
            "root",
            "dir",
            "path",
            "folder",
            "file",
            "database")) {
      if (k.contains(part)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Finds module settings that two identities share: the same value for a setting that names an
   * account or a place (a user, an address, a directory). A mailbox belongs to exactly one
   * identity, because two daemons that watch or write the same mailbox would both act on it.
   *
   * @param settings the module settings of each identity, by identity name
   */
  static List<String> sharedAccounts(Map<String, Map<String, Map<String, Object>>> settings) {
    // module / setting / value -> identities
    Map<String, List<String>> seen = new TreeMap<>();
    settings.forEach(
        (identity, modules) ->
            modules.forEach(
                (module, values) ->
                    values.forEach(
                        (key, value) -> {
                          if (identifies(key)
                              && value != null
                              && !String.valueOf(value).isBlank()) {
                            seen.computeIfAbsent(
                                    "module " + module + ", " + key + " = " + value,
                                    k -> new ArrayList<>())
                                .add(identity);
                          }
                        })));
    List<String> out = new ArrayList<>();
    seen.forEach(
        (what, identities) -> {
          if (identities.size() > 1) {
            out.add(
                what + ": used by " + String.join(" and ", identities.stream().sorted().toList()));
          }
        });
    return out;
  }
}
