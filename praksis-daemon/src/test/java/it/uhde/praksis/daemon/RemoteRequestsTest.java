// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteRequestsTest {
  @TempDir Path dir;

  private static Response.PeerCatalog.CapabilityInfo cap(String name, String effect) {
    return new Response.PeerCatalog.CapabilityInfo(name, effect, false, List.of(), List.of());
  }

  private static Response.PeerCatalog catalog(
      List<Response.PeerCatalog.CapabilityInfo> caps,
      List<Response.EntitySetList.EntitySetInfo> sets) {
    return new Response.PeerCatalog(PeerVersions.CATALOG_VERSION, caps, List.of(), sets);
  }

  private static Response.EntitySetList.EntitySetInfo set(String... members) {
    List<String> m = List.of(members);
    return new Response.EntitySetList.EntitySetInfo(
        "finance", "f", null, null, "h", m, List.of(), m);
  }

  private static Peers.Peer peer(boolean standing) {
    return new Peers.Peer("acme", "k", Optional.empty(), standing);
  }

  @Test
  void aNewFunctionWaitsForApproval() throws Exception {
    try (Store store = Store.open(dir.resolve("db"))) {
      List<Boolean> reloaded = new ArrayList<>();
      var r =
          new RemoteRequests(
              store,
              new RemotePins(dir),
              new RemoteCatalogs(dir),
              Clock.systemUTC(),
              () -> reloaded.add(true));
      r.onCatalog(peer(false), catalog(List.of(cap("entity.read", "READ_ONLY")), List.of()));
      assertFalse(r.accepted("acme", "entity.read"));
      assertEquals(1, r.pendingOf("acme").size());
      // seen again: no second request
      r.onCatalog(peer(false), catalog(List.of(cap("entity.read", "READ_ONLY")), List.of()));
      assertEquals(1, r.pendingOf("acme").size());

      long id = r.pendingOf("acme").get(0).id();
      r.answer(id, "approve");
      assertTrue(r.accepted("acme", "entity.read"));
      assertThrows(Approvals.Failure.class, () -> r.answer(id, "approve"));
    }
  }

  @Test
  void aStandingReadGrantAcceptsOnlyWhatIsSafe() throws Exception {
    try (Store store = Store.open(dir.resolve("db"))) {
      var r =
          new RemoteRequests(
              store, new RemotePins(dir), new RemoteCatalogs(dir), Clock.systemUTC(), () -> {});
      r.onCatalog(
          peer(true),
          catalog(
              List.of(
                  cap("entity.read", "READ_ONLY"),
                  cap("entity.delete", "DESTRUCTIVE"),
                  cap("mail.send", "EXTERNAL")),
              List.of()));
      assertTrue(r.accepted("acme", "entity.read"));
      assertFalse(r.accepted("acme", "entity.delete"));
      assertFalse(r.accepted("acme", "mail.send"));
      assertEquals(2, r.pendingOf("acme").size());
    }
  }

  @Test
  void aRefusedFunctionIsNotAskedAgain() throws Exception {
    try (Store store = Store.open(dir.resolve("db"))) {
      var r =
          new RemoteRequests(
              store, new RemotePins(dir), new RemoteCatalogs(dir), Clock.systemUTC(), () -> {});
      var c = catalog(List.of(cap("entity.delete", "DESTRUCTIVE")), List.of());
      r.onCatalog(peer(false), c);
      r.answer(r.pendingOf("acme").get(0).id(), "refuse");
      r.onCatalog(peer(false), c);
      assertTrue(r.pendingOf("acme").isEmpty());
      assertFalse(r.accepted("acme", "entity.delete"));
      // but a changed function (here another effect) is a new question
      r.onCatalog(peer(false), catalog(List.of(cap("entity.delete", "EXTERNAL")), List.of()));
      assertEquals(1, r.pendingOf("acme").size());
    }
  }

  @Test
  void aRemoteCannotFloodTheQueue() throws Exception {
    try (Store store = Store.open(dir.resolve("db"))) {
      var r =
          new RemoteRequests(
              store, new RemotePins(dir), new RemoteCatalogs(dir), Clock.systemUTC(), () -> {});
      var many = IntStream.range(0, 50).mapToObj(i -> cap("c" + i, "LOCAL_WRITE")).toList();
      r.onCatalog(peer(false), catalog(many, List.of()));
      assertEquals(RemoteRequests.MAX_PENDING, r.pendingOf("acme").size());
      // another remote has its own room
      r.onCatalog(
          new Peers.Peer("other", "k2", Optional.empty(), false),
          catalog(List.of(cap("x", "LOCAL_WRITE")), List.of()));
      assertEquals(1, r.pendingOf("other").size());
      // answering makes room; the rest is asked at the next catalog
      r.answer(r.pendingOf("acme").get(0).id(), "refuse");
      r.onCatalog(peer(false), catalog(many, List.of()));
      assertEquals(RemoteRequests.MAX_PENDING, r.pendingOf("acme").size());
    }
  }

  @Test
  void aChangedPinOpensARequestThatMovesThePinWhenApproved() throws Exception {
    try (Store store = Store.open(dir.resolve("db"))) {
      var pins = new RemotePins(dir);
      var catalogs = new RemoteCatalogs(dir);
      List<Boolean> reloaded = new ArrayList<>();
      var r =
          new RemoteRequests(store, pins, catalogs, Clock.systemUTC(), () -> reloaded.add(true));
      pins.pin("acme", "finance", catalog(List.of(), List.of(set("invoice"))), Instant.now());

      var changed = catalog(List.of(), List.of(set("invoice", "salary")));
      catalogs.store(RemoteCatalogs.clean("acme", Instant.now(), false, changed));
      r.onCatalog(peer(false), changed);
      var pending = r.pendingOf("acme");
      assertEquals(1, pending.size());
      assertEquals("pin:finance", pending.get(0).subject());
      assertEquals(java.util.Set.of("invoice"), pins.sets().get("acme/finance"));

      r.answer(pending.get(0).id(), "approve");
      assertEquals(java.util.Set.of("invoice", "salary"), pins.sets().get("acme/finance"));
      assertEquals(List.of(true), reloaded);
    }
  }
}
