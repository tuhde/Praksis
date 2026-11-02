// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.Policy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemotesTest {
  @TempDir Path dir;

  private LoadedModules base(DaemonConfig config) throws Exception {
    return ModuleLoader.load(
        config.moduleRoots(), config.lockfile(), config.moduleSettings(), config.enabledModules());
  }

  private void keep(String peerName, Peers own, Response.PeerCatalog catalog) throws Exception {
    Path otherHome = Files.createTempDirectory(dir, "o");
    own.add(peerName, new Peers(otherHome).publicKey(), Optional.of("/nowhere"), false);
    new RemoteCatalogs(dir).store(RemoteCatalogs.clean(peerName, Instant.now(), false, catalog));
  }

  private static Response.PeerCatalog catalogWith(String... names) {
    return new Response.PeerCatalog(
        PeerVersions.CATALOG_VERSION,
        java.util.Arrays.stream(names)
            .map(
                n ->
                    new Response.PeerCatalog.CapabilityInfo(
                        n,
                        "READ_ONLY",
                        false,
                        List.of(new Response.PeerCatalog.Field("type", "string", true, true)),
                        List.of()))
            .toList(),
        List.of(),
        List.of());
  }

  @Test
  void theCapabilitiesOfAPeerAppearUnderItsNameAndGrantsCanNameThem() throws Exception {
    keep("acme", new Peers(dir), catalogWith("entity.read"));
    new RemotePins(dir)
        .pin(
            "acme",
            "finance",
            new Response.PeerCatalog(
                1,
                List.of(),
                List.of(),
                List.of(
                    new Response.EntitySetList.EntitySetInfo(
                        "finance",
                        "f",
                        null,
                        null,
                        "h",
                        List.of("invoice"),
                        List.of(),
                        List.of("invoice")))),
            Instant.now());
    Files.writeString(
        dir.resolve("policy.toml"),
        "format_version = 1\n[[grant]]\nid = \"g\"\nlayer = \"l\"\ncapability = \"acme/entity.read\"\n"
            + "[grant.args]\ntype = { set = \"acme/finance\" }\n");
    DaemonConfig config = DaemonConfig.load(dir);
    try (LoadedModules modules = Remotes.add(config, base(config))) {
      var catalog = modules.catalog();
      assertTrue(catalog.capability("acme/entity.read").isPresent());
      assertTrue(modules.capabilities().containsKey("acme/entity.read"));
      Policy policy = EntitySets.policy(config, java.util.Map.of());
      assertTrue(
          policy
              .check(catalog, java.util.Set.of("l"), java.util.Set.of(), java.util.Set.of())
              .isEmpty());
      assertTrue(
          policy.find("l", "acme/entity.read", java.util.Map.of("type", "invoice")).isPresent());
      assertTrue(
          policy.find("l", "acme/entity.read", java.util.Map.of("type", "salary")).isEmpty());
    }
  }

  @Test
  void aFunctionThatTheRemoteStoppedOfferingIsUnavailableToTheVerifier() throws Exception {
    keep("acme", new Peers(dir), catalogWith("entity.read", "entity.delete"));
    DaemonConfig config = DaemonConfig.load(dir);
    RemoteFeed feed = new RemoteFeed(dir, "acme", java.time.Clock.systemUTC(), t -> {});
    feed.apply(catalogWith("entity.read", "entity.delete"));
    feed.apply(catalogWith("entity.read"));
    try (LoadedModules modules = Remotes.add(config, base(config))) {
      var catalog = modules.catalog();
      assertTrue(catalog.unavailable("acme/entity.read").isEmpty());
      assertTrue(
          catalog.unavailable("acme/entity.delete").orElseThrow().contains("no longer offers"));
      assertFalse(catalog.unavailable("nothing.here").isPresent());
    }
    assertEquals(1, feed.gone().size());
  }
}
