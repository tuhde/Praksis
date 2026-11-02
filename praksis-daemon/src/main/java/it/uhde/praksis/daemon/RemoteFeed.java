// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Consumer;

/**
 * Follows the catalog of one connected daemon. The remote's change feed ({@link
 * Request.PeerChanges}) says when what it offers is another thing; then the catalog is fetched,
 * kept ({@link RemoteCatalogs}) and compared with the one before.
 *
 * <p>Two things are tracked and they are different:
 *
 * <ul>
 *   <li>an <b>item that disappeared</b> (a capability, type or set, or a type that left a set) is
 *       marked unavailable with the time it was last seen, and stays in the list until it comes
 *       back. A set keeps its member in the list, marked, so that a rule that depends on it fails
 *       in the verifier or its test instead of silently not finding it. This is a warning, not an
 *       error. The marks are kept in {@code <home>/remote/<peer>.gone}, so that a restart does not
 *       forget them;
 *   <li>a <b>remote that cannot be reached</b>: nothing is marked gone, because nothing is known;
 *       the catalog is shown as stale, from {@link #unreachableSince()}.
 * </ul>
 */
final class RemoteFeed {
  /** What changed between two catalogs. */
  record Diff(Set<String> added, Set<String> removed) {
    boolean isEmpty() {
      return added.isEmpty() && removed.isEmpty();
    }
  }

  private final Path home;
  private final String peer;
  private final Clock clock;
  private final Consumer<String> changed;
  private final Map<String, Instant> gone = new TreeMap<>();
  private Set<String> known;
  private Instant unreachableSince;

  /**
   * @param changed told {@code remote} whenever the state of this feed changes (for the topic that
   *     lets a client refresh its screens)
   */
  RemoteFeed(Path home, String peer, Clock clock, Consumer<String> changed) throws IOException {
    this.home = home;
    this.peer = peer;
    this.clock = clock;
    this.changed = changed;
    readGone();
    this.known = new RemoteCatalogs(home).cached(peer).map(r -> keys(r.catalog())).orElse(null);
  }

  /**
   * Every item of a catalog by kind and name: {@code capability:x}, {@code type:x}, {@code set:x},
   * {@code member:set/x}.
   */
  static Set<String> keys(Response.PeerCatalog c) {
    Set<String> out = new LinkedHashSet<>();
    c.capabilities().forEach(x -> out.add("capability:" + x.name()));
    c.types().forEach(x -> out.add("type:" + x.id()));
    c.sets()
        .forEach(
            s -> {
              out.add("set:" + s.id());
              s.members().forEach(m -> out.add("member:" + s.id() + "/" + m));
            });
    return out;
  }

  /** What a new catalog changes; also marks and unmarks. */
  synchronized Diff apply(Response.PeerCatalog catalog) throws IOException {
    Set<String> now = keys(catalog);
    Set<String> added = new LinkedHashSet<>(now);
    Set<String> removed = new LinkedHashSet<>();
    if (known != null) {
      added.removeAll(known);
      removed.addAll(known);
      removed.removeAll(now);
    }
    boolean changedMarks = false;
    Instant time = clock.instant();
    for (String r : removed) {
      if (gone.putIfAbsent(r, time) == null) {
        changedMarks = true;
      }
    }
    for (String a : added) {
      if (gone.remove(a) != null) {
        changedMarks = true;
      }
    }
    known = now;
    if (unreachableSince != null) {
      unreachableSince = null;
      changedMarks = true;
    }
    if (changedMarks) {
      writeGone();
    }
    Diff diff = new Diff(added, removed);
    if (changedMarks || !diff.isEmpty()) {
      changed.accept("remote");
    }
    return diff;
  }

  /** The remote could not be reached. Nothing is marked gone; the catalog is stale. */
  synchronized void unreachable() {
    if (unreachableSince == null) {
      unreachableSince = clock.instant();
      changed.accept("remote");
    }
  }

  synchronized Optional<Instant> unreachableSince() {
    return Optional.ofNullable(unreachableSince);
  }

  /** The items that disappeared, by key, with the time they were noticed missing. */
  synchronized Map<String, Instant> gone() {
    return new LinkedHashMap<>(gone);
  }

  /** Why an item cannot be used now, or empty if it can. */
  synchronized Optional<String> unavailable(String key) {
    Instant g = gone.get(key);
    if (g != null) {
      return Optional.of(peer + " no longer offers it (since " + g + ")");
    }
    if (unreachableSince != null) {
      return Optional.of(peer + " cannot be reached (since " + unreachableSince + ")");
    }
    return Optional.empty();
  }

  // ---------------------------------------------------------------------------- the loop

  /**
   * Follows the feed until the thread is interrupted: fetches the catalog, then waits for changes
   * and fetches again when the hash differs. When the connection fails it is marked unreachable and
   * tried again after a pause that grows to a minute.
   */
  void run(Peers own, Peers.Peer remote) {
    long pause = 1_000;
    RemoteCatalogs catalogs = new RemoteCatalogs(home);
    while (!Thread.currentThread().isInterrupted()) {
      try (PeerLink link = PeerLink.connect(own, remote)) {
        String hash = null;
        long since = 0;
        pause = 1_000;
        while (!Thread.currentThread().isInterrupted()) {
          Response r = link.send(new Request.PeerChanges(since, since == 0 ? 0 : 25_000));
          if (!(r instanceof Response.PeerChangeList list)) {
            throw new IOException("peer '" + peer + "' did not answer the feed");
          }
          since = list.counter();
          if (!list.catalogHash().equals(hash)) {
            var fetched = catalogs.fetch(link);
            apply(fetched.catalog());
            hash = list.catalogHash();
          }
        }
      } catch (IOException e) {
        unreachable();
      }
      try {
        Thread.sleep(pause);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
      pause = Math.min(pause * 2, 60_000);
    }
  }

  // ---------------------------------------------------------------------------- persistence

  private Path goneFile() {
    return home.resolve("remote").resolve(peer + ".gone");
  }

  private void readGone() throws IOException {
    Path f = goneFile();
    if (!Files.exists(f)) {
      return;
    }
    for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
      int tab = line.indexOf('\t');
      if (tab > 0) {
        try {
          gone.put(line.substring(0, tab), Instant.parse(line.substring(tab + 1)));
        } catch (java.time.format.DateTimeParseException ignored) {
          // a damaged line is dropped; the item is noticed missing again at the next change
        }
      }
    }
  }

  private void writeGone() throws IOException {
    Path f = goneFile();
    Files.createDirectories(f.getParent());
    StringBuilder sb = new StringBuilder();
    gone.forEach((k, t) -> sb.append(k).append('\t').append(t).append('\n'));
    Path tmp = Files.createTempFile(f.getParent(), "gone", ".tmp");
    Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
  }
}
