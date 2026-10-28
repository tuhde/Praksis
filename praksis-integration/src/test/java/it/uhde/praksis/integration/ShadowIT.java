// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Shadow mode against the runtime image: a changed rule and a new rule are observed and do nothing
 * while the older version keeps running, until a person promotes them.
 */
class ShadowIT {
  @Test
  void aChangedAndANewRuleAreObservedUntilTheyArePromoted() throws Exception {
    try (Harness h = new Harness()) {
      Files.writeString(
          h.home.resolve("praksis.toml"),
          """
          format_version = 1
          [module.maildir]
          root = '%s'
          poll_interval_ms = 20
          [shadow]
          enabled = true
          trial_days = 0
          min_events = 2
          """
              .formatted(h.mail));
      h.grantMoves(Map.of("ruleset", List.of("Archive", "Spam", "Lists")));

      // the first start takes the rules in use as live
      Process daemon = h.startDaemon();
      assertThat(h.cli("shadow").output()).contains("No rule is on trial.");
      h.deliver("1.a", "one");
      Harness.await("the first mail to be archived", () -> h.names(".Archive/cur").size() == 1);
      h.stop(daemon);

      // a changed rule and a new rule
      Files.writeString(
          h.home.resolve("ruleset/rules/mail.archive.toml"),
          Harness.RULE.replace("Archive", "Spam"));
      Files.writeString(
          h.home.resolve("ruleset/rules/mail.zz-extra.toml"),
          Harness.RULE.replace("mail.archive", "mail.zz-extra").replace("Archive", "Lists"));
      daemon = h.startDaemon();
      String start = h.cli("shadow").output();
      assertThat(start)
          .contains("mail.archive  (a changed rule: the older version runs meanwhile)")
          .contains("mail.zz-extra  (a new rule)")
          .contains("needs 2 more event(s)");

      h.deliver("2.b", "two");
      h.deliver("3.c", "three");
      Harness.await("both mails to be archived", () -> h.names(".Archive/cur").size() == 3);
      // the older version did the work; the rules on trial did nothing
      assertThat(h.names(".Spam/cur")).isEmpty();
      assertThat(h.names(".Lists/cur")).isEmpty();
      Harness.await(
          "both trials to have seen both mails",
          () -> h.cli("shadow").output().split("seen with 2 event").length == 3);

      String seen = h.cli("shadow").output();
      assertThat(seen)
          .contains("ready to promote")
          .containsPattern("would call   mail\\.move folder=Spam message=\\S+")
          .containsPattern("would call   mail\\.move folder=Lists message=\\S+")
          .contains("really done  mail.archive: OK");
      assertThat(h.log()).extracting(l -> l.ruleId()).doesNotContain("mail.zz-extra");

      // promote the changed rule; the new rule stays on trial
      Harness.CliResult promoted = h.cli("shadow", "promote", "mail.archive");
      assertThat(promoted.exit()).as(promoted.output()).isZero();
      assertThat(promoted.output()).contains("Promoted mail.archive");
      assertThat(h.cli("shadow", "promote", "mail.archive").exit()).isEqualTo(1);
      h.deliver("4.d", "four");
      Harness.await(
          "the fourth mail to be filed by the new version", () -> h.names(".Spam/cur").size() == 1);
      assertThat(h.names(".Lists/cur")).isEmpty();
      String after = h.cli("shadow").output();
      assertThat(after).contains("mail.zz-extra").doesNotContain("mail.archive  (");

      // a restart keeps what was decided
      h.stop(daemon);
      daemon = h.startDaemon();
      h.deliver("5.e", "five");
      Harness.await("the fifth mail to be filed", () -> h.names(".Spam/cur").size() == 2);
      assertThat(h.cli("shadow").output())
          .contains("mail.zz-extra")
          .doesNotContain("mail.archive  (");

      // starting the trial again forgets what was seen
      assertThat(h.cli("shadow", "reset", "mail.zz-extra").exit()).isZero();
      assertThat(h.cli("shadow").output()).contains("seen with 0 event(s)");
      h.stop(daemon);
    }
  }
}
