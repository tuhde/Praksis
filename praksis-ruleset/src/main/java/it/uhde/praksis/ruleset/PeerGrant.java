// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Permission for another daemon, known by the name it has in {@code peers.toml}, to call one
 * capability of this daemon, optionally limited by arguments. This is the ceiling of what a peer
 * can do here; what the peer's own policy lets its rules do is decided there. A peer without grants
 * can do nothing.
 *
 * @param id the name used to revoke the grant and in the audit log
 * @param peer the name of the peer
 * @param capability the capability name
 * @param args matchers by argument name; arguments that are not listed are not restricted
 * @param note why the grant exists, shown to the user
 */
public record PeerGrant(
    String id,
    String peer,
    String capability,
    Map<String, ArgMatcher> args,
    Optional<String> note) {
  public PeerGrant {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(peer, "peer");
    Objects.requireNonNull(capability, "capability");
    args = Map.copyOf(args);
    Objects.requireNonNull(note, "note");
  }

  /** Whether a call of this capability with these arguments by this peer is covered. */
  public boolean covers(String forPeer, String forCapability, Map<String, Object> callArgs) {
    return asGrant().covers(forPeer, forCapability, callArgs);
  }

  /** The same grant with the peer in the place of the layer, so that matching is the same. */
  Grant asGrant() {
    return new Grant(id, peer, capability, args, note);
  }
}
