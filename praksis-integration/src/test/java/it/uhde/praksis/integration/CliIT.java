// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** The CLI against a daemon it starts itself, as separate processes. */
class CliIT {
  @Test
  void startStatusInjectLogStop() throws Exception {
    try (Harness h = new Harness()) {
      assertThat(h.cli("status").exit()).isEqualTo(3);

      Harness.CliResult start = h.cli("start");
      assertThat(start.exit()).as(start.output()).isZero();
      assertThat(start.output()).contains("The daemon is running");
      assertThat(h.cli("start").output()).contains("already running");

      h.deliver("1700000000.M1P1.host", "hello");
      Harness.await("the mail to be archived", () -> h.names(".Archive/cur").size() == 1);

      Harness.CliResult status = h.cli("status");
      assertThat(status.output())
          .contains("running, pid")
          .contains("ruleset: mail (1 rules)")
          .contains("modules: maildir");

      Harness.CliResult log = h.cli("log", "-n", "5");
      assertThat(log.exit()).isZero();
      assertThat(log.output()).contains("mail.archive/move").contains("OK");

      Harness.CliResult audit = h.cli("audit", "-n", "5");
      assertThat(audit.exit()).as(audit.output()).isZero();
      assertThat(audit.output())
          .containsPattern(
              "CALL +mail\\.archive +allowed +mail\\.move \\{.*\"folder\":\"Archive\".*\\}")
          .containsPattern(
              "POLICY .*policy\\.toml \\{\"sha256\":\"[0-9a-f]{64}\",\"grants\":\\[\"ruleset-moves\"\\]\\}");

      Harness.CliResult bad = h.cli("inject", "mail.received", "--data", "{\"size\":\"big\"}");
      assertThat(bad.exit()).isEqualTo(1);
      assertThat(bad.output())
          .contains("missing required field 'message'")
          .contains("field 'size': must be of type int");

      // an injected event for a mail that does not exist fails visibly in the log
      String data = "{\"message\":\"" + h.mail + "/new/nothing\",\"folder\":\"INBOX\",\"size\":1}";
      assertThat(h.cli("inject", "mail.received", "--key", "k1", "--data", data).output())
          .contains("accepted");
      assertThat(h.cli("inject", "mail.received", "--key", "k1", "--data", data).output())
          .contains("dropped");
      Harness.await(
          "the failure to be logged", () -> h.cli("log").output().contains("mail not found"));

      Harness.CliResult stop = h.cli("stop");
      assertThat(stop.exit()).isZero();
      Harness.await("the socket to disappear", () -> !Files.exists(h.socket()));
      assertThat(h.cli("status").exit()).isEqualTo(3);
    }
  }
}
