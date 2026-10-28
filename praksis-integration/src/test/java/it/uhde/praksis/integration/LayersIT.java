// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M2's done criterion: a public mail ruleset plus one private rule behaves as expected, and
 * survives an upstream update. Uses real git repositories, the real CLI and the real daemon.
 */
class LayersIT {
  private static final java.util.Map<String, java.util.List<String>> GRANTS =
      java.util.Map.of(
          "mail", java.util.List.of("Spam", "Lists", "Bulk"),
          "mine", java.util.List.of("VIP", "Lists"));

  private static final String HEADER = "format_version = 1\nname = \"%s\"\n";

  private static String rule(String id, String description, String filter, String folder) {
    return """
        id = "%s"
        description = "%s"
        effect = "local-write"
        needs = ["mail.move"]

        [trigger]
        kind = "event"
        type = "mail.received"
        filter = "%s"

        [[action]]
        id = "move"
        capability = "mail.move"
        args = { message = { cel = "event.data.message" }, folder = "%s" }

        [[action]]
        stop = true
        """
        .formatted(id, description, filter, folder);
  }

  private static final String SPAM_FILTER = "event.data.x_spam_flag.matches('(?i)^yes')";
  private static final String LISTS_FILTER = "event.data.list_id != ''";

  private static final String VIP =
      rule("05.vip", "VIP", "event.data.from.contains('boss@example.org')", "VIP");

  private static final String PATCH =
      """
      [[change]]
      path = "trigger.filter"
      expect = "event.data.list_id != ''"
      value = "event.data.list_id != '' && !event.data.list_id.contains('important')"
      """;

