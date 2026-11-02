// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class IdentitiesTest {
  @TempDir Path userHome;
  private String before;

  @BeforeEach
  void setUp() {
    before = System.getProperty("user.home");
    System.setProperty("user.home", userHome.toString());
  }

  @AfterEach
  void tearDown() {
    System.setProperty("user.home", before);
  }

  @Test
  void anIdentityHasItsHomeNextToTheDefaultOne() {
    assertThat(Identities.home("private")).isEqualTo(userHome.resolve(".praksis-private"));
    assertThatThrownBy(() -> Identities.home("Private"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Identities.home("../x")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Identities.home("a".repeat(17)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void createMakesAHomeThatNamesTheIdentityAndRefusesToDoItTwice() throws IOException {
    Path home = Identities.create("work", userHome.resolve("modules"));
    String toml = Files.readString(home.resolve("praksis.toml"));
    assertThat(toml).contains("identity = \"work\"").contains("shared_modules = \"");
    assertThatThrownBy(() -> Identities.create("work", null)).isInstanceOf(IOException.class);
  }

  @Test
  void listFindsTheHomesByTheirNames() throws IOException {
    Identities.create("private", null);
    Identities.create("work", null);
    Files.createDirectories(userHome.resolve(".praksis-Bad Name"));
    Files.createDirectories(userHome.resolve(".praksis"));
    assertThat(Identities.list())
        .extracting(Identities.Found::name)
        .containsExactly("private", "work");
    assertThat(Identities.list()).allMatch(f -> !f.running());
  }

  @Test
  void theCommandLineFindsTheHomeOfAnIdentity() {
    Praksis praksis = new Praksis();
    praksis.identity = "private";
    assertThat(praksis.home()).isEqualTo(userHome.resolve(".praksis-private"));
    assertThat(praksis.socket()).isEqualTo(userHome.resolve(".praksis-private/praksis.sock"));
    praksis.home = userHome.resolve("elsewhere");
    assertThat(praksis.home()).isEqualTo(userHome.resolve("elsewhere"));
  }
}
