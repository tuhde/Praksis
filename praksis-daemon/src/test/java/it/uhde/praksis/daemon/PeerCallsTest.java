// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.store.AuditEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerCallsTest {
  @TempDir Path dir;
  Peers acme;
  Peers me;
  Path meHome;
  ApiServer server;
  final List<AuditEntry> remoteAudit = new ArrayList<>();
  final List<String> localAudit = new ArrayList<>();
  boolean ran;

  static final CapabilityDescriptor ECHO =
      new CapabilityDescriptor(
          "demo.echo",
          EffectClass.READ_ONLY,
          false,
          List.of(
              new FieldDescriptor("text", ValueType.STRING, true, true),
              new FieldDescriptor("at", ValueType.TIMESTAMP, false, false)),
          List.of(new FieldDescriptor("text", ValueType.STRING, true, false)));

  Capability echo =
      new Capability() {
        @Override
        public String name() {
          return "demo.echo";
        }

        @Override
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args) {
          ran = true;
          return Map.of("text", args.get("text"), "when", Instant.parse("2026-11-02T10:00:00Z"));
        }
      };

  @BeforeEach
  void start() throws Exception {
    Path acmeHome = Files.createDirectory(dir.resolve("a"));
    meHome = Files.createDirectory(dir.resolve("m"));
    acme = new Peers(acmeHome);
    me = new Peers(meHome);
    Path socket = dir.resolve("s");
    acme.add("me", me.publicKey(), Optional.empty(), false);
    me.add("acme", acme.publicKey(), Optional.of(socket.toString()), false);
    Path pf = dir.resolve("policy.toml");
    Files.writeString(
        pf,
        "format_version = 1\n[[peer_grant]]\nid = \"g\"\npeer = \"me\"\ncapability = \"demo.echo\"\n"
            + "[peer_grant.args]\ntext = { one_of = [\"hello\", \"hi\"] }\n");
    Policy policy = Policy.load(pf);
    PeerAccess access = new PeerAccess(() -> policy, remoteAudit::add);
    PeerCalls calls =
        new PeerCalls(
            n -> n.equals("demo.echo") ? Optional.of(ECHO) : Optional.empty(),
            Map.of("demo.echo", echo),
            access);
    server =
        new ApiServer(
            socket,
            r -> new Response.Error("no"),
            () -> {},
            new PeerHandshake(acme),
            (peer, req) ->
                req instanceof Request.PeerCall c
                    ? calls.call(peer, c)
                    : new Response.Error("a peer cannot do this"));
    server.start();
    // the catalog we hold of acme
    new RemoteCatalogs(meHome)
        .store(
            RemoteCatalogs.clean(
                "acme",
                Instant.now(),
                false,
                new Response.PeerCatalog(
                    PeerVersions.CATALOG_VERSION,
                    List.of(
                        new Response.PeerCatalog.CapabilityInfo(
                            "demo.echo",
                            "READ_ONLY",
                            false,
                            List.of(
                                new Response.PeerCatalog.Field("text", "string", true, true),
                                new Response.PeerCatalog.Field("at", "timestamp", false, false)),
                            List.of(
                                new Response.PeerCatalog.Field("text", "string", true, false)))),
                    List.of(),
                    List.of())));
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private RemoteCalls remote(AccessPolicy local) {
    return new RemoteCalls(
        me,
        new RemoteCatalogs(meHome),
        local,
        (t, rule, cap, args, allowed, error) ->
            localAudit.add(rule + " " + cap + " " + allowed + error.map(e -> " " + e).orElse("")),
        Clock.systemUTC());
  }

  @Test
  void bothSidesMustAllow() throws IOException {
    RemoteCalls allowAll = remote((r, c, a) -> AccessPolicy.Decision.allow());
    Map<String, Object> out =
        allowAll.call("rule1", "acme", "demo.echo", Map.of("text", "hi"), false);
    assertEquals("hi", out.get("text"));
    assertEquals("2026-11-02T10:00:00Z", out.get("when"));
    assertTrue(ran);
    assertEquals("rule1 acme/demo.echo true", localAudit.get(0));
    assertEquals(Optional.of("peer:me"), remoteAudit.get(0).ruleId());

    // the remote says no although the local policy allows
    ran = false;
    IOException e =
        assertThrows(
            IOException.class,
            () -> allowAll.call("rule1", "acme", "demo.echo", Map.of("text", "secret"), false));
    assertTrue(e.getMessage().contains("has no grant"), e.getMessage());
    assertFalse(ran);

    // the local policy says no although the remote would allow: nothing is sent
    int before = remoteAudit.size();
    RemoteCalls deny =
        remote((r, c, a) -> AccessPolicy.Decision.deny("layer 'x' has no grant for " + c.name()));
    e =
        assertThrows(
            IOException.class,
            () -> deny.call("rule1", "acme", "demo.echo", Map.of("text", "hi"), false));
    assertEquals("layer 'x' has no grant for acme/demo.echo", e.getMessage());
    assertEquals(before, remoteAudit.size());
    assertTrue(localAudit.get(localAudit.size() - 1).startsWith("rule1 acme/demo.echo false"));
  }

  @Test
  void aCapabilityBeyondTheGrantsLooksLikeOneThatDoesNotExist() throws Exception {
    try (PeerLink link = PeerLink.connect(me, me.byName("acme").orElseThrow())) {
      Response missing = link.send(new Request.PeerCall("demo.nothing", Map.of(), false));
      Response agent = link.send(new Request.PeerCall("praksis.plan", Map.of(), false));
      assertEquals(new Response.Error("peer 'me' has no grant for demo.nothing"), missing);
      assertEquals(new Response.Error("peer 'me' has no grant for praksis.plan"), agent);
    }
  }

  @Test
  void aCapabilityNotInTheCatalogIsNotCalled() {
    RemoteCalls allowAll = remote((r, c, a) -> AccessPolicy.Decision.allow());
    IOException e =
        assertThrows(
            IOException.class, () -> allowAll.call("u", "acme", "demo.other", Map.of(), false));
    assertTrue(e.getMessage().contains("not in the catalog"));
  }

  @Test
  void dryRunNeedsSupport() throws Exception {
    try (PeerLink link = PeerLink.connect(me, me.byName("acme").orElseThrow())) {
      Response r = link.send(new Request.PeerCall("demo.echo", Map.of("text", "hi"), true));
      assertEquals(new Response.Error("demo.echo cannot be asked to change nothing"), r);
      assertFalse(ran);
    }
  }

  @Test
  void badArgumentsShowTheShapeOnlyToThoseWhoMayCall() throws Exception {
    try (PeerLink link = PeerLink.connect(me, me.byName("acme").orElseThrow())) {
      // missing 'text' -> no grant covers (the grant restricts text), so only "no grant"
      Response r = link.send(new Request.PeerCall("demo.echo", Map.of(), false));
      assertEquals(new Response.Error("peer 'me' has no grant for demo.echo"), r);
    }
  }
}
