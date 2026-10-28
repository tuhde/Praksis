// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** {@code praksis verify} against the runtime image, without a running daemon. */
class VerifyIT {
  @Test
  void aGoodSetupPassesAndMistakesAreReportedTogether() throws Exception {
    try (Harness h = new Harness()) {
      Harness.CliResult good = h.cli("verify");
      assertThat(good.exit()).as(good.output()).isZero();
      assertThat(good.output()).contains("Ruleset mail (1 rules): 0 error(s), 0 warning(s)");

      // a rule that asks for more than it calls, one that is shadowed by the first, and a grant for
      // a layer and a capability that do not exist
      Files.writeString(
          h.home.resolve("ruleset/rules/mail.zz-extra.toml"),
          """
          id = "mail.zz-extra"
          effect = "local-write"
          needs = ["mail.move"]

          [trigger]
          kind = "event"
          type = "mail.received"

          [[action]]
          stop = true
          """);
      Files.writeString(
          h.home.resolve("ruleset/rules/mail.archive.toml"),
          Harness.RULE + "\n[[action]]\nstop = true\n");
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "stray"
              layer = "nowhere"
              capability = "mail.copy"
              """);

      Harness.CliResult bad = h.cli("verify");
      assertThat(bad.exit()).isEqualTo(1);
      assertThat(bad.output())
          .contains("3 error(s), 1 warning(s)")
          .containsPattern(
              "ERROR +unused-need +rules/mail\\.zz-extra\\.toml: 'needs' lists 'mail\\.move'")
          .containsPattern("ERROR +policy +policy\\.toml: grant 'stray': no layer named 'nowhere'")
          .containsPattern(
              "ERROR +policy +policy\\.toml: grant 'stray': no capability named 'mail\\.copy'")
          .containsPattern(
              "WARNING +shadowed +rules/mail\\.zz-extra\\.toml: can only fire if 'mail\\.archive'");

      // a rule that may do what no grant covers is a warning that names the access
      Files.writeString(
          h.home.resolve("ruleset/rules/mail.zz-extra.toml"),
          Harness.RULE.replace("mail.archive", "mail.zz-extra").replace("Archive", "Trash"));
      Harness.CliResult ungranted = h.cli("verify");
      assertThat(ungranted.output())
          .containsPattern(
              "WARNING +access +mail\\.zz-extra: no grant covers ruleset: mail\\.move"
                  + " folder=\"Trash\"");

      // an invalid ruleset is an error of the validator, with the file it is about
      Files.writeString(
          h.home.resolve("ruleset/rules/mail.zz-extra.toml"),
          Files.readString(h.home.resolve("ruleset/rules/mail.zz-extra.toml"))
              .replace("mail.move", "mail.nope"));
      Harness.CliResult invalid = h.cli("verify");
      assertThat(invalid.exit()).isEqualTo(1);
      assertThat(invalid.output()).containsPattern("ERROR +validate +rules/mail\\.zz-extra\\.toml");
    }
  }
}
