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
 * M5's done criterion, the whole loop with real repositories and a running daemon: a change that an
 * agent made on a branch and that passes the verifier runs in shadow mode and is promoted after the
 * trial, and a change that needs approval waits for the user and changes nothing meanwhile.
 */
class AiMaintenanceIT {
  private static final String VIP_FILTER = "event.data.from.contains('boss@example.org')";

  private static String run(Path dir, String... command) throws Exception {
    Process p =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as(String.join(" ", command) + "\n" + out).isZero();
    return out.strip();
  }

  @Test
  void aPassingChangeRunsInShadowModeAndIsPromotedAndOneThatNeedsApprovalWaits() throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        // the user signs their own layer; git trusts the key
        Path key = repos.resolve("key");
        run(repos, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString());
        String fingerprint = run(repos, "ssh-keygen", "-l", "-f", key + ".pub").split(" ")[1];
        Path allowed = repos.resolve("allowed_signers");
        Files.writeString(
            allowed, "test@example.org " + Files.readString(repos.resolve("key.pub")));
        Files.writeString(h.gitConfig(), "[gpg \"ssh\"]\n\tallowedSignersFile = " + allowed + "\n");

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
        mine.signWith(key);
        mine.commit(
            "mine",
            "ruleset.toml",
            "format_version = 1\nname = \"myPraksis\"\n",
            "rules/05.vip.toml",
            rule("05.vip", VIP_FILTER, "VIP"),
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
            min_events = 2
            """
                .formatted(mail.url(), mine.url(), h.mail));
        h.grantMoves(
            Map.of(
                "mail", List.of("Lists"),
                "mine", List.of("VIP", "Lists", "Announce")));
        Files.writeString(
            h.home.resolve("policy.toml"),
            Files.readString(h.home.resolve("policy.toml"))
                + "\n[trust]\nhuman_keys = [\""
                + fingerprint
                + "\"]\n");
        assertThat(h.cli("layers", "update").exit()).isZero();
        h.stop(h.startDaemon()); // the rules in use are taken as promoted

        // 1. an agent proposes a new rule on a branch (not signed, says so in its message)
        mine.branch("ai-announce");
        mine.signWith(null);
        String tip =
            mine.commit(
                "Announcements get their own folder\n\nThey are read once a week.\n\n"
                    + "Praksis-Author: ai",
                "rules/06.announce.toml",
                rule("06.announce", "event.data.list_id.contains('announce')", "Announce"),
                "tests/06.announce.toml",
                tests(
                    test(
                        "announcements go to Announce",
                        "a@l.example",
                        "announce.l.example",
                        "Announce")));
        mine.checkout("main");
        Harness.CliResult shown = h.cli("proposals", "show", "mine@ai-announce");
        assertThat(shown.exit()).as(shown.output()).isZero();
        assertThat(shown.output()).contains("SHADOW    06.announce (new)");
        String short10 = tip.substring(0, 10);
        assertThat(h.cli("proposals", "approve", "mine@ai-announce", short10).exit()).isZero();

        // 2. after the restart the rule is observed and the old behaviour continues
        Process daemon = h.startDaemon();
        h.deliverWithHeaders(
            "1.a", "From: a@l.example", "List-Id: <announce.l.example>", "Subject: x");
        h.deliverWithHeaders(
            "2.b", "From: a@l.example", "List-Id: <announce.l.example>", "Subject: y");
        Harness.await(
            "both mails to be filed by the live rule", () -> h.names(".Lists/cur").size() == 2);
        assertThat(h.names(".Announce/cur")).isEmpty();
        Harness.await(
            "the trial to be over", () -> h.cli("shadow").output().contains("ready to promote"));
        String trial = h.cli("shadow").output();
        assertThat(trial)
            .contains("06.announce  (a new rule)")
            .contains("seen with 2 event(s): would call for 2")
            .containsPattern("would call   mail\\.move folder=Announce message=\\S+")
            .contains("really done  mail.20-lists: OK");

        // 3. the user promotes it; from now on it works
        assertThat(h.cli("shadow", "promote", "06.announce").exit()).isZero();
        h.deliverWithHeaders(
            "3.c", "From: a@l.example", "List-Id: <announce.l.example>", "Subject: z");
        Harness.await(
            "the third mail to go to Announce", () -> h.names(".Announce/cur").size() == 1);
        assertThat(h.names(".Lists/cur")).hasSize(2);
        assertThat(h.cli("shadow").output()).contains("No rule is on trial.");

        // 4. an agent changes the user's own rule: this needs the user, and nothing changes
        // meanwhile
        String pin = Files.readString(h.home.resolve("rulesets.lock"));
        mine.checkout("ai-announce");
        mine.branch("ai-edit");
        mine.commit(
            "Also the CEO",
            "rules/05.vip.toml",
            rule("05.vip", VIP_FILTER + " || event.data.from.contains('ceo@example.org')", "VIP"),
            "tests/05.vip.toml",
            tests(test("the boss's mail is a VIP", "boss@example.org", "", "VIP")));
        mine.checkout("main");
        Harness.CliResult edit = h.cli("proposals", "show", "mine@ai-edit");
        assertThat(edit.exit()).as(edit.output()).isZero();
        assertThat(edit.output())
            .contains("This change needs your approval:")
            .containsPattern("APPROVAL +05\\.vip \\(changed\\): changes a rule you wrote");
        h.deliverWithHeaders("4.d", "From: ceo@example.org", "Subject: hello");
        h.deliverWithHeaders("5.e", "From: boss@example.org", "Subject: hello");
        Harness.await("the boss's mail to be filed", () -> h.names(".VIP/cur").size() == 1);
        Thread.sleep(300);
        assertThat(h.names(".VIP/cur")).containsExactly("5.e:2,"); // the CEO is not a VIP yet
        assertThat(h.names("new")).contains("4.d");
        assertThat(Files.readString(h.home.resolve("rulesets.lock"))).isEqualTo(pin);
        h.stop(daemon);
      } finally {
        deleteTree(repos);
      }
    }
  }
}
