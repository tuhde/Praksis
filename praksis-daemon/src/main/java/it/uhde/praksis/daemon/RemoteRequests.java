// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.RemoteRequest;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.io.IOException;
import java.time.Clock;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a connected daemon asks of a person. A function that a remote offers anew is not usable
 * until it is approved, and a change of a pinned set is a difference that waits for approval. Both
 * are requests in the approvals queue (the topic {@code approvals}), and both are bounded: one
 * remote can have {@value #MAX_PENDING} requests pending, so it cannot flood the queue; what does
 * not fit is asked again the next time its catalog is read, once the queue has room.
 *
 * <p>A peer with a <em>standing read grant</em> ({@code standing_read = true} in {@code
 * peers.toml}) has the functions that are safe accepted without asking: those that only read
 * ({@code READ_ONLY}). Anything that writes, sends or deletes always asks. Copying what is read
 * into a local backend is not possible yet, so the data-flow rule does not come into it.
 */
final class RemoteRequests {
  static final int MAX_PENDING = 20;
  private static final System.Logger LOG = System.getLogger(RemoteRequests.class.getName());

  private final Store store;
  private final RemotePins pins;
  private final RemoteCatalogs catalogs;
  private final Clock clock;
  private final Runnable policyChanged;

  /**
   * @param policyChanged called after a pin moved, so that the policy is read again with it
   */
  RemoteRequests(
      Store store, RemotePins pins, RemoteCatalogs catalogs, Clock clock, Runnable policyChanged) {
    this.store = store;
    this.pins = pins;
    this.catalogs = catalogs;
    this.clock = clock;
    this.policyChanged = policyChanged;
  }

  /** Looks at a catalog that was just received and opens what a person has to decide. */
  void onCatalog(Peers.Peer peer, Response.PeerCatalog catalog) {
    int overflow = 0;
    try {
      for (var c : catalog.capabilities()) {
        boolean auto = peer.standingRead() && c.effect().equals("READ_ONLY");
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("capability", c.name());
        detail.put("effect", c.effect());
        detail.put("dry_run", c.supportsDryRun());
        detail.put("params", c.params().stream().map(f -> f.name() + ": " + f.type()).toList());
        var outcome =
            store.openRemoteRequest(
                clock.instant(),
                peer.name(),
                "capability",
                "capability:" + c.name(),
                detail,
                auto ? RemoteRequest.State.AUTO : RemoteRequest.State.PENDING,
                MAX_PENDING);
        if (outcome == Store.Opened.LIMIT) {
          overflow++;
        }
      }
      for (var p : pins.pending(peer.name(), catalog)) {
        if (!p.changed()) {
          continue;
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("set", p.pin().set());
        detail.put("added", p.added());
        detail.put("removed", p.removed());
        detail.put("gone", p.gone());
        var outcome =
            store.openRemoteRequest(
                clock.instant(),
                peer.name(),
                "pin",
                "pin:" + p.pin().set(),
                detail,
                RemoteRequest.State.PENDING,
                MAX_PENDING);
        if (outcome == Store.Opened.LIMIT) {
          overflow++;
        }
      }
    } catch (StoreException | IOException e) {
      LOG.log(System.Logger.Level.WARNING, "cannot open requests for " + peer.name() + ": " + e);
      return;
    }
    if (overflow > 0) {
      LOG.log(
          System.Logger.Level.WARNING,
          peer.name()
              + " has "
              + overflow
              + " more request(s) than the "
              + MAX_PENDING
              + " that wait; answer some (praksis peers requests) and they are asked again");
    }
  }

  /** Whether the function of this peer was accepted, by a person or by a standing grant. */
  boolean accepted(String peer, String capability) throws StoreException {
    return store.remoteAccepted(peer, "capability:" + capability);
  }

  Response.RemoteRequestList list() throws StoreException {
    return new Response.RemoteRequestList(
        store.remoteRequests(EnumSet.allOf(RemoteRequest.State.class)).stream()
            .map(
                r ->
                    new Response.RemoteRequestList.Item(
                        r.id(),
                        r.time().toString(),
                        r.peer(),
                        r.kind(),
                        r.subject(),
                        r.state().name(),
                        r.detail()))
            .toList());
  }

  /** Answers a request; for a pin, approving moves the pin to what the remote offers now. */
  String answer(long id, String decision) throws Approvals.Failure, StoreException {
    RemoteRequest r =
        store
            .remoteRequest(id)
            .orElseThrow(() -> new Approvals.Failure("there is no remote request #" + id));
    if (r.state() != RemoteRequest.State.PENDING) {
      throw new Approvals.Failure("request #" + id + " was answered already (" + r.state() + ")");
    }
    if (!decision.equals("approve") && !decision.equals("refuse")) {
      throw new Approvals.Failure("the decision must be 'approve' or 'refuse'");
    }
    boolean approve = decision.equals("approve");
    String message;
    if (approve && r.kind().equals("pin")) {
      String set = r.subject().substring("pin:".length());
      try {
        Optional<RemoteCatalogs.Remote> remote = catalogs.cached(r.peer());
        if (remote.isEmpty()) {
          throw new Approvals.Failure("there is no catalog of " + r.peer());
        }
        pins.accept(r.peer(), set, remote.get().catalog(), clock.instant());
      } catch (IOException e) {
        throw new Approvals.Failure(e.getMessage(), e);
      }
      policyChanged.run();
      message = r.peer() + "/" + set + " is pinned to what " + r.peer() + " offers now.";
    } else if (approve) {
      message =
          r.subject()
              + " of "
              + r.peer()
              + " is accepted; the local policy still decides who may use it.";
    } else {
      message =
          r.subject() + " of " + r.peer() + " is refused and is not asked again unless it changes.";
    }
    store.answerRemoteRequest(
        id, approve ? RemoteRequest.State.APPROVED : RemoteRequest.State.REFUSED, clock.instant());
    store.audit(
        AuditEntry.of(
            clock.instant(),
            AuditEntry.Kind.APPROVAL,
            Optional.of(AuditEntry.PEER_ACTOR + r.peer()),
            "remote request #" + id,
            Map.of("subject", r.subject(), "decision", decision)));
    return message;
  }

  /** The requests of one peer that wait, for tests and status. */
  List<RemoteRequest> pendingOf(String peer) throws StoreException {
    return store.remoteRequests(EnumSet.of(RemoteRequest.State.PENDING)).stream()
        .filter(r -> r.peer().equals(peer))
        .toList();
  }
}
