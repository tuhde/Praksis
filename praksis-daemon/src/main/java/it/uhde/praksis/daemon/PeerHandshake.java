// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;

/**
 * The connection of another daemon, seen from the daemon it connects to. Both sides prove that they
 * hold the key that the other has listed (see {@link Peers}):
 *
 * <ol>
 *   <li>the caller sends its key, a nonce and its versions ({@link Request.PeerHello});
 *   <li>the daemon checks that the key is listed, agrees on versions, and answers with its own key,
 *       nonce and versions and a signature over the transcript, so the caller knows whom it
 *       reached;
 *   <li>the caller signs the same transcript for its role ({@link Request.PeerProve}); if the
 *       signature is right the connection belongs to the peer.
 * </ol>
 *
 * The transcript contains both keys, both nonces and both version statements, so a signature cannot
 * be replayed on another connection or to another daemon, and the versions cannot be changed on the
 * way. A connection that has begun the handshake can do nothing else until it is a peer.
 */
final class PeerHandshake {
  private static final SecureRandom RANDOM = new SecureRandom();
  private final Peers peers;

  PeerHandshake(Peers peers) {
    this.peers = peers;
  }

  /** What a connection knows about the handshake. */
  static final class Session {
    enum State {
      /** No handshake: the owner of the socket. */
      LOCAL,
      /** Hello answered, proof expected. */
      CHALLENGED,
      /** Proven. */
      PEER
    }

    State state = State.LOCAL;
    Peers.Peer peer;
    String serverNonce;
    byte[] transcript;
    PeerVersions.Agreed agreed;

    /** The authenticated peer, once the connection is one. */
    Optional<Peers.Peer> peer() {
      return state == State.PEER ? Optional.of(peer) : Optional.empty();
    }
  }

  /** Answers a hello. */
  Response hello(Session s, Request.PeerHello hello) {
    if (s.state != Session.State.LOCAL) {
      return new Response.Error("the handshake has been done or begun on this connection");
    }
    try {
      Optional<Peers.Peer> peer = hello.key() == null ? Optional.empty() : peers.byKey(hello.key());
      if (peer.isEmpty() || hello.nonce() == null || hello.versions() == null) {
        return new Response.Error("not accepted");
      }
      PeerVersions mine = PeerVersions.local();
      Optional<PeerVersions.Agreed> agreed = mine.agree(hello.versions());
      if (agreed.isEmpty()) {
        return new Response.Error("no common version: " + mine.disagreement(hello.versions()));
      }
      String nonce = nonce();
      byte[] transcript =
          transcript(
              hello.key(), peers.publicKey(), hello.nonce(), nonce, hello.versions(), mine);
      String signature =
          Base64.getEncoder().encodeToString(peers.sign(prefix("server", transcript)));
      s.state = Session.State.CHALLENGED;
      s.peer = peer.get();
      s.serverNonce = nonce;
      s.transcript = transcript;
      s.agreed = agreed.get();
      return new Response.PeerChallenge(peers.publicKey(), nonce, mine, signature);
    } catch (IOException e) {
      return new Response.Error("the peers cannot be read: " + e.getMessage());
    }
  }

  /** Answers a proof; on failure the session goes back to the start and the caller should hang up. */
  Response prove(Session s, Request.PeerProve prove) {
    if (s.state != Session.State.CHALLENGED) {
      return new Response.Error("no handshake is begun on this connection");
    }
    byte[] sig;
    try {
      sig = Base64.getDecoder().decode(prove.signature() == null ? "" : prove.signature());
    } catch (IllegalArgumentException e) {
      sig = new byte[0];
    }
    if (!Peers.verify(s.peer.key(), prefix("client", s.transcript), sig)) {
      s.state = Session.State.LOCAL;
      s.peer = null;
      s.transcript = null;
      return new Response.Error("not accepted");
    }
    s.state = Session.State.PEER;
    return new Response.PeerWelcome(s.peer.name(), s.agreed.protocol(), s.agreed.catalog());
  }

  static String nonce() {
    byte[] b = new byte[32];
    RANDOM.nextBytes(b);
    return Base64.getEncoder().encodeToString(b);
  }

  static byte[] transcript(
      String clientKey,
      String serverKey,
      String clientNonce,
      String serverNonce,
      PeerVersions client,
      PeerVersions server) {
    return String.join(
            "\n",
            "praksis-peer-v1",
            clientKey,
            serverKey,
            clientNonce,
            serverNonce,
            client.toString(),
            server.toString())
        .getBytes(StandardCharsets.UTF_8);
  }

  /** The role goes in front, so the signature of one side is not valid for the other. */
  static byte[] prefix(String role, byte[] transcript) {
    byte[] r = (role + "\n").getBytes(StandardCharsets.UTF_8);
    byte[] out = new byte[r.length + transcript.length];
    System.arraycopy(r, 0, out, 0, r.length);
    System.arraycopy(transcript, 0, out, r.length, transcript.length);
    return out;
  }
}