  private static void deleteTree(Path dir) throws Exception {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(f);
      }
    }
  }

  private static String lockfile(Harness h) throws Exception {
    return Files.readString(h.home.resolve("rulesets.lock"));
  }

  @Test
  void aPublicRulesetPlusAPrivateRuleSurvivesAnUpstreamUpdate() throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        Repo mail = new Repo(repos.resolve("mail"));
        mail.commit(
            "first",
            "ruleset.toml",
            HEADER.formatted("praksis-mail"),
            "rules/mail.10-spam.toml",
            rule("mail.10-spam", "Spam", SPAM_FILTER, "Spam"),
            "rules/mail.20-lists.toml",
            rule("mail.20-lists", "Lists", LISTS_FILTER, "Lists"));
        Repo mine = new Repo(repos.resolve("mine"));
        mine.commit(
            "mine",
            "ruleset.toml",
            HEADER.formatted("myPraksis"),
            "rules/05.vip.toml",
            VIP,
            "patch/mail.20-lists.toml",
            PATCH);
        Files.writeString(
            h.home.resolve("praksis.toml"),
            """
            format_version = 1

            [[layer]]
            name = "mail"
            git = "%s"
            ref = "main"

            [[layer]]
            name = "mine"
            git = "%s"
            ref = "main"

            [module.maildir]
            root = "%s"
            poll_interval_ms = 20
            """
                .formatted(mail.url(), mine.url(), h.mail));
        h.grantMoves(GRANTS);

        // nothing starts before the layers are pinned
        Harness.CliResult unpinned = h.cli("effective");
        assertThat(unpinned.exit()).isEqualTo(1);
        assertThat(unpinned.output()).contains("not pinned to a commit yet");

        Harness.CliResult first = h.cli("layers", "update");
        assertThat(first.exit()).as(first.output()).isZero();
        assertThat(first.output()).contains("The new ruleset is valid (3 rules).");
        assertThat(h.cli("effective").output())
            .contains("ruleset: praksis-mail+myPraksis (3 rules)")
            .contains("mail: add; mine: patch");

        // the layers behave as written
        Process daemon = h.startDaemon();
        h.deliverWithHeaders("1.vip", "From: boss@example.org", "X-Spam-Flag: YES", "Subject: a");
        h.deliverWithHeaders("2.spam", "From: x@spam.example", "X-Spam-Flag: yes", "Subject: b");
        h.deliverWithHeaders(
            "3.list", "From: a@l.example", "List-Id: <dev.l.example>", "Subject: c");
        h.deliverWithHeaders(
            "4.important", "From: a@l.example", "List-Id: <important.l.example>", "Subject: d");
        h.deliverWithHeaders("5.bulk", "From: s@example.com", "Precedence: bulk", "Subject: e");
        Harness.await("the first mails to be sorted", () -> h.names("new").size() == 2);
        assertThat(h.names(".VIP/cur")).containsExactly("1.vip:2,");
        assertThat(h.names(".Spam/cur")).containsExactly("2.spam:2,");
        assertThat(h.names(".Lists/cur")).containsExactly("3.list:2,");
        assertThat(h.names("new")).containsExactly("4.important", "5.bulk"); // no rule for them yet
        h.stop(daemon);

        // a compatible upstream update: a new rule, and another field of a patched rule changes
        String pinned = lockfile(h);
        mail.commit(
            "second",
            "rules/mail.30-bulk.toml",
            rule("mail.30-bulk", "Bulk", "event.data.precedence.matches('(?i)^bulk$')", "Bulk"),
            "rules/mail.20-lists.toml",
            rule("mail.20-lists", "Mailing lists", LISTS_FILTER, "Lists"));
        Harness.CliResult dry = h.cli("layers", "update", "--dry-run");
        assertThat(dry.exit()).as(dry.output()).isZero();
        assertThat(dry.output())
            .contains("  + mail.30-bulk (from mail)")
            .contains("  ~ mail.20-lists changed")
            .contains("Dry run");
        assertThat(lockfile(h)).isEqualTo(pinned);

        Harness.CliResult applied = h.cli("layers", "update");
        assertThat(applied.exit()).as(applied.output()).isZero();
        assertThat(lockfile(h)).isNotEqualTo(pinned);

        // the old pins are not needed to start: the upstream may be gone
        deleteTree(mail.dir);
        daemon = h.startDaemon();
        assertThat(h.status().rules()).isEqualTo(4);
        // mails that were handled before keep their outcome, a new rule does not reach back
        h.deliverWithHeaders("6.bulk", "From: s@example.com", "Precedence: bulk", "Subject: f");
        Harness.await("the new bulk mail to be sorted", () -> h.names(".Bulk/cur").size() == 1);
        assertThat(h.names(".Bulk/cur")).containsExactly("6.bulk:2,");
        assertThat(h.names("new")).containsExactly("4.important", "5.bulk");
        h.stop(daemon);
      } finally {
        deleteTree(repos);
      }
    }
  }

  @Test
  void anUpstreamChangeThatALayerWasNotWrittenForIsRefusedAndTheOldPinKeepsWorking()
      throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        Repo mail = new Repo(repos.resolve("mail"));
        mail.commit(
            "first",
            "ruleset.toml",
            HEADER.formatted("praksis-mail"),
            "rules/mail.20-lists.toml",
            rule("mail.20-lists", "Lists", LISTS_FILTER, "Lists"));
        Repo mine = new Repo(repos.resolve("mine"));
        mine.commit(
            "mine",
            "ruleset.toml",
            HEADER.formatted("myPraksis"),
            "patch/mail.20-lists.toml",
            PATCH);
        Files.writeString(
            h.home.resolve("praksis.toml"),
            """
            format_version = 1
            [[layer]]
            name = "mail"
            git = "%s"
            ref = "main"
            [[layer]]
            name = "mine"
            git = "%s"
            ref = "main"
            [module.maildir]
            root = "%s"
            poll_interval_ms = 20
            """
                .formatted(mail.url(), mine.url(), h.mail));
        h.grantMoves(GRANTS);
        assertThat(h.cli("layers", "update").exit()).isZero();
        String pinned = lockfile(h);

        // the field that myPraksis patches changes upstream
        mail.commit(
            "second",
            "rules/mail.20-lists.toml",
            rule(
                "mail.20-lists",
                "Lists",
                LISTS_FILTER + " && event.data.precedence != 'junk'",
                "Lists"));
        Harness.CliResult refused = h.cli("layers", "update");
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.output())
            .contains("Not updated")
            .contains("layer 'mine': patch/mail.20-lists.toml")
            .contains("has changed since this patch was written")
            .contains("The pins are unchanged.");
        assertThat(lockfile(h)).isEqualTo(pinned);

        // the daemon, killed and restarted, keeps working with the old pins
        Process daemon = h.startDaemon();
        h.kill(daemon);
        daemon = h.startDaemon();
        h.deliverWithHeaders(
            "1.list", "From: a@l.example", "List-Id: <dev.l.example>", "Subject: c");
        Harness.await("the list mail to be sorted", () -> h.names(".Lists/cur").size() == 1);
        h.stop(daemon);
      } finally {
        deleteTree(repos);
      }
    }
  }
}
