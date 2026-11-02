// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.store.AuditEntry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerAccessTest {
  @TempDir Path dir;

  private Policy policy(String toml) throws Exception {
    Path f = dir.resolve("policy.toml");
    Files.writeString(f, "format_version = 1\n" + toml);
    return Policy.load(f);
  }

  private static CapabilityDescriptor cap(String name) {
    return new CapabilityDescriptor(
        name, it.uhde.praksis.capability.EffectClass.READ_ONLY, false, List.of(), List.of());
  }

  private static final Peers.Peer ME = new Peers.Peer("me", "k", Optional.empty(), false);
  private static final Peers.Peer OTHER = new Peers.Peer("other", "k2", Optional.empty(), false);

  @Test
  void aPeerNeedsAGrantAndIsAudited() throws Exception {
    Policy p =
        policy(
            "[[peer_grant]]\nid = \"g1\"\npeer = \"me\"\ncapability = \"entity.read\"\n"
                + "[peer_grant.args]\ntype = { one_of = [\"invoice\"] }\n");
    List<AuditEntry> log = new ArrayList<>();
    PeerAccess access = new PeerAccess(() -> p, log::add);
    assertTrue(access.check(ME, cap("entity.read"), Map.of("type", "invoice")).allowed());
    assertFalse(access.check(ME, cap("entity.read"), Map.of("type", "salary")).allowed());
    assertFalse(access.check(ME, cap("entity.delete"), Map.of("type", "invoice")).allowed());
    assertFalse(access.check(OTHER, cap("entity.read"), Map.of("type", "invoice")).allowed());
    assertEquals(4, log.size());
    assertEquals(Optional.of("peer:me"), log.get(0).ruleId());
    assertTrue(log.get(0).allowed());
    assertFalse(log.get(1).allowed());
    assertEquals(Optional.of("peer:other"), log.get(3).ruleId());
  }

  @Test
  void aChangedPolicyAppliesAtOnce() throws Exception {
    Policy[] current = {Policy.empty()};
    PeerAccess access = new PeerAccess(() -> current[0], e -> {});
    assertFalse(access.check(ME, cap("entity.read"), Map.of()).allowed());
    current[0] =
        policy("[[peer_grant]]\nid = \"g\"\npeer = \"me\"\ncapability = \"entity.read\"\n");
    assertTrue(access.check(ME, cap("entity.read"), Map.of()).allowed());
  }

  @Test
  void grantsForUnknownPeersAreAProblem() throws Exception {
    Policy p =
        policy("[[peer_grant]]\nid = \"g\"\npeer = \"ghost\"\ncapability = \"entity.read\"\n");
    var config = DaemonConfig.load(dir);
    assertEquals(1, Daemon.peerGrantProblems(config, p).size());
    new Peers(dir)
        .add(
            "ghost",
            new Peers(Files.createTempDirectory(dir, "o")).publicKey(),
            Optional.empty(),
            false);
    assertTrue(Daemon.peerGrantProblems(config, p).isEmpty());
  }

  @Test
  void aGrantMayNotUseTheSameIdAsALayerGrant() throws IOException {
    Path f = dir.resolve("p2.toml");
    Files.writeString(
        f,
        "format_version = 1\n[[grant]]\nid = \"a\"\nlayer = \"l\"\ncapability = \"c\"\n"
            + "[[peer_grant]]\nid = \"a\"\npeer = \"p\"\ncapability = \"c\"\n");
    org.junit.jupiter.api.Assertions.assertThrows(
        it.uhde.praksis.ruleset.RulesetException.class, () -> Policy.load(f));
  }

  @Test
  void aPeerGrantMayNotNameACapabilityOfAnotherDaemon() throws Exception {
    Policy p =
        policy("[[peer_grant]]\nid = \"g\"\npeer = \"me\"\ncapability = \"acme/entity.read\"\n");
    var problems =
        p.check(
            new it.uhde.praksis.ruleset.ModuleCatalog(List.of()),
            java.util.Set.of(),
            java.util.Set.of(),
            java.util.Set.of());
    assertEquals(1, problems.size());
    assertTrue(problems.get(0).message().contains("not a relay"), problems.get(0).message());
  }
}
