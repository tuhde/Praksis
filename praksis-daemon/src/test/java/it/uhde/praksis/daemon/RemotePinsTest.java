// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.Policy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemotePinsTest {
  @TempDir Path dir;
  static final Instant T = Instant.parse("2026-11-02T10:00:00Z");

  private static Response.PeerCatalog catalog(String... members) {
    List<String> m = List.of(members);
    return new Response.PeerCatalog(
        PeerVersions.CATALOG_VERSION,
        List.of(),
        List.of(),
        List.of(
            new Response.EntitySetList.EntitySetInfo(
                "finance", "f", null, null, "h", m, List.of(), m)));
  }

  @Test
  void aPinIsASnapshotThatTheRemoteCannotMove() throws IOException {
    RemotePins pins = new RemotePins(dir);
    pins.pin("acme", "finance", catalog("invoice", "offer"), T);
    assertEquals(Set.of("invoice", "offer"), pins.sets().get("acme/finance"));

    // the company adds a type: the pin stays, the change is pending
    var pending = pins.pending("acme", catalog("invoice", "offer", "salary")).get(0);
    assertEquals(List.of("salary"), pending.added());
    assertTrue(pending.removed().isEmpty());
    assertEquals(Set.of("invoice", "offer"), pins.sets().get("acme/finance"));

    // a person approves
    pins.accept("acme", "finance", catalog("invoice", "offer", "salary"), T.plusSeconds(60));
    assertEquals(Set.of("invoice", "offer", "salary"), pins.sets().get("acme/finance"));
    assertFalse(pins.pending("acme", catalog("invoice", "offer", "salary")).get(0).changed());

    // survives a restart
    assertEquals(3, new RemotePins(dir).sets().get("acme/finance").size());
  }

  @Test
  void refusals() throws IOException {
    RemotePins pins = new RemotePins(dir);
    assertThrows(IOException.class, () -> pins.pin("acme", "nothing", catalog("a"), T));
    pins.pin("acme", "finance", catalog("a"), T);
    assertThrows(IOException.class, () -> pins.pin("acme", "finance", catalog("a"), T));
    assertThrows(IOException.class, () -> pins.accept("acme", "other", catalog("a"), T));
    var gone = pins.pending("acme", new Response.PeerCatalog(1, List.of(), List.of(), List.of()));
    assertTrue(gone.get(0).gone());
    assertThrows(
        IOException.class,
        () ->
            pins.accept(
                "acme",
                "finance",
                new Response.PeerCatalog(1, List.of(), List.of(), List.of()),
                T));
    assertTrue(pins.unpin("acme", "finance"));
    assertTrue(pins.sets().isEmpty());
  }

  @Test
  void aGrantOnARemoteSetCoversThePinnedMembersOnly() throws Exception {
    new RemotePins(dir).pin("acme", "finance", catalog("invoice", "offer"), T);
    Files.writeString(
        dir.resolve("policy.toml"),
        "format_version = 1\n[[grant]]\nid = \"g\"\nlayer = \"l\"\ncapability = \"acme/entity.read\"\n"
            + "[grant.args]\ntype = { set = \"acme/finance\" }\n");
    DaemonConfig config = DaemonConfig.load(dir);
    Policy policy = EntitySets.policy(config, java.util.Map.of());
    assertTrue(EntitySets.pinProblems(config, policy).isEmpty());
    assertTrue(
        policy.find("l", "acme/entity.read", java.util.Map.of("type", "invoice")).isPresent());
    assertTrue(policy.find("l", "acme/entity.read", java.util.Map.of("type", "salary")).isEmpty());

    // without the pin the grant covers nothing, and the daemon refuses to start with it
    new RemotePins(dir).unpin("acme", "finance");
    Policy unpinned = EntitySets.policy(config, java.util.Map.of());
    assertEquals(1, EntitySets.pinProblems(config, unpinned).size());
    assertTrue(
        unpinned.find("l", "acme/entity.read", java.util.Map.of("type", "invoice")).isEmpty());
  }
}
