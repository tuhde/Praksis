// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Response;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteOverviewTest {
  @TempDir Path dir;

  private static Response.PeerCatalog catalog(List<String> caps, List<String> members) {
    return new Response.PeerCatalog(
        PeerVersions.CATALOG_VERSION,
        caps.stream()
            .map(
                c ->
                    new Response.PeerCatalog.CapabilityInfo(
                        c, "READ_ONLY", false, List.of(), List.of()))
            .toList(),
        List.of(),
        List.of(
            new Response.EntitySetList.EntitySetInfo(
                "finance", "Finance", null, null, "h", members, List.of(), members)));
  }

  @Test
  void showsWhatAPeerOffersAndWhatIsWrongWithIt() throws Exception {
    Peers own = new Peers(dir);
    Path otherHome = java.nio.file.Files.createTempDirectory(dir, "o");
    own.add("acme", new Peers(otherHome).publicKey(), Optional.of("/nowhere"), false);
    RemoteCatalogs catalogs = new RemoteCatalogs(dir);
    var first = catalog(List.of("entity.read", "entity.delete"), List.of("invoice", "offer"));
    var second = catalog(List.of("entity.read"), List.of("invoice", "salary"));
    new RemotePins(dir).pin("acme", "finance", first, Instant.now());
    RemoteFeed feed = new RemoteFeed(dir, "acme", java.time.Clock.systemUTC(), t -> {});
    feed.apply(first);
    feed.apply(second);
    catalogs.store(RemoteCatalogs.clean("acme", Instant.now(), false, second));
    feed.unreachable();

    var overview =
        RemoteOverview.build(
            DaemonConfig.load(dir), name -> Optional.of(feed), (p, c) -> c.equals("entity.read"));
    var acme = overview.peers().get(0);
    assertEquals("unreachable", acme.state());
    assertEquals(true, acme.stale());
    var byName = new java.util.HashMap<String, Response.PeerOverview.Entry>();
    acme.entries().forEach(e -> byName.put(e.kind() + " " + e.name(), e));
    // while the peer is unreachable everything it offers is marked, and a gone item says so
    assertEquals("unavailable", byName.get("capability acme/entity.read").state());
    assertTrue(byName.get("capability acme/entity.delete").why().contains("no longer offers"));
    assertEquals("unavailable", byName.get("member acme/finance/offer").state());
    // the pin has not moved; the difference is shown
    var pin = byName.get("pin acme/finance");
    assertEquals("changed", pin.state());
    assertTrue(pin.why().contains("salary"));
    assertEquals(Peers.fingerprint(own.publicKey()), overview.fingerprint());
  }

  @Test
  void aFunctionThatIsNotAcceptedSaysSo() throws Exception {
    Peers own = new Peers(dir);
    own.add(
        "acme",
        new Peers(java.nio.file.Files.createTempDirectory(dir, "o")).publicKey(),
        Optional.of("/nowhere"),
        false);
    new RemoteCatalogs(dir)
        .store(
            RemoteCatalogs.clean(
                "acme", Instant.now(), false, catalog(List.of("entity.read"), List.of())));
    var overview =
        RemoteOverview.build(DaemonConfig.load(dir), n -> Optional.empty(), (p, c) -> false);
    var cap =
        overview.peers().get(0).entries().stream()
            .filter(e -> e.kind().equals("capability"))
            .findFirst()
            .orElseThrow();
    assertEquals("not accepted", cap.state());
    assertEquals("connected", overview.peers().get(0).state());
  }
}
