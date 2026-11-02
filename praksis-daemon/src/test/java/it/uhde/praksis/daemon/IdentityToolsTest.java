// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentityToolsTest {
  @TempDir Path dir;

  @Test
  void linkMakesEachIdentityAPeerOfTheOtherAndIsRepeatable() throws IOException {
    Path a = Files.createDirectory(dir.resolve(".praksis-private"));
    Path b = Files.createDirectory(dir.resolve(".praksis-work"));
    var first = IdentityTools.link(a, "private", b, "work");
    assertThat(first).hasSize(2).allMatch(l -> l.contains("now lists"));
    Peers.Peer ofA = new Peers(a).byName("work").orElseThrow();
    assertThat(ofA.key()).isEqualTo(new Peers(b).publicKey());
    assertThat(ofA.socket()).contains(b.resolve("praksis.sock").toString());
    assertThat(new Peers(b).byName("private").orElseThrow().key())
        .isEqualTo(new Peers(a).publicKey());
    // nothing is allowed by it
    assertThat(Files.exists(a.resolve("policy.toml"))).isFalse();
    assertThat(ofA.standingRead()).isFalse();

    assertThat(IdentityTools.link(a, "private", b, "work")).allMatch(l -> l.contains("already"));
  }

  @Test
  void linkRefusesAStaleEntryWithAnotherKeyAndLinkingToItself() throws IOException {
    Path a = Files.createDirectory(dir.resolve("a"));
    Path b = Files.createDirectory(dir.resolve("b"));
    Path c = Files.createDirectory(dir.resolve("c"));
    new Peers(a).add("work", new Peers(c).publicKey(), Optional.empty(), false);
    assertThatThrownBy(() -> IdentityTools.link(a, "private", b, "work"))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("another key");
    assertThatThrownBy(() -> IdentityTools.link(a, "x", a, "x")).isInstanceOf(IOException.class);
  }

  @Test
  void aSharedAccountOrPlaceIsFoundAndDifferentOnesAreNot() {
    var settings =
        Map.of(
            "private",
                Map.of(
                    "imap",
                        Map.<String, Object>of(
                            "user", "till@example.org", "host", "mail.example.org"),
                    "maildir", Map.<String, Object>of("root", "/home/me/Maildir")),
            "work",
                Map.of(
                    "imap",
                        Map.<String, Object>of(
                            "user", "till@firma.example", "host", "mail.example.org"),
                    "maildir", Map.<String, Object>of("root", "/home/me/Maildir")));
    var shared = IdentityTools.sharedAccounts(settings);
    assertThat(shared).hasSize(1);
    assertThat(shared.get(0))
        .contains("maildir")
        .contains("/home/me/Maildir")
        .contains("private and work");
  }
}
