// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.engine.AuditLog;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;

/**
 * Calls a capability of a connected daemon. The effective permission is what the remote allows (its
 * grants for this daemon, enforced there) and what the local policy grants for the caller; either
 * side alone is not enough. The call is forwarded, never run here, and a remote capability is never
 * installed. The capability must be in the last catalog of the remote, because that is what the
 * local policy was written against, and under the name {@code acme/entity.read} the local policy
 * and the audit log know it.
 */
final class RemoteCalls {
  private final Peers peers;
  private final RemoteCatalogs catalogs;
  private final AccessPolicy local;
  private final AuditLog audit;
  private final Clock clock;
  private final Accepted accepted;

  /** Whether a function of a remote was accepted. */
  @FunctionalInterface
  interface Accepted {
    boolean test(String peer, String capability) throws IOException;
  }

  /**
   * @param accepted whether a function was accepted by a person or a standing grant; a rule may use
   *     only accepted functions
   * @param local the local policy; asked with the caller and the qualified name of the capability
   * @param audit where the local side of a call is recorded
   */
  RemoteCalls(
      Peers peers,
      RemoteCatalogs catalogs,
      AccessPolicy local,
      AuditLog audit,
      Clock clock,
      Accepted accepted) {
    this.accepted = accepted;
    this.peers = peers;
    this.catalogs = catalogs;
    this.local = local;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Makes the call.
   *
   * @param caller who calls here: a rule id, or {@code user} for a person at the socket
   * @param peerName the remote
   * @param capability its name at the remote (no prefix)
   * @throws IOException if the call is refused, here or there, or fails
   */
  Map<String, Object> call(
      String caller, String peerName, String capability, Map<String, Object> args, boolean dryRun)
      throws IOException {
    Peers.Peer peer =
        peers.byName(peerName).orElseThrow(() -> new IOException("no peer named " + peerName));
    String qualified = peerName + "/" + capability;
    Response.PeerCatalog.CapabilityInfo info =
        catalogs
            .cached(peerName)
            .flatMap(
                r ->
                    r.catalog().capabilities().stream()
                        .filter(c -> c.name().equals(capability))
                        .findFirst())
            .orElseThrow(
                () ->
                    new IOException(
                        qualified
                            + " is not in the catalog of "
                            + peerName
                            + " (praksis peers catalog "
                            + peerName
                            + ")"));
    if (!accepted.test(peerName, capability)) {
      throw new IOException(
          qualified + " is not accepted yet (praksis peers requests, then peers answer)");
    }
    AccessPolicy.Decision decision = local.check(caller, descriptor(qualified, info), args);
    if (!decision.allowed()) {
      audit.record(clock.instant(), caller, qualified, args, false, Optional.of(decision.reason()));
      throw new IOException(decision.reason());
    }
    try (PeerLink link = PeerLink.connect(peers, peer)) {
      Response r = link.send(new Request.PeerCall(capability, args, dryRun));
      if (r instanceof Response.PeerCallResult result) {
        audit.record(clock.instant(), caller, qualified, args, true, Optional.empty());
        return result.results();
      }
      String why = r instanceof Response.Error e ? e.message() : "unexpected answer";
      audit.record(clock.instant(), caller, qualified, args, true, Optional.of(why));
      throw new IOException(peerName + ": " + why);
    }
  }

  /** The descriptor under the qualified name, made from the cleaned catalog. */
  static CapabilityDescriptor descriptor(
      String qualified, Response.PeerCatalog.CapabilityInfo info) {
    EffectClass effect;
    try {
      effect = EffectClass.valueOf(info.effect());
    } catch (IllegalArgumentException e) {
      // an effect we do not know is treated as the most careful one
      effect = EffectClass.EXTERNAL;
    }
    return new CapabilityDescriptor(
        qualified,
        effect,
        info.supportsDryRun(),
        info.params().stream().map(RemoteCalls::field).flatMap(Optional::stream).toList(),
        info.results().stream().map(RemoteCalls::field).flatMap(Optional::stream).toList());
  }

  private static Optional<FieldDescriptor> field(Response.PeerCatalog.Field f) {
    return ValueType.fromId(f.type())
        .map(t -> new FieldDescriptor(f.name(), t, f.required(), f.scopable()));
  }
}
