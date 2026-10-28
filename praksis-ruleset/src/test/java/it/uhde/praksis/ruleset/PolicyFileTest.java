// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyFileTest {
  @TempDir Path dir;

  private static final String HAND_WRITTEN =
      """
      format_version = 1
      # grants for the public mail rules
      [[grant]]
      id = "mail-sort"   # keep
      layer = "mail"
      capability = "mail.move"
      [grant.args]
      folder = { one_of = ["Spam", "Lists"] }

      # mine
      [[grant]]
      id = "mine"
      layer = "mine"
      capability = "mail.move"
      """;

  private static Grant grant(String id, String folder) {
    return new Grant(
        id,
        "mail",
        "mail.move",
        Map.of("folder", new ArgMatcher.Equals(folder)),
        Optional.of("approved \"once\" for 'x'"));
  }

  @Test
  void aGrantIsAppendedWithoutTouchingWhatIsThere() throws Exception {
    Path file = dir.resolve("policy.toml");
    Files.writeString(file, HAND_WRITTEN);
    PolicyFile.append(file, grant("approval-7", "Trash"));

    String text = Files.readString(file);
    assertThat(text).startsWith(HAND_WRITTEN);
    assertThat(text)
        .contains("[[grant]]\nid = \"approval-7\"\n")
        .contains("folder = { equals = \"Trash\" }");
    Policy policy = Policy.load(file);
    assertThat(policy.grants())
        .extracting(Grant::id)
        .containsExactly("mail-sort", "mine", "approval-7");
    assertThat(policy.grants().get(2).note()).contains("approved \"once\" for 'x'");
    assertThat(policy.find("mail", "mail.move", Map.of("folder", "Trash"))).isPresent();
  }

  @Test
  void aMissingFileIsCreated() throws Exception {
    Path file = dir.resolve("policy.toml");
    PolicyFile.append(file, grant("a", "Trash"));
    assertThat(Policy.load(file).grants()).hasSize(1);
    assertThat(Files.readString(file)).startsWith("format_version = 1\n");
  }

  @Test
  void aGrantIsRemovedWithItsArgsAndTheNextGrantKeepsItsComment() throws Exception {
    Path file = dir.resolve("policy.toml");
    Files.writeString(file, HAND_WRITTEN);
    assertThat(PolicyFile.remove(file, "mail-sort")).isTrue();
    assertThat(Files.readString(file))
        .isEqualTo(
            """
            format_version = 1
            # grants for the public mail rules

            # mine
            [[grant]]
            id = "mine"
            layer = "mine"
            capability = "mail.move"
            """);

    assertThat(PolicyFile.remove(file, "mine")).isTrue();
    assertThat(Policy.load(file).grants()).isEmpty();
    assertThat(PolicyFile.remove(file, "mine")).isFalse();
    assertThat(PolicyFile.remove(dir.resolve("none.toml"), "x")).isFalse();
  }

  @Test
  void anArgumentNamedIdDoesNotCountAsTheGrantId() throws Exception {
    Path file = dir.resolve("policy.toml");
    Files.writeString(
        file,
        """
        format_version = 1
        [[grant]]
        id = "a"
        layer = "l"
        capability = "c"
        [grant.args]
        id = { equals = "b" }
        """);
    assertThat(PolicyFile.remove(file, "b")).isFalse();
    assertThat(PolicyFile.remove(file, "a")).isTrue();
  }

  @Test
  void aChangeThatWouldBreakThePolicyIsRefusedAndTheFileStays() throws Exception {
    Path file = dir.resolve("policy.toml");
    Files.writeString(file, HAND_WRITTEN);
    // the same id twice
    assertThatThrownBy(() -> PolicyFile.append(file, grant("mine", "Trash")))
        .isInstanceOf(RulesetException.class)
        .hasMessageContaining("used twice");
    assertThat(Files.readString(file)).isEqualTo(HAND_WRITTEN);
    assertThat(dir.resolve("policy.toml.new")).doesNotExist();
  }

  @Test
  void blockFormatsEveryMatcher() {
    Grant g =
        new Grant(
            "g",
            "l",
            "c",
            Map.of(
                "a", new ArgMatcher.OneOf(List.of("x", 2L)),
                "b", new ArgMatcher.Prefix("P/")),
            Optional.empty());
    assertThat(PolicyFile.block(g))
        .isEqualTo(
            """
            [[grant]]
            id = "g"
            layer = "l"
            capability = "c"
            [grant.args]
            a = { one_of = ["x", 2] }
            b = { prefix = "P/" }
            """);
  }
}
