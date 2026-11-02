// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Policy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteCatalogsTest {
  @TempDir Path dir;

  private static Response.EntityTypeList.EntityTypeInfo type(String id, String owner) {
    return new Response.EntityTypeList.EntityTypeInfo(
        id,
        id,
        "d",
        owner,
        "h",
        List.of(
            new Response.EntityTypeList.EntityField(
                "name", "string", true, false, false, null, null, null)),
        List.of("s"));
  }

  private static Response.EntitySetList.EntitySetInfo set(String id, List<String> members) {
    return new Response.EntitySetList.EntitySetInfo(
        id, id, null, "private-layer", "h", members, List.of(), members);
  }

  @Test
  void aPeerSeesOnlyWhatItsGrantsCover() throws Exception {
    Path f = dir.resolve("policy.toml");
    Files.writeString(
        f,
        "format_version = 1\n[[peer_grant]]\nid = \"a\"\npeer = \"me\"\ncapability = \"entity.read\"\n"
            + "[peer_grant.args]\ntype = { one_of = [\"invoice\"] }\n");
    Policy policy = Policy.load(f);
    var types =
        new Response.EntityTypeList(List.of(type("invoice", "mine"), type("salary", "mine")));
    var sets =
        new Response.EntitySetList(
            List.of(set("finance", List.of("invoice", "salary")), set("hr", List.of("salary"))));
    var peer = new Peers.Peer("me", "k", Optional.empty(), false);
    var catalog = PeerCatalogs.build(peer, policy, new ModuleCatalog(List.of()), types, sets);
    assertEquals(List.of("invoice"), catalog.types().stream().map(t -> t.id()).toList());
    assertEquals(null, catalog.types().get(0).owner());
    assertEquals(List.of("finance"), catalog.types().get(0).sets());
    assertEquals(List.of("finance"), catalog.sets().stream().map(s -> s.id()).toList());
    assertEquals(List.of("invoice"), catalog.sets().get(0).members());
    assertEquals(null, catalog.sets().get(0).owner());
    var none =
        PeerCatalogs.build(
            new Peers.Peer("other", "k2", Optional.empty(), false),
            policy,
            new ModuleCatalog(List.of()),
            types,
            sets);
    assertTrue(none.types().isEmpty() && none.sets().isEmpty() && none.capabilities().isEmpty());
  }

  @Test
  void textFromARemoteIsCleaned() {
    var evil =
        new Response.PeerCatalog(
            1,
            List.of(),
            List.of(
                new Response.EntityTypeList.EntityTypeInfo(
                    "invoice",
                    "In\u001b[31mvoice\u0007",
                    "x".repeat(5000),
                    "layer",
                    "h",
                    List.of(),
                    List.of("../etc", "ok")),
                new Response.EntityTypeList.EntityTypeInfo(
                    "Bad Name", "t", null, null, "h", List.of(), List.of())),
            List.of());
    var r = RemoteCatalogs.clean("acme", Instant.now(), false, evil);
    assertEquals(1, r.dropped());
    var t = r.catalog().types().get(0);
    assertEquals("In[31mvoice", t.title());
    assertEquals(RemoteCatalogs.MAX_TEXT + 1, t.description().length());
    assertEquals(List.of("ok"), t.sets());
    assertEquals(null, t.owner());
    assertEquals("acme/invoice", r.qualified("invoice"));
  }

  @Test
  void theCatalogIsKeptAndComesBackStale() throws IOException {
    RemoteCatalogs catalogs = new RemoteCatalogs(dir);
    assertTrue(catalogs.cached("acme").isEmpty());
    var c =
        new Response.PeerCatalog(
            PeerVersions.CATALOG_VERSION, List.of(), List.of(type("invoice", null)), List.of());
    var fresh = RemoteCatalogs.clean("acme", Instant.parse("2026-11-02T10:00:00Z"), false, c);
    catalogs.store(fresh);
    var back = catalogs.cached("acme").orElseThrow();
    assertTrue(back.stale());
    assertFalse(fresh.stale());
    assertEquals(Instant.parse("2026-11-02T10:00:00Z"), back.fetched());
    assertEquals("invoice", back.catalog().types().get(0).id());
  }

  @Test
  void fetchOverARealConnection(@TempDir Path other) throws IOException {
    Path acmeHome = Files.createDirectory(dir.resolve("a"));
    Path meHome = Files.createDirectory(dir.resolve("m"));
    Peers acme = new Peers(acmeHome);
    Peers me = new Peers(meHome);
    acme.add("me", me.publicKey(), Optional.empty(), false);
    Path socket = dir.resolve("s");
    me.add("acme", acme.publicKey(), Optional.of(socket.toString()), false);
    var offered =
        new Response.PeerCatalog(
            PeerVersions.CATALOG_VERSION, List.of(), List.of(type("invoice", null)), List.of());
    try (ApiServer server =
        new ApiServer(
            socket,
            r -> new Response.Error("no"),
            () -> {},
            new PeerHandshake(acme),
            (peer, request) ->
                request instanceof it.uhde.praksis.protocol.Request.PeerCatalog
                    ? offered
                    : new Response.Error("a peer cannot do this"))) {
      server.start();
      RemoteCatalogs catalogs = new RemoteCatalogs(meHome);
      try (PeerLink link = PeerLink.connect(me, me.byName("acme").orElseThrow())) {
        var remote = catalogs.fetch(link);
        assertFalse(remote.stale());
        assertEquals("acme/invoice", remote.qualified(remote.catalog().types().get(0).id()));
      }
      assertTrue(catalogs.cached("acme").orElseThrow().stale());
    }
  }
}
