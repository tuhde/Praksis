// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleViewTest {
  @TempDir Path dir;

  @Test
  void aRuleSurvivesAJsonRoundTripWithTheSameHash() throws Exception {
    Files.createDirectories(dir.resolve("rules"));
    Files.writeString(dir.resolve("ruleset.toml"), "format_version = 1\nname = \"t\"\n");
    Files.writeString(
        dir.resolve("rules/mail.a.toml"),
        """
        id = "mail.a"
        description = "d"
        effect = "local-write"
        needs = ["mail.move"]
        required = true

        [trigger]
        kind = "event"
        type = "mail.received"
        filter = "event.data.n > 3"

        [[action]]
        id = "move"
        capability = "mail.move"
        args = { message = { cel = "event.data.message" }, folder = "Lists", n = 3 }

        [[action]]
        stop = true
        """);
    Rule rule = RulesetLoader.load(dir).rules().get(0);

    Rule back = RuleView.fromJson(RuleView.toJson(rule));

    assertThat(RuleView.hash(back)).isEqualTo(RuleView.hash(rule));
    assertThat(back.actions()).hasSize(2);
  }

  @Test
  void textThatIsNoRuleIsRefused() {
    assertThatThrownBy(() -> RuleView.fromJson("{\"id\":\"x\"}"))
        .isInstanceOf(RulesetException.class);
    assertThatThrownBy(() -> RuleView.fromJson("nope")).isInstanceOf(RulesetException.class);
  }
}
