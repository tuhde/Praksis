// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * What the screens show about the other daemons: each peer with the state of the connection and
 * what it offers under its name, each item marked if it cannot be used (and why). It reads the kept
 * catalog, the marks of {@link RemoteFeed}, the pins and the acceptance of functions; it changes
 * nothing.
 */
final class RemoteOverview {
  private RemoteOverview() {}

  /**
   * @param feeds the feed of a peer, if it has one
   * @param accepted whether a function of a peer is accepted
   */
  static Response.PeerOverview build(
      DaemonConfig config,
      Function<String, Optional<RemoteFeed>> feeds,
      BiPredicate<String, String> accepted)
      throws IOException {
    Peers own = new Peers(config.home());
    RemoteCatalogs catalogs = new RemoteCatalogs(config.home());
    RemotePins pins = new RemotePins(config.home());
    List<Response.PeerOverview.PeerInfo> out = new ArrayList<>();
    for (Peers.Peer peer : own.list()) {
      Optional<RemoteCatalogs.Remote> remote = catalogs.cached(peer.name());
      Optional<RemoteFeed> feed = feeds.apply(peer.name());
      RemoteFeed marks =
          feed.isPresent()
              ? feed.get()
              : new RemoteFeed(config.home(), peer.name(), java.time.Clock.systemUTC(), t -> {});
      String state;
      if (peer.socket().isEmpty()) {
        state = "inbound only";
      } else if (marks.unreachableSince().isPresent()) {
        state = "unreachable";
      } else if (remote.isEmpty()) {
        state = "no catalog";
      } else {
        state = "connected";
      }
      List<Response.PeerOverview.Entry> entries = new ArrayList<>();
      if (remote.isPresent()) {
        var c = remote.get().catalog();
        for (var x : c.capabilities()) {
          String key = "capability:" + x.name();
          Optional<String> why = marks.unavailable(key);
          boolean ok = accepted.test(peer.name(), x.name());
          entries.add(
              new Response.PeerOverview.Entry(
                  "capability",
                  peer.name() + "/" + x.name(),
                  x.effect(),
                  why.isPresent() ? "unavailable" : ok ? "ok" : "not accepted",
                  why.orElse(ok ? null : "approve it in the requests")));
        }
        for (var t : c.types()) {
          entries.add(entry("type", peer, t.id(), t.title(), marks.unavailable("type:" + t.id())));
        }
        for (var s : c.sets()) {
          entries.add(entry("set", peer, s.id(), s.title(), marks.unavailable("set:" + s.id())));
          for (String m : s.members()) {
            entries.add(
                entry(
                    "member",
                    peer,
                    s.id() + "/" + m,
                    "",
                    marks.unavailable("member:" + s.id() + "/" + m)));
          }
        }
        // what the remote offered and stopped offering stays in the list, marked
        marks
            .gone()
            .forEach(
                (key, since) -> {
                  String kind = key.substring(0, key.indexOf(':'));
                  String name = key.substring(key.indexOf(':') + 1);
                  boolean present =
                      entries.stream()
                          .anyMatch(
                              e ->
                                  e.kind().equals(kind)
                                      && e.name().equals(peer.name() + "/" + name));
                  if (!present) {
                    entries.add(
                        new Response.PeerOverview.Entry(
                            kind,
                            peer.name() + "/" + name,
                            "",
                            "unavailable",
                            peer.name() + " no longer offers it (since " + since + ")"));
                  }
                });
        for (var p : pins.pending(peer.name(), c)) {
          entries.add(
              new Response.PeerOverview.Entry(
                  "pin",
                  p.pin().name(),
                  String.join(", ", p.pin().members()),
                  p.changed() ? "changed" : "ok",
                  p.gone()
                      ? "the set is no longer offered"
                      : p.changed() ? "adds " + p.added() + ", removes " + p.removed() : null));
        }
      }
      out.add(
          new Response.PeerOverview.PeerInfo(
              peer.name(),
              peer.fingerprint(),
              peer.socket().orElse(null),
              peer.standingRead(),
              state,
              marks.unreachableSince().map(Object::toString).orElse(null),
              remote.map(r -> r.fetched().toString()).orElse(null),
              // the kept catalog is only stale while the peer cannot be reached
              marks.unreachableSince().isPresent(),
              entries));
    }
    return new Response.PeerOverview(Peers.fingerprint(own.publicKey()), out);
  }

  private static Response.PeerOverview.Entry entry(
      String kind, Peers.Peer peer, String name, String detail, Optional<String> why) {
    return new Response.PeerOverview.Entry(
        kind,
        peer.name() + "/" + name,
        detail,
        why.isPresent() ? "unavailable" : "ok",
        why.orElse(null));
  }
}
