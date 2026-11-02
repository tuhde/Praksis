// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import java.util.Optional;

/**
 * The versions a daemon speaks when it connects to another daemon: the protocol (see {@link
 * Wire#PROTOCOL_VERSION}) and the catalog (the form of the list of types, sets, capabilities,
 * commands and views that a daemon offers, {@link #CATALOG_VERSION}). Each is given as the newest
 * version understood and the oldest still accepted; the two daemons use the highest version that
 * both understand, or refuse each other.
 *
 * @param protocol the newest protocol version understood
 * @param minProtocol the oldest protocol version accepted
 * @param catalog the newest catalog version understood
 * @param minCatalog the oldest catalog version accepted
 */
public record PeerVersions(int protocol, int minProtocol, int catalog, int minCatalog) {
  /** The version of the catalog that a daemon offers to its peers. */
  public static final int CATALOG_VERSION = 1;

  /** What was agreed. */
  public record Agreed(int protocol, int catalog) {}

  /** The versions of this build. Below 1.0.0 nothing older is accepted. */
  public static PeerVersions local() {
    return new PeerVersions(
        Wire.PROTOCOL_VERSION, Wire.PROTOCOL_VERSION, CATALOG_VERSION, CATALOG_VERSION);
  }

  /** The versions both sides understand, or empty if there are none. */
  public Optional<Agreed> agree(PeerVersions other) {
    int p = Math.min(protocol, other.protocol);
    int c = Math.min(catalog, other.catalog);
    if (p < Math.max(minProtocol, other.minProtocol)
        || c < Math.max(minCatalog, other.minCatalog)) {
      return Optional.empty();
    }
    return Optional.of(new Agreed(p, c));
  }

  /** Why {@link #agree} found nothing, for a message. */
  public String disagreement(PeerVersions other) {
    return "protocol "
        + minProtocol
        + ".."
        + protocol
        + " here, "
        + other.minProtocol
        + ".."
        + other.protocol
        + " there; catalog "
        + minCatalog
        + ".."
        + catalog
        + " here, "
        + other.minCatalog
        + ".."
        + other.catalog
        + " there";
  }
}
