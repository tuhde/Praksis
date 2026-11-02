// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.ruleset.PeerGrant;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.StoreException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * What another daemon may do here. This daemon enforces its own grants for the identity of the peer
 * ({@code [[peer_grant]]} in {@code policy.toml}) and that is the ceiling: the peer's own policy
 * can only narrow it further. A peer with no grant for a call is denied, and every decision is
 * recorded in the audit log with the peer as the actor ({@code peer:<name>}).
 */
final class PeerAccess {
  /** Takes a line for the audit log. */
  @FunctionalInterface
  interface Audit {
    void accept(AuditEntry entry) throws StoreException;
  }

  private final Supplier<Policy> policy;
  private final Audit audit;

  /**
   * @param policy the grants in use now (read at each call, so a change in {@code policy.toml}
   *     applies at once)
   * @param audit where decisions are recorded
   */
  PeerAccess(Supplier<Policy> policy, Audit audit) {
    this.policy = policy;
    this.audit = audit;
  }

  /** Decides one call of a peer and records the decision. */
  AccessPolicy.Decision check(
      Peers.Peer peer, CapabilityDescriptor capability, Map<String, Object> args) {
    Optional<PeerGrant> grant = policy.get().findForPeer(peer.name(), capability.name(), args);
    AccessPolicy.Decision decision =
        grant.isPresent()
            ? AccessPolicy.Decision.allow()
            : AccessPolicy.Decision.deny(
                "peer '" + peer.name() + "' has no grant for " + capability.name());
    try {
      audit.accept(
          AuditEntry.peerCall(
              Instant.now(),
              peer.name(),
              capability.name(),
              args,
              decision.allowed(),
              decision.allowed() ? Optional.empty() : Optional.of(decision.reason())));
    } catch (StoreException | RuntimeException e) {
      // a broken audit table must not stop the work, but it must not go unnoticed
      System.err.println("praksis: cannot write the audit log: " + e.getMessage());
    }
    return decision;
  }
}
