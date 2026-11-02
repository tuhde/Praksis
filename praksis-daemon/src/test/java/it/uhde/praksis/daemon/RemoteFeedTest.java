// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteFeedTest {
  @TempDir Path dir;

  private static Response.EntityTypeList.EntityTypeInfo type(String id) {
    return new Response.EntityTypeList.EntityTypeInfo(
        id, id, null, null, "h", List.of(), List.of());
  }

  private static Response.EntitySetList.EntitySetInfo set(String id, List<String> members) {
    return new Response.EntitySetList.EntitySetInfo(
        id, id, null, null, "h", members, List.of(), members);
  }

  private static Response.PeerCatalog catalog(
      List<String> types, List<Response.EntitySetList.EntitySetInfo> sets) {
    return new Response.PeerCatalog(
        PeerVersions.CATALOG_VERSION,
        List.of(),
        types.stream().map(RemoteFeedTest::type).toList(),
        sets);
  }

  @Test
  void aVanishedItemIsMarkedAndComesBack() throws IOException {
    AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T10:00:00Z"));
    Clock clock =
        new Clock() {
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          public Clock withZone(java.time.ZoneId z) {
            return this;
          }

          public Instant instant() {
            return now.get();
          }
        };
    List<String> told = new ArrayList<>();
    RemoteFeed feed = new RemoteFeed(dir, "acme", clock, told::add);
    feed.apply(
        catalog(List.of("invoice", "offer"), List.of(set("sales", List.of("invoice", "offer")))));
    assertTrue(feed.gone().isEmpty());

    now.set(Instant.parse("2026-11-02T11:00:00Z"));
    var diff = feed.apply(catalog(List.of("invoice"), List.of(set("sales", List.of("invoice")))));
    assertEquals(Set.of("type:offer", "member:sales/offer"), diff.removed());
    assertEquals(2, feed.gone().size());
    assertTrue(feed.unavailable("type:offer").orElseThrow().contains("2026-11-02T11:00:00Z"));
    assertTrue(feed.unavailable("type:invoice").isEmpty());
    assertTrue(told.contains("remote"));

    // a restart remembers
    RemoteFeed again = new RemoteFeed(dir, "acme", clock, t -> {});
    assertEquals(2, again.gone().size());

    // it comes back
    now.set(Instant.parse("2026-11-02T12:00:00Z"));
    feed.apply(
        catalog(List.of("invoice", "offer"), List.of(set("sales", List.of("invoice", "offer")))));
    assertTrue(feed.gone().isEmpty());
    assertTrue(new RemoteFeed(dir, "acme", clock, t -> {}).gone().isEmpty());
  }

  @Test
  void anUnreachableRemoteMarksNothingGone() throws IOException {
    RemoteFeed feed = new RemoteFeed(dir, "acme", Clock.systemUTC(), t -> {});
    feed.apply(catalog(List.of("invoice"), List.of()));
    feed.unreachable();
    assertTrue(feed.gone().isEmpty());
    assertTrue(feed.unreachableSince().isPresent());
    assertTrue(feed.unavailable("type:invoice").orElseThrow().contains("cannot be reached"));
    feed.apply(catalog(List.of("invoice"), List.of()));
    assertTrue(feed.unreachableSince().isEmpty());
    assertTrue(feed.unavailable("type:invoice").isEmpty());
  }

  @Test
  void followsTheFeedOverARealConnection() throws Exception {
    Path acmeHome = Files.createDirectory(dir.resolve("a"));
    Path meHome = Files.createDirectory(dir.resolve("m"));
    Peers acme = new Peers(acmeHome);
    Peers me = new Peers(meHome);
    Path socket = dir.resolve("s");
    acme.add("me", me.publicKey(), Optional.empty(), false);
    me.add("acme", acme.publicKey(), Optional.of(socket.toString()), false);

    AtomicReference<Response.PeerCatalog> offered =
        new AtomicReference<>(catalog(List.of("invoice", "offer"), List.of()));
    ApiServer server =
        new ApiServer(
            socket,
            r -> new Response.Error("no"),
            () -> {},
            new PeerHandshake(acme),
            (peer, req) -> {
              if (req instanceof Request.PeerCatalog) {
                return offered.get();
              }
              if (req instanceof Request.PeerChanges c) {
                if (c.since() != 0) {
                  try {
                    Thread.sleep(50);
                  } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                  }
                }
                return new Response.PeerChangeList(
                    c.since() + 1, List.of(), PeerCatalogs.hash(offered.get()));
              }
              return new Response.Error("a peer cannot do this");
            });
    server.start();
    RemoteFeed feed = new RemoteFeed(meHome, "acme", Clock.systemUTC(), t -> {});
    Thread t = Thread.ofVirtual().start(() -> feed.run(me, acmeAsPeer(me)));
    try {
      await(() -> new RemoteCatalogsReader(meHome).has("type:offer"));
      offered.set(catalog(List.of("invoice"), List.of()));
      await(() -> feed.gone().containsKey("type:offer"));
      assertFalse(feed.gone().containsKey("type:invoice"));
      server.close();
      await(() -> feed.unreachableSince().isPresent());
    } finally {
      t.interrupt();
      server.close();
    }
  }

  private static Peers.Peer acmeAsPeer(Peers me) {
    try {
      return me.byName("acme").orElseThrow();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Reads the kept catalog the way another process would. */
  private record RemoteCatalogsReader(Path home) {
    boolean has(String key) {
      try {
        return new RemoteCatalogs(home)
            .cached("acme")
            .map(r -> RemoteFeed.keys(r.catalog()).contains(key))
            .orElse(false);
      } catch (IOException e) {
        return false;
      }
    }
  }

  private static void await(java.util.function.BooleanSupplier condition) throws Exception {
    long end = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(25);
    }
  }
}
