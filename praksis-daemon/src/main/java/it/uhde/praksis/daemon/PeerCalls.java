// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.engine.ModuleCalls;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Runs the capability calls of peers. The call runs here, under the grants for the peer ({@link
 * PeerAccess}, which also writes the audit line); the peer has already checked its own policy. A
 * capability that the peer may not call and one that does not exist get the same answer, so a peer
 * cannot find out what exists beyond its grants. The capabilities of the agent ({@code praksis.*})
 * and those that this daemon has from another daemon ({@code acme/entity.read}) are never reachable
 * by a peer.
 */
final class PeerCalls {
  private final Function<String, Optional<CapabilityDescriptor>> descriptors;
  private final Map<String, Capability> capabilities;
  private final PeerAccess access;

  PeerCalls(
      Function<String, Optional<CapabilityDescriptor>> descriptors,
      Map<String, Capability> capabilities,
      PeerAccess access) {
    this.descriptors = descriptors;
    this.capabilities = capabilities;
    this.access = access;
  }

  /** Answers one call. */
  Response call(Peers.Peer peer, Request.PeerCall request) {
    String name = request.capability() == null ? "" : request.capability();
    // the agent's capabilities and those of other daemons (acme/...) are never reachable by a peer:
    // a daemon is not a relay
    Optional<CapabilityDescriptor> found =
        name.startsWith("praksis.") || name.contains("/")
            ? Optional.empty()
            : descriptors.apply(name);
    Capability implementation = capabilities.get(name);
    String refusal = "peer '" + peer.name() + "' has no grant for " + name;
    if (found.isEmpty() || implementation == null) {
      return new Response.Error(refusal);
    }
    CapabilityDescriptor descriptor = found.get();
    Map<String, Object> args;
    try {
      args = coerce(descriptor, request.args());
    } catch (IllegalArgumentException e) {
      // checked after the grant would be, so that the shape of a capability is not shown to a peer
      // that may not call it
      if (access.check(peer, descriptor, request.args()).allowed()) {
        return new Response.Error(e.getMessage());
      }
      return new Response.Error(refusal);
    }
    AccessPolicy.Decision decision = access.check(peer, descriptor, args);
    if (!decision.allowed()) {
      return new Response.Error(refusal);
    }
    if (request.dryRun() && !descriptor.supportsDryRun()) {
      return new Response.Error(name + " cannot be asked to change nothing");
    }
    try {
      Map<String, Object> results =
          ModuleCalls.invoke(
              implementation, new Context("peer:" + peer.name(), request.dryRun()), args);
      return new Response.PeerCallResult(wire(results));
    } catch (Exception e) {
      return new Response.Error(String.valueOf(e.getMessage()));
    }
  }

  private static Map<String, Object> coerce(CapabilityDescriptor c, Map<String, Object> given) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : given.entrySet()) {
      var field =
          c.params().stream()
              .filter(p -> p.name().equals(e.getKey()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "'" + c.name() + "' has no argument '" + e.getKey() + "'"));
      out.put(e.getKey(), Injection.coerce(field, e.getValue()));
    }
    for (var p : c.params()) {
      if (p.required() && !out.containsKey(p.name())) {
        throw new IllegalArgumentException(
            "'" + c.name() + "' needs the argument '" + p.name() + "'");
      }
    }
    return out;
  }

  /** Results as JSON can carry them: timestamps as ISO-8601 text, bytes as Base64. */
  @SuppressWarnings("unchecked")
  static Map<String, Object> wire(Map<String, Object> results) {
    return (Map<String, Object>) wireValue(results);
  }

  private static Object wireValue(Object v) {
    if (v instanceof Instant i) {
      return i.toString();
    }
    if (v instanceof byte[] b) {
      return Base64.getEncoder().encodeToString(b);
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach((k, x) -> out.put(String.valueOf(k), wireValue(x)));
      return out;
    }
    if (v instanceof List<?> l) {
      return l.stream().map(PeerCalls::wireValue).toList();
    }
    if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean) {
      return v;
    }
    return String.valueOf(v);
  }

  private record Context(String key, boolean dryRun) implements CapabilityContext {
    @Override
    public String idempotencyKey() {
      return key;
    }

    @Override
    public Optional<Event> event() {
      return Optional.empty();
    }
  }
}
