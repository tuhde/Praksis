// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/** Grants, requests and the audit log through the CLI, against the runtime image. */
class PermissionsIT {
  @Test
  void anUngrantedRuleIsDisabledReportedApprovedAndThenRuns() throws Exception {
    try (Harness h = new Harness()) {
      Files.delete(h.home.resolve("policy.toml"));
      assertThat(h.cli("start").exit()).isZero();
      assertThat(h.cli("grants").output()).contains("No grants");

      // the rule is denied, disabled and reported; the mail stays where it is
      h.deliver("1.first", "one");
      Harness.await("a request", () -> h.cli("approvals").output().contains("PENDING"));
      h.deliver("2.second", "two");
      Harness.await(
          "both mails to be missed", () -> h.cli("approvals").output().contains("mail.archive: 2"));
      String requests = h.cli("approvals").output();
      assertThat(requests)
          .containsPattern(
              "#1 +PENDING +mail\\.archive \\(layer ruleset\\) needs mail\\.move \\{folder=Archive\\}")
          .contains("Disabled rules:")
          .contains("layer 'ruleset' has no grant for mail.move (folder=Archive)");
      assertThat(h.names(".Archive/cur")).isEmpty();
      assertThat(h.cli("log", "-n", "10").output()).contains("DENIED").contains("SKIPPED");
      assertThat(h.cli("audit", "-n", "10").output())
          .containsPattern("CALL +mail\\.archive +DENIED +mail\\.move")
          .contains("request #1");

      // the answer has to say what happens to the mail that was missed
      Harness.CliResult incomplete = h.cli("approvals", "allow", "1", "--always");
      assertThat(incomplete.exit()).isEqualTo(1);
      assertThat(incomplete.output()).contains("replay or discard");
      assertThat(h.cli("approvals", "allow", "1").exit()).isEqualTo(2);

      Harness.CliResult allowed = h.cli("approvals", "allow", "1", "--always", "--replay");
      assertThat(allowed.exit()).as(allowed.output()).isZero();
      assertThat(allowed.output()).contains("Granted 'approval-1'");
      Harness.await("the missed mail to be archived", () -> h.names(".Archive/cur").size() == 2);

      assertThat(Files.readString(h.home.resolve("policy.toml")))
          .contains("id = \"approval-1\"")
          .contains("folder = { equals = \"Archive\" }");
      assertThat(h.cli("grants").output())
          .contains("approval-1  layer ruleset may call mail.move")
          .contains("folder: equals = \"Archive\"");
      assertThat(h.cli("approvals").output()).contains("ALLOWED_ALWAYS").doesNotContain("Missed");

      // new mail needs no question any more
      h.deliver("3.third", "three");
      Harness.await("the third mail", () -> h.names(".Archive/cur").size() == 3);

      // a revoked grant makes the rule ask again
      assertThat(h.cli("grants", "revoke", "approval-1").exit()).isZero();
      h.deliver("4.fourth", "four");
      Harness.await("a new request", () -> h.cli("approvals").output().contains("#2"));
      assertThat(h.names(".Archive/cur")).hasSize(3);
      assertThat(h.cli("grants", "revoke", "approval-1").exit()).isEqualTo(1);

      // the rule stays disabled across a restart until it is answered
      assertThat(h.cli("approvals", "deny", "2").output()).contains("Refused");
      assertThat(h.cli("stop").exit()).isZero();
      Harness.await("the daemon to stop", () -> !Files.exists(h.socket()));
      assertThat(h.cli("start").exit()).isZero();
      assertThat(h.cli("approvals").output())
          .contains("#2  DENIED")
          .contains("refused in request #2");

      String audit = h.cli("audit", "-n", "50").output();
      assertThat(audit)
          .contains("GRANT")
          .contains("\"action\":\"added\"")
          .contains("\"action\":\"revoked\"")
          .contains("allowed always #1")
          .contains("denied #2");
      assertThat(h.cli("stop").exit()).isZero();
    }
  }
}
