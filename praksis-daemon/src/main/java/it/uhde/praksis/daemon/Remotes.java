// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * Puts the capabilities of connected daemons next to those of the installed modules, under the name
 * of the daemon ({@code acme/entity.read}), from the catalog that was kept of each. Rules, grants,
 * the verifier and the tests then treat them like any capability, with two differences: the call is
 * forwarded ({@link RemoteCapability}), and a capability can be unavailable (the remote no longer
 * offers it, cannot be reached, or the function is not accepted yet), which the verifier reports as
 * an error and which makes the engine skip the event for that rule and record it as missed.
 *
 * <p>The set of remote capabilities that rules can name is the one of the kept catalogs when the
 * daemon starts; a function that a remote offers later is visible in {@code peers catalog} and
 * becomes usable in rules at the next start.
 */
final class Remotes {
  private Remotes() {}

  /** For checks outside a running daemon: no calls, and unavailable only what the files say. */
  static LoadedModules add(DaemonConfig config, LoadedModules modules) {
    Path home = config.home();
    return add(config, modules, name -> fromFiles(home, name), null);
  }

  /**
   * @param availability why a qualified name cannot be used now, or empty
   * @param calls how to forward calls; null if nothing is to be called
   */
  static LoadedModules add(
      DaemonConfig config,
      LoadedModules modules,
      Function<String, Optional<String>> availability,
      RemoteCalls calls) {
    LoadedModules out = modules;
    try {
      Peers peers = new Peers(config.home());
      RemoteCatalogs catalogs = new RemoteCatalogs(config.home());
      for (Peers.Peer peer : peers.list()) {
        Optional<RemoteCatalogs.Remote> remote = catalogs.cached(peer.name());
        if (remote.isEmpty() || remote.get().catalog().capabilities().isEmpty()) {
          continue;
        }
        List<CapabilityDescriptor> descriptors = new java.util.ArrayList<>();
        Map<String, Capability> implementations = new LinkedHashMap<>();
        for (var info : remote.get().catalog().capabilities()) {
          String qualified = peer.name() + "/" + info.name();
          descriptors.add(RemoteCalls.descriptor(qualified, info));
          implementations.put(qualified, new RemoteCapability(peer.name(), info.name(), calls));
        }
        out =
            out.with(
                new ModuleDescriptor(
                    peer.name(),
                    "remote",
                    List.of(),
                    descriptors,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of()),
                implementations);
      }
    } catch (IOException | IllegalArgumentException e) {
      System.err.println(
          "praksis: the capabilities of other daemons are not used: " + e.getMessage());
    }
    return out.withAvailability(availability);
  }

  /** What the files alone say: a function that the remote stopped offering. */
  static Optional<String> fromFiles(Path home, String qualified) {
    int slash = qualified.indexOf('/');
    if (slash < 1) {
      return Optional.empty();
    }
    String peer = qualified.substring(0, slash);
    String key = "capability:" + qualified.substring(slash + 1);
    try {
      return new RemoteFeed(home, peer, java.time.Clock.systemUTC(), t -> {}).unavailable(key);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /** The catalog entries as cleaned text, for messages. */
  static String describe(Response.PeerCatalog.CapabilityInfo info) {
    return info.name() + " (" + info.effect() + ")";
  }
}
