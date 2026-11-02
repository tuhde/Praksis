// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PeersTest {
  @TempDir Path dir;

  @Test
  void theKeyIsMadeOnceAndKeptPrivate() throws IOException {
    Peers a = new Peers(dir);
    String first = a.publicKey();
    assertEquals(first, new Peers(dir).publicKey());
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(dir.resolve("identity").resolve("key"))));
  }

  @Test
  void signatureProvesTheKey(@TempDir Path other) throws IOException {
    Peers a = new Peers(dir);
    Peers b = new Peers(other);
    byte[] data = "challenge".getBytes(StandardCharsets.UTF_8);
    byte[] sig = a.sign(data);
    assertTrue(Peers.verify(a.publicKey(), data, sig));
    assertFalse(Peers.verify(b.publicKey(), data, sig));
    assertFalse(Peers.verify(a.publicKey(), "other".getBytes(StandardCharsets.UTF_8), sig));
  }

  @Test
  void addListRemove(@TempDir Path other) throws IOException {
    Peers a = new Peers(dir);
    String key = new Peers(other).publicKey();
    assertTrue(a.list().isEmpty());
    a.add("acme", key, Optional.of("/run/acme.sock"), true);
    Peers.Peer p = a.list().get(0);
    assertEquals("acme", p.name());
    assertEquals(Optional.of("/run/acme.sock"), p.socket());
    assertTrue(p.standingRead());
    assertEquals("acme", a.byKey(key).orElseThrow().name());
    assertTrue(a.remove("acme"));
    assertFalse(a.remove("acme"));
    assertTrue(a.list().isEmpty());
  }

  @Test
  void refusesBadEntries(@TempDir Path other) throws IOException {
    Peers a = new Peers(dir);
    String key = new Peers(other).publicKey();
    assertThrows(IOException.class, () -> a.add("Acme!", key, Optional.empty(), false));
    assertThrows(IOException.class, () -> a.add("acme", "not-a-key", Optional.empty(), false));
    assertThrows(IOException.class, () -> a.add("me", a.publicKey(), Optional.empty(), false));
    a.add("acme", key, Optional.empty(), false);
    assertThrows(IOException.class, () -> a.add("acme", key, Optional.empty(), false));
    assertThrows(IOException.class, () -> a.add("other", key, Optional.empty(), false));
  }

  @Test
  void readsAFileWrittenByHand(@TempDir Path other) throws IOException {
    String key = new Peers(other).publicKey();
    Files.writeString(
        dir.resolve("peers.toml"),
        "format_version = 1\n[[peer]]\nname = \"x\"\nkey = \"" + key + "\"\nbogus = 1\n");
    assertThrows(IOException.class, () -> new Peers(dir).list());
  }
}
