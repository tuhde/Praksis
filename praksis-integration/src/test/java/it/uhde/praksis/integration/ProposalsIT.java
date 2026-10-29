// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static it.uhde.praksis.integration.DoneCriterionIT.deleteTree;
import static it.uhde.praksis.integration.DoneCriterionIT.rule;
import static it.uhde.praksis.integration.DoneCriterionIT.test;
import static it.uhde.praksis.integration.DoneCriterionIT.tests;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The proposal workflow with real git repositories: branches of an AI-maintained layer are listed,
 * shown with the checks, approved only for the commit that was looked at, and pinned.
 */
class ProposalsIT {
  @Test
  void aBranchOfAnAiMaintainedLayerIsShownApprovedAndPinned() throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        Repo mail = new Repo(repos.resolve("mail"));
        mail.commit(
            "mail",
            "ruleset.toml",
            "format_version = 1\nname = \"praksis-mail\"\n",
            "rules/mail.20-lists.toml",
            rule("mail.20-lists", "event.data.list_id != ''", "Lists"),
            "tests/mail.20-lists.toml",
            tests(test("a list mail goes to Lists", "a@l.example", "dev.l.example", "Lists")));
        Repo mine = new Repo(repos.resolve("mine"));
        mine.commit(
            "mine",
            "ruleset.toml",
            "format_version = 1\nname = \"myPraksis\"\n",
            "rules/05.vip.toml",
            rule("05.vip", "event.data.from.contains('boss@example.org')", "VIP"),
            "tests/05.vip.toml",
            tests(test("the boss's mail is a VIP", "boss@example.org", "", "VIP")));
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
            ai_maintained = true
            [module.maildir]
            root = "%s"
            poll_interval_ms = 20
            [shadow]
            enabled = true
            trial_days = 0
            min_events = 1
            """
                .formatted(mail.url(), mine.url(), h.mail));
        h.grantMoves(Map.of("mail", List.of("Lists"), "mine", List.of("VIP", "Lists")));
        assertThat(h.cli("layers", "update").exit()).isZero();
        Process daemon = h.startDaemon(); // takes the rules in use as promoted
        h.stop(daemon);

        assertThat(h.cli("proposals").output()).contains("No proposals");

        // two branches by an AI
        mine.branch("ai-new");
        String tip =
            mine.commit(
                "A rule for announcements\n\nThey are read once a week.\n\nPraksis-Author: ai",
                "rules/06.lists.toml",
                rule("06.lists", "event.data.list_id.contains('announce')", "Lists"),
                "tests/06.lists.toml",
                tests(
                    test(
                        "announcements go to Lists",
                        "a@l.example",
                        "announce.l.example",
                        "Lists")));
        mine.checkout("main");
        mine.branch("ai-wide");
        String wide =
            mine.commit(
                "A rule for the trash",
                "rules/06.trash.toml",
                rule("06.trash", "event.data.from.contains('spam.example')", "Trash"),
                "tests/06.trash.toml",
                tests(test("spam goes to Trash", "x@spam.example", "", "Trash")));
        mine.checkout("main");

        String list = h.cli("proposals").output();
        assertThat(list)
            .contains("mine@ai-new  " + tip.substring(0, 10) + "  1 commit(s)")
            .contains("A rule for announcements")
            .contains("mine@ai-wide  " + wide.substring(0, 10));

        // an upstream layer takes no proposals
        Harness.CliResult upstream = h.cli("proposals", "show", "mail@main");
        assertThat(upstream.exit()).isEqualTo(1);
        assertThat(upstream.output()).contains("not an AI-maintained git layer");

        Harness.CliResult shown = h.cli("proposals", "show", "mine@ai-new");
        assertThat(shown.exit()).as(shown.output()).isZero();
        assertThat(shown.output())
            .contains("| They are read once a week.")
            .doesNotContain("Praksis-Author")
            .contains("(ai, not signed)")
            .contains("This change runs in shadow mode first")
            .contains("SHADOW    06.lists (new)")
            .contains(
                "To approve exactly this: praksis proposals approve mine@ai-new "
                    + tip.substring(0, 10));

        Harness.CliResult refused = h.cli("proposals", "show", "mine@ai-wide");
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.output())
            .contains("new access that no grant covers")
            .contains("It cannot be approved while there are errors.");
        Harness.CliResult notApproved =
            h.cli("proposals", "approve", "mine@ai-wide", wide.substring(0, 10));
        assertThat(notApproved.exit()).isEqualTo(1);
        assertThat(notApproved.output()).contains("Not approved: the checks found errors.");

        // approving needs the commit that was looked at
        Harness.CliResult stale = h.cli("proposals", "approve", "mine@ai-new", "0123456789");
        assertThat(stale.exit()).isEqualTo(1);
        assertThat(stale.output())
            .contains("Not approved: the branch is at " + tip.substring(0, 10));
        String before = Files.readString(h.home.resolve("rulesets.lock"));

        Harness.CliResult approved =
            h.cli("proposals", "approve", "mine@ai-new", tip.substring(0, 10));
        assertThat(approved.exit()).as(approved.output()).isZero();
        assertThat(approved.output())
            .contains("Approved mine@ai-new: layer 'mine' is pinned to " + tip.substring(0, 10))
            .contains("Merge the branch into 'main' and push it yourself");
        String pinned = Files.readString(h.home.resolve("rulesets.lock"));
        assertThat(pinned).isNotEqualTo(before).contains(tip);
        assertThat(h.cli("layers", "status").output()).contains("[AI-maintained]");

        // the rule is new, so it is on trial after the restart, and the decision is in the audit
        // log
        daemon = h.startDaemon();
        assertThat(h.cli("shadow").output()).contains("06.lists  (a new rule)");
        String audit = h.cli("audit", "-n", "50").output();
        assertThat(audit)
            .containsPattern("PROPOSAL +- +mine@ai-new ")
            .contains("\"decision\":\"approved\"")
            .contains("A rule for announcements")
            .containsPattern("RULE +06\\.lists ");
        h.stop(daemon);

        // decided branches are not listed again; the other one can be rejected
        String again = h.cli("proposals").output();
        assertThat(again).doesNotContain("mine@ai-new").contains("mine@ai-wide");
        assertThat(h.cli("proposals", "reject", "mine@ai-wide", "not", "that", "folder").exit())
            .isZero();
        assertThat(h.cli("proposals").output()).contains("No proposals");

        // nothing was merged: the next update follows main again
        assertThat(h.cli("layers", "update").exit()).isZero();
        assertThat(Files.readString(h.home.resolve("rulesets.lock")))
            .isNotEqualTo(pinned)
            .doesNotContain(tip);
      } finally {
        deleteTree(repos);
      }
    }
  }

  @Test
  void withoutShadowModeAnApprovalNeedsToSayItAcceptsThat() throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        Repo mine = new Repo(repos.resolve("mine"));
        mine.commit(
            "mine",
            "ruleset.toml",
            "format_version = 1\nname = \"myPraksis\"\n",
            "rules/05.vip.toml",
            rule("05.vip", "event.data.from.contains('boss@example.org')", "VIP"),
            "tests/05.vip.toml",
            tests(test("the boss's mail is a VIP", "boss@example.org", "", "VIP")));
        Files.writeString(
            h.home.resolve("praksis.toml"),
            """
            format_version = 1
            [[layer]]
            name = "mine"
            git = "%s"
            ref = "main"
            ai_maintained = true
            [module.maildir]
            root = "%s"
            poll_interval_ms = 20
            """
                .formatted(mine.url(), h.mail));
        h.grantMoves(Map.of("mine", List.of("VIP", "Lists")));
        assertThat(h.cli("layers", "update").exit()).isZero();
        mine.branch("ai-new");
        String tip =
            mine.commit(
                "A rule for announcements",
                "rules/06.lists.toml",
                rule("06.lists", "event.data.list_id.contains('announce')", "Lists"),
                "tests/06.lists.toml",
                tests(
                    test(
                        "announcements go to Lists",
                        "a@l.example",
                        "announce.l.example",
                        "Lists")));
        mine.checkout("main");

        Harness.CliResult refused =
            h.cli("proposals", "approve", "mine@ai-new", tip.substring(0, 10));
        assertThat(refused.exit()).isEqualTo(1);
        assertThat(refused.output()).contains("shadow mode is off");
        Harness.CliResult accepted =
            h.cli("proposals", "approve", "mine@ai-new", tip.substring(0, 10), "--without-shadow");
        assertThat(accepted.exit()).as(accepted.output()).isZero();
      } finally {
        deleteTree(repos);
      }
    }
  }
}
