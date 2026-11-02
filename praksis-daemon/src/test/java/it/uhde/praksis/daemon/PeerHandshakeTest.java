// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.PeerVersions;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeerHandshakeTest {
  @TempDir Path dir;
  Peers acme; // the daemon that is connected to
  Peers me; // the daemon that connects
  Path socket;
  ApiServer server;

  @BeforeEach
  void start() throws IOException {
    Path acmeHome = Files.createDirectory(dir.resolve("a"));
    Path meHome = Files.createDirectory(dir.resolve("m"));
    acme = new Peers(acmeHome);
    me = new Peers(meHome);
    acme.add("me", me.publicKey(), Optional.empty(), false);
    me.add("acme", acme.publicKey(), Optional.of(dir.resolve("s").toString()), false);
    socket = dir.resolve("s");
    server =
        new ApiServer(
            socket, r -> new Response.StatusReport(1, "", "", 0, java.util.List.of(), 0), () -> {},
            new PeerHandshake(acme));
    server.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void aListedPeerGetsIn() throws IOException {
    try (PeerLink link = PeerLink.connect(me, me.byName("acme").orElseThrow())) {
      assertEquals(PeerVersions.CATALOG_VERSION, link.agreed().catalog());
      // a peer is not the owner: it cannot do what the owner of the socket can
      Response r = link.send(new Request.Status());
      assertEquals(new Response.Error("a peer cannot do this"), r);
    }
  }

  @Test
  void anUnlistedKeyIsRefused(@TempDir Path stranger) throws IOException {
    Peers other = new Peers(stranger);
    Peers.Peer asAcme = new Peers.Peer("acme", acme.publicKey(), Optional.of(socket.toString()), false);
    IOException e = assertThrows(IOException.class, () -> PeerLink.connect(other, asAcme));
    assertTrue(e.getMessage().contains("not accepted"), e.getMessage());
  }

  @Test
  void theOtherSideMustHoldTheKeyWeListed(@TempDir Path stranger) throws IOException {
    Peers other = new Peers(stranger);
    Peers.Peer wrong = new Peers.Peer("acme", other.publicKey(), Optional.of(socket.toString()), false);
    IOException e = assertThrows(IOException.class, () -> PeerLink.connect(me, wrong));
    assertTrue(e.getMessage().contains("another key"), e.getMessage());
  }

  @Test
  void aWrongProofIsRefusedAndTheConnectionEnds() throws IOException {
    try (Client c = Client.connect(socket)) {
      Response r =
          c.send(new Request.PeerHello(me.publicKey(), PeerHandshake.nonce(), PeerVersions.local()));
      assertInstanceOf(Response.PeerChallenge.class, r);
      Response proof = c.send(new Request.PeerProve("AAAA"));
      assertEquals(new Response.Error("not accepted"), proof);
      assertThrows(IOException.class, () -> c.send(new Request.Status()));
    }
  }

  @Test
  void aChallengedConnectionCannotDoAnythingElse() throws IOException {
    try (Client c = Client.connect(socket)) {
      c.send(new Request.PeerHello(me.publicKey(), PeerHandshake.nonce(), PeerVersions.local()));
      assertEquals(new Response.Error("a peer cannot do this"), c.send(new Request.Status()));
    }
  }

  @Test
  void versionsThatDoNotFitAreRefused() throws IOException {
    try (Client c = Client.connect(socket)) {
      PeerVersions future = new PeerVersions(99, 98, 99, 98);
      Response r = c.send(new Request.PeerHello(me.publicKey(), PeerHandshake.nonce(), future));
      assertTrue(((Response.Error) r).message().startsWith("no common version"));
    }
  }

  @Test
  void versionsAreAgreedOnTheHighestBothUnderstand() {
    PeerVersions mine = new PeerVersions(3, 1, 2, 1);
    PeerVersions theirs = new PeerVersions(2, 2, 5, 2);
    assertEquals(new PeerVersions.Agreed(2, 2), mine.agree(theirs).orElseThrow());
    assertTrue(new PeerVersions(1, 1, 1, 1).agree(new PeerVersions(3, 2, 1, 1)).isEmpty());
  }

  @Test
  void theOwnerOfTheSocketIsNotAffected() throws IOException {
    try (Client c = Client.connect(socket)) {
      assertInstanceOf(Response.StatusReport.class, c.send(new Request.Status()));
    }
  }
}
