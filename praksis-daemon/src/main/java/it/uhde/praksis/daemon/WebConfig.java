// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.ruleset.Table;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The {@code [web]} section of {@code praksis.toml}: whether web clients may reach this daemon, and
 * where. Without the section there is no listener at all.
 *
 * <pre>
 * [web]
 * listen  = "127.0.0.1:8484"          # an address of this machine; nothing else is accepted yet
 * origins = ["http://localhost:8080"]  # pages that may call the API from a browser
 * </pre>
 *
 * @param listen the address to listen on, empty for none
 * @param origins the origins of pages that may call the API besides the daemon's own address
 */
public record WebConfig(Optional<Listen> listen, List<String> origins) {
  /** A loopback address and a port. */
  public record Listen(String host, int port) {}

  static WebConfig none() {
    return new WebConfig(Optional.empty(), List.of());
  }

  static WebConfig parse(Table t) {
    t.onlyKeys(Set.of("listen", "origins"));
    Optional<Listen> listen = t.string("listen", false).flatMap(s -> listen(t, s));
    List<String> origins = t.strings("origins");
    for (String o : origins) {
      if (!o.matches("https?://[A-Za-z0-9.\\-\\[\\]:]+")) {
        t.problem("'origins' has '" + o + "', which is not an origin like http://localhost:8080");
      }
    }
    return new WebConfig(listen, List.copyOf(origins));
  }

  private static Optional<Listen> listen(Table t, String s) {
    int colon = s.lastIndexOf(':');
    if (colon <= 0) {
      t.problem("'listen' must be HOST:PORT, for example 127.0.0.1:8484");
      return Optional.empty();
    }
    String host = s.substring(0, colon);
    int port;
    try {
      port = Integer.parseInt(s.substring(colon + 1));
    } catch (NumberFormatException e) {
      port = -1;
    }
    if (port < 1 || port > 65535) {
      t.problem("'listen' needs a port from 1 to 65535");
      return Optional.empty();
    }
    if (!(host.equals("127.0.0.1") || host.equals("localhost") || host.equals("[::1]"))) {
      t.problem(
          "'listen' must be a loopback address (127.0.0.1, localhost or [::1]): a port that other"
              + " machines can reach needs TLS, which does not exist yet");
      return Optional.empty();
    }
    return Optional.of(new Listen(host, port));
  }
}
