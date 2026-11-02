// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Remote entity sets that a local grant may name. A grant can name a local set or a remote set that
 * is <em>pinned</em>, like an upstream layer is pinned to a commit: the pin is a snapshot of the
 * members that a person looked at and approved, and a grant on the set covers exactly those. If the
 * company adds a type to its set, the pin does not move and the grant does not widen; the change is
 * a pending difference that a person approves (or not). Without this, whoever runs the remote could
 * widen a local grant by editing their own set.
 *
 * <p>A pinned set is named {@code <peer>/<set>} in a grant, e.g. {@code type = { set =
 * "acme/finance" }} on {@code acme/entity.read}. The pins are in {@code <home>/remote/pins.json}.
 * Nothing about them is ever sent to a remote: the remote does not learn which sets exist here.
 */
final class RemotePins {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * One pin.
   *
   * @param peer the remote
   * @param set the set at the remote
   * @param members the approved members, as the remote names its types
   * @param approved when a person approved this snapshot
   */
  record Pin(String peer, String set, List<String> members, Instant approved) {
    /** The name a grant uses. */
    String name() {
      return peer + "/" + set;
    }
  }

  /**
   * How the remote's set now differs from the pin.
   *
   * @param pin the pin
   * @param added members the remote has added, which the pin does not cover
   * @param removed members the remote has taken out
   * @param gone true if the remote no longer offers the set at all
   */
  record Pending(Pin pin, List<String> added, List<String> removed, boolean gone) {
    boolean changed() {
      return gone || !added.isEmpty() || !removed.isEmpty();
    }
  }

  private final Path file;

  RemotePins(Path home) {
    this.file = home.resolve("remote").resolve("pins.json");
  }

  /** All pins, by name. */
  synchronized Map<String, Pin> pins() throws IOException {
    Map<String, Pin> out = new TreeMap<>();
    if (!Files.exists(file)) {
      return out;
    }
    var root = JSON.readTree(file.toFile());
    for (var e : root.properties()) {
      var n = e.getValue();
      List<String> members = new ArrayList<>();
      n.path("members").forEach(m -> members.add(m.asText()));
      String[] parts = e.getKey().split("/", 2);
      if (parts.length != 2) {
        throw new IOException(file + ": '" + e.getKey() + "' is not <peer>/<set>");
      }
      out.put(
          e.getKey(),
          new Pin(parts[0], parts[1], members, Instant.parse(n.path("approved").asText())));
    }
    return out;
  }

  /** The members of every pinned set, by name, for resolving grants. */
  Map<String, Set<String>> sets() {
    try {
      Map<String, Set<String>> out = new TreeMap<>();
      pins().forEach((name, p) -> out.put(name, new TreeSet<>(p.members())));
      return out;
    } catch (IOException | RuntimeException e) {
      // a damaged file pins nothing, so no grant on a remote set covers anything
      return Map.of();
    }
  }

  private static List<String> membersOf(Response.PeerCatalog catalog, String set) {
    return catalog.sets().stream()
        .filter(s -> s.id().equals(set))
        .findFirst()
        .map(s -> s.members().stream().sorted().toList())
        .orElse(null);
  }

  /**
   * Pins the set as the catalog shows it now. A person does this; it is the approval.
   *
   * @throws IOException if the set is not offered, or is pinned already (use {@link #accept})
   */
  synchronized Pin pin(String peer, String set, Response.PeerCatalog catalog, Instant now)
      throws IOException {
    Map<String, Pin> all = pins();
    Pin pin = new Pin(peer, set, membersOf(catalog, set), now);
    if (pin.members() == null) {
      throw new IOException(peer + " does not offer a set '" + set + "'");
    }
    if (all.containsKey(pin.name())) {
      throw new IOException(pin.name() + " is pinned already; accept the change to move the pin");
    }
    all.put(pin.name(), pin);
    write(all);
    return pin;
  }

  /**
   * Moves a pin to what the remote offers now: the approval of the change that {@link #pending}
   * shows.
   *
   * @throws IOException if the set is not pinned, or the remote no longer offers it (unpin it)
   */
  synchronized Pin accept(String peer, String set, Response.PeerCatalog catalog, Instant now)
      throws IOException {
    Map<String, Pin> all = pins();
    String name = peer + "/" + set;
    if (!all.containsKey(name)) {
      throw new IOException(name + " is not pinned");
    }
    List<String> members = membersOf(catalog, set);
    if (members == null) {
      throw new IOException(peer + " no longer offers '" + set + "'; unpin it to remove the pin");
    }
    Pin pin = new Pin(peer, set, members, now);
    all.put(name, pin);
    write(all);
    return pin;
  }

  /** Removes a pin; grants that name it then fail to load. */
  synchronized boolean unpin(String peer, String set) throws IOException {
    Map<String, Pin> all = pins();
    boolean removed = all.remove(peer + "/" + set) != null;
    if (removed) {
      write(all);
    }
    return removed;
  }

  /** How the sets of this remote now differ from their pins; only pins of this peer. */
  List<Pending> pending(String peer, Response.PeerCatalog catalog) throws IOException {
    List<Pending> out = new ArrayList<>();
    for (Pin pin : pins().values()) {
      if (!pin.peer().equals(peer)) {
        continue;
      }
      List<String> now = membersOf(catalog, pin.set());
      if (now == null) {
        out.add(new Pending(pin, List.of(), List.of(), true));
        continue;
      }
      List<String> added = new ArrayList<>(now);
      added.removeAll(pin.members());
      List<String> removed = new ArrayList<>(pin.members());
      removed.removeAll(now);
      out.add(new Pending(pin, added, removed, false));
    }
    return out;
  }

  private void write(Map<String, Pin> all) throws IOException {
    Map<String, Object> root = new LinkedHashMap<>();
    all.forEach(
        (name, p) -> {
          Map<String, Object> n = new LinkedHashMap<>();
          n.put("approved", p.approved().toString());
          n.put("members", p.members());
          root.put(name, n);
        });
    Files.createDirectories(file.getParent());
    Path tmp = Files.createTempFile(file.getParent(), "pins", ".tmp");
    JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
  }
}
