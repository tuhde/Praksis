// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.PeerGrant;
import it.uhde.praksis.ruleset.Policy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What this daemon offers to one peer. The grants for the peer decide, so the catalog is also the
 * ceiling in the other direction: a peer does not learn that something exists which it may not use.
 * A capability is offered if some grant for the peer names it, an entity type if some {@code
 * entity.*} grant covers it, and an entity set only with the members that are offered (the nesting
 * of sets and the names of local layers stay private).
 */
final class PeerCatalogs {
  private static final List<String> ENTITY_OPERATIONS =
      List.of("entity.create", "entity.read", "entity.query", "entity.update", "entity.delete");

  private PeerCatalogs() {}

  /**
   * Builds the catalog for a peer.
   *
   * @param policy the grants in use, with the entity sets resolved
   * @param modules the installed modules
   * @param types all entity types of this daemon
   * @param sets all entity sets of this daemon
   */
  static Response.PeerCatalog build(
      Peers.Peer peer,
      Policy policy,
      ModuleCatalog modules,
      Response.EntityTypeList types,
      Response.EntitySetList sets) {
    Set<String> granted = new LinkedHashSet<>();
    for (PeerGrant g : policy.peerGrants()) {
      if (g.peer().equals(peer.name())) {
        granted.add(g.capability());
      }
    }
    List<Response.PeerCatalog.CapabilityInfo> capabilities = new ArrayList<>();
    for (CapabilityDescriptor c : modules.capabilities()) {
      // what this daemon has from another daemon is not offered on: it is not a relay
      if (granted.contains(c.name()) && !c.name().contains("/")) {
        capabilities.add(capability(c));
      }
    }
    capabilities.sort(Comparator.comparing(Response.PeerCatalog.CapabilityInfo::name));

    Set<String> visible = new LinkedHashSet<>();
    List<Response.EntityTypeList.EntityTypeInfo> offeredTypes = new ArrayList<>();
    for (var t : types.types()) {
      boolean any =
          ENTITY_OPERATIONS.stream()
              .anyMatch(
                  op -> policy.findForPeer(peer.name(), op, Map.of("type", t.id())).isPresent());
      if (any) {
        visible.add(t.id());
        offeredTypes.add(
            new Response.EntityTypeList.EntityTypeInfo(
                t.id(), t.title(), t.description(), null, t.hash(), t.fields(), List.of()));
      }
    }
    List<Response.EntitySetList.EntitySetInfo> offeredSets = new ArrayList<>();
    for (var s : sets.sets()) {
      List<String> members = s.members().stream().filter(visible::contains).toList();
      if (!members.isEmpty()) {
        offeredSets.add(
            new Response.EntitySetList.EntitySetInfo(
                s.id(), s.title(), s.description(), null, s.hash(), members, List.of(), members));
      }
    }
    // a type is told which offered sets hold it
    List<Response.EntityTypeList.EntityTypeInfo> withSets = new ArrayList<>();
    for (var t : offeredTypes) {
      List<String> in =
          offeredSets.stream().filter(s -> s.members().contains(t.id())).map(s -> s.id()).toList();
      withSets.add(
          new Response.EntityTypeList.EntityTypeInfo(
              t.id(), t.title(), t.description(), null, t.hash(), t.fields(), in));
    }
    return new Response.PeerCatalog(
        PeerVersions.CATALOG_VERSION, List.copyOf(capabilities), withSets, offeredSets);
  }

  /** A hash of a catalog: equal catalogs have equal hashes. */
  static String hash(Response.PeerCatalog catalog) {
    try {
      byte[] h =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(
                  it.uhde.praksis.protocol.Wire.encode(catalog)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(h);
    } catch (java.security.NoSuchAlgorithmException
        | it.uhde.praksis.protocol.ProtocolException e) {
      throw new IllegalStateException(e);
    }
  }

  private static Response.PeerCatalog.CapabilityInfo capability(CapabilityDescriptor c) {
    return new Response.PeerCatalog.CapabilityInfo(
        c.name(),
        c.effect().name(),
        c.supportsDryRun(),
        c.params().stream().map(PeerCatalogs::field).toList(),
        c.results().stream().map(PeerCatalogs::field).toList());
  }

  private static Response.PeerCatalog.Field field(FieldDescriptor f) {
    return new Response.PeerCatalog.Field(f.name(), f.type().id(), f.required(), f.scopable());
  }
}
