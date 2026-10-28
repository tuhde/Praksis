// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** {@code praksis test} against the runtime image: the tests of a layer on the real engine. */
class RuleTestingIT {
  private static final String TESTS =
      """
      format_version = 1

      [[test]]
      name = "mail is archived"
      [test.event]
      type = "mail.received"
      data = { message = "/some/mail" }
      [[test.call]]
      capability = "mail.move"
      args = { message = "/some/mail", folder = "%s" }
      """;

  @Test
  void theTestsOfALayerPassOrFailWithTheDifference() throws Exception {
    try (Harness h = new Harness()) {
      Files.createDirectories(h.home.resolve("ruleset/tests"));
      Files.writeString(
          h.home.resolve("ruleset/tests/mail.archive.toml"), TESTS.formatted("Archive"));
      Harness.CliResult good = h.cli("test");
      assertThat(good.exit()).as(good.output()).isZero();
      assertThat(good.output())
          .contains("Tests: 1 run, 1 passed, 0 failed")
          .containsPattern("ok +ruleset +mail\\.archive: mail is archived");
      // nothing was moved: the capabilities are recorders
      assertThat(h.names("new")).isEmpty();

      Files.writeString(
          h.home.resolve("ruleset/tests/mail.archive.toml"), TESTS.formatted("Elsewhere"));
      Harness.CliResult bad = h.cli("test");
      assertThat(bad.exit()).isEqualTo(1);
      assertThat(bad.output())
          .contains("Tests: 1 run, 0 passed, 1 failed")
          .containsPattern("FAIL +ruleset +mail\\.archive: mail is archived")
          .contains("call 1 should be mail.move")
          .contains("folder=Elsewhere");

      Files.writeString(
          h.home.resolve("ruleset/tests/mail.archive.toml"), "[[test]]\nname = \"x\"\n");
      Harness.CliResult unreadable = h.cli("test");
      assertThat(unreadable.exit()).isEqualTo(1);
      assertThat(unreadable.output()).contains("2 unreadable").contains("missing [event]");
    }
  }
}
