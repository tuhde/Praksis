// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code praksis verify --replay}: stored events through the ruleset in use and a candidate. */
class ReplayIT {
  private static final String TESTS =
      """
      format_version = 1

      [[test]]
      name = "mail is moved"
      [test.event]
      type = "mail.received"
      data = { message = "m1" }
      [[test.call]]
      capability = "mail.move"
      args = { folder = "%s" }
      """;

  /** A copy of the ruleset of the harness that moves mail to another folder. */
  private static Path candidate(Harness h, String folder) throws Exception {
    Path dir = h.home.resolve("candidate");
    Files.createDirectories(dir.resolve("rules"));
    Files.createDirectories(dir.resolve("tests"));
    Files.writeString(dir.resolve("ruleset.toml"), "format_version = 1\nname = \"mail\"\n");
    Files.writeString(
        dir.resolve("rules/mail.archive.toml"), Harness.RULE.replace("Archive", folder));
    Files.writeString(dir.resolve("tests/mail.archive.toml"), TESTS.formatted(folder));
    return dir;
  }

  @Test
  void storedEventsShowWhatACandidateWouldDoDifferently() throws Exception {
    try (Harness h = new Harness()) {
      Files.createDirectories(h.home.resolve("ruleset/tests"));
      Files.writeString(
          h.home.resolve("ruleset/tests/mail.archive.toml"), TESTS.formatted("Archive"));
      Process daemon = h.startDaemon();
      h.deliver("1.a", "one");
      h.deliver("2.b", "two");
      Harness.await("both mails to be archived", () -> h.names(".Archive/cur").size() == 2);
      h.stop(daemon);

      // nothing changes without a candidate
      Harness.CliResult same = h.cli("verify", "--replay");
      assertThat(same.exit()).as(same.output()).isZero();
      assertThat(same.output()).contains("Replay of 2 stored event(s): 0 treated differently");

      // a candidate that files into a folder that no grant names: the differences are shown, and
      // the new access is an error
      Path dir = candidate(h, "Spam");
      Harness.CliResult refused = h.cli("verify", "--replay", "--layer", "ruleset=" + dir);
      assertThat(refused.exit()).as(refused.output()).isEqualTo(1);
      assertThat(refused.output())
          .contains("Candidate mail (1 rules): 1 error(s)")
          .containsPattern("ERROR +access +mail\\.archive: new access that no grant covers")
          .contains("Replay of 2 stored event(s): 2 treated differently")
          .contains("now:       mail.archive: mail.move")
          .contains("candidate: mail.archive: mail.move")
          .contains("folder=Spam");
      // checking changed nothing
      assertThat(h.names(".Archive/cur")).hasSize(2);
      assertThat(h.names(".Spam/cur")).isEmpty();

      // with the grant the candidate is fine, and -n limits the replay
      h.grantMoves(Map.of("ruleset", List.of("Archive", "Spam")));
      Harness.CliResult granted =
          h.cli("verify", "--replay", "-n", "1", "--layer", "ruleset=" + dir);
      assertThat(granted.exit()).as(granted.output()).isZero();
      assertThat(granted.output()).contains("Replay of 1 stored event(s): 1 treated differently");
    }
  }
}
