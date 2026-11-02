// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Base64;

/**
 * A connection from this daemon to a peer, after the handshake of {@link PeerHandshake}: the peer
 * has shown that it holds the key we listed for it, and we have shown ours.
 */
final class PeerLink implements AutoCloseable {
  private final Client client;
  private final Peers.Peer peer;
  private final PeerVersions.Agreed agreed;

  private PeerLink(Client client, Peers.Peer peer, PeerVersions.Agreed agreed) {
    this.client = client;
    this.peer = peer;
    this.agreed = agreed;
  }

  /** The peer at the other end. */
  Peers.Peer peer() {
    return peer;
  }

  /** The versions that were agreed. */
  PeerVersions.Agreed agreed() {
    return agreed;
  }

  /** Sends a request over the connection. */
  Response send(Request request) throws IOException {
    return client.send(request);
  }

  /**
   * Connects to the socket of a listed peer and does the handshake.
   *
   * @throws IOException if nothing listens, the other side holds another key than the one listed,
   *     refuses us, or speaks no version that we understand
   */
  static PeerLink connect(Peers own, Peers.Peer peer) throws IOException {
    Path socket =
        Path.of(
            peer.socket()
                .orElseThrow(() -> new IOException("peer '" + peer.name() + "' has no socket")));
    Client client = Client.connect(socket);
    boolean ok = false;
    try {
      PeerVersions mine = PeerVersions.local();
      String myKey = own.publicKey();
      String nonce = PeerHandshake.nonce();
      Response r = client.send(new Request.PeerHello(myKey, nonce, mine));
      if (r instanceof Response.Error e) {
        throw new IOException("peer '" + peer.name() + "' refused the connection: " + e.message());
      }
      if (!(r instanceof Response.PeerChallenge c)) {
        throw new IOException("peer '" + peer.name() + "' did not answer the handshake");
      }
      if (!peer.key().equals(c.key())) {
        throw new IOException(
            "the daemon at "
                + socket
                + " has another key than the one listed for '"
                + peer.name()
                + "' ("
                + Peers.fingerprint(c.key())
                + " instead of "
                + peer.fingerprint()
                + ")");
      }
      byte[] transcript =
          PeerHandshake.transcript(myKey, c.key(), nonce, c.nonce(), mine, c.versions());
      byte[] sig;
      try {
        sig = Base64.getDecoder().decode(c.signature());
      } catch (IllegalArgumentException e) {
        sig = new byte[0];
      }
      if (!Peers.verify(peer.key(), PeerHandshake.prefix("server", transcript), sig)) {
        throw new IOException("peer '" + peer.name() + "' could not prove its key");
      }
      PeerVersions.Agreed agreed =
          mine.agree(c.versions())
              .orElseThrow(
                  () ->
                      new IOException("no common version: " + mine.disagreement(c.versions())));
      String proof =
          Base64.getEncoder()
              .encodeToString(own.sign(PeerHandshake.prefix("client", transcript)));
      Response w = client.send(new Request.PeerProve(proof));
      if (w instanceof Response.Error e) {
        throw new IOException("peer '" + peer.name() + "' refused the proof: " + e.message());
      }
      if (!(w instanceof Response.PeerWelcome)) {
        throw new IOException("peer '" + peer.name() + "' did not welcome us");
      }
      ok = true;
      return new PeerLink(client, peer, agreed);
    } finally {
      if (!ok) {
        client.close();
      }
    }
  }

  @Override
  public void close() throws IOException {
    client.close();
  }
}
