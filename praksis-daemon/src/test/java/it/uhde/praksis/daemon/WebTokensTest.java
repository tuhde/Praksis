// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebTokensTest {
  @TempDir Path dir;

  @Test
  void aTokenLogsInAndOnlyItsHashIsKept() throws Exception {
    WebTokens tokens = new WebTokens(dir);
    String secret = tokens.create("phone", true, Instant.now());
    var found = tokens.authenticate(secret).orElseThrow();
    assertEquals("phone", found.name());
    assertTrue(found.readOnly());
    String file = Files.readString(dir.resolve("web").resolve("tokens.json"));
    assertFalse(file.contains(secret));
    assertEquals(
        "rw-------",
        PosixFilePermissions.toString(
            Files.getPosixFilePermissions(dir.resolve("web").resolve("tokens.json"))));
  }

  @Test
  void aWrongOrRevokedTokenDoesNotLogIn() throws Exception {
    WebTokens tokens = new WebTokens(dir);
    String secret = tokens.create("tablet", false, Instant.now());
    assertTrue(tokens.authenticate(null).isEmpty());
    assertTrue(tokens.authenticate("").isEmpty());
    assertTrue(tokens.authenticate(secret + "x").isEmpty());
    assertTrue(tokens.authenticate("pwt_other").isEmpty());
    tokens.revoke("tablet");
    assertTrue(tokens.authenticate(secret).isEmpty());
    assertTrue(tokens.list().isEmpty());
  }

  @Test
  void namesAreChecked() throws Exception {
    WebTokens tokens = new WebTokens(dir);
    tokens.create("a", false, Instant.now());
    assertThrows(DaemonException.class, () -> tokens.create("a", false, Instant.now()));
    assertThrows(DaemonException.class, () -> tokens.create("Bad Name", false, Instant.now()));
    assertThrows(DaemonException.class, () -> tokens.revoke("nobody"));
  }

  @Test
  void twoTokensDiffer() throws Exception {
    WebTokens tokens = new WebTokens(dir);
    String a = tokens.create("a", false, Instant.now());
    String b = tokens.create("b", false, Instant.now());
    assertFalse(a.equals(b));
    assertEquals("b", tokens.authenticate(b).orElseThrow().name());
  }
}
