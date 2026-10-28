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
 * Which changes need the user: only commits with a good signature by one of the user's keys count
 * as the user's, whatever their message says. Real git repositories, SSH signatures.
 */
class ApprovalTiersIT {
  private static final String VIP_FILTER = "event.data.from.contains('boss@example.org')";

  private static String run(Path dir, String... command) throws Exception {
    Process p =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true).start();
    String out = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as(String.join(" ", command) + "\n" + out).isZero();
    return out.strip();
  }

  @Test
  void onlyACommitSignedByTheUsersKeyIsTheUsers() throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        // the user's signing key, and git's list of the keys it trusts
        Path key = repos.resolve("key");
        run(repos, "ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString());
        String pub = Files.readString(repos.resolve("key.pub")).strip();
        String fingerprint = run(repos, "ssh-keygen", "-l", "-f", key + ".pub").split(" ")[1];
        Path allowed = repos.resolve("allowed_signers");
        Files.writeString(allowed, "test@example.org " + pub + "\n");
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
        mine.signWith(key); // the user signs their own layer
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
            [module.maildir]
            root = "%s"
            poll_interval_ms = 20
            """
                .formatted(mail.url(), mine.url(), h.mail));
        h.grantMoves(Map.of("mail", List.of("Lists"), "mine", List.of("VIP", "Lists")));
        Files.writeString(
            h.home.resolve("policy.toml"),
            Files.readString(h.home.resolve("policy.toml"))
                + "\n[trust]\nhuman_keys = [\""
                + fingerprint
                + "\"]\n");
        assertThat(h.cli("layers", "update").exit()).isZero();

        // what git can prove: the user's rule is the user's, the public one is nobody's in
        // particular
        String effective = h.cli("effective").output();
        assertThat(effective)
            .containsPattern(
                "(?s)05\\.vip.*last changed by Test <test@example.org> \\(human, signed by "
                    + java.util.regex.Pattern.quote(fingerprint))
            .containsPattern("(?s)mail\\.20-lists.*\\(unverified, not signed\\)");

        // an AI edit of the user's rule, claiming in its message to be a person's: needs approval
        mine.branch("ai-edit");
        mine.signWith(null);
        mine.commit(
            "Also the CEO\n\nSigned off by a human, honest.",
            "rules/05.vip.toml",
            rule("05.vip", VIP_FILTER + " || event.data.from.contains('ceo@example.org')", "VIP"),
            "tests/05.vip.toml",
            tests(test("the boss's mail is a VIP", "boss@example.org", "", "VIP")));
        mine.checkout("main");
        String edit = h.cli("verify", "--layer", "mine=@ai-edit").output();
        assertThat(edit)
            .contains("This change needs your approval:")
            .containsPattern(
                "APPROVAL +05\\.vip \\(changed\\): changes a rule you wrote, and the change is not signed by one of your keys \\(unverified\\)");

        // the same edit, signed by the user: only shadow mode
        mine.branch("human-edit");
        mine.signWith(key);
        mine.commit(
            "Also the CEO",
            "rules/05.vip.toml",
            rule("05.vip", VIP_FILTER + " || event.data.from.contains('ceo@example.org')", "VIP"),
            "tests/05.vip.toml",
            tests(test("the boss's mail is a VIP", "boss@example.org", "", "VIP")));
        mine.checkout("main");
        String human = h.cli("verify", "--layer", "mine=@human-edit").output();
        assertThat(human)
            .contains("This change runs in shadow mode first")
            .containsPattern("SHADOW +05\\.vip \\(changed\\)")
            .doesNotContain("needs your approval");

        // a new rule within the grants: shadow mode; one that needs an ungranted folder: approval
        mine.branch("ai-new");
        mine.signWith(null);
        mine.commit(
            "A rule for announcements",
            "rules/06.lists.toml",
            rule("06.lists", "event.data.list_id.contains('announce')", "Lists"),
            "tests/06.lists.toml",
            tests(test("announcements go to Lists", "a@l.example", "announce.l.example", "Lists")));
        mine.checkout("main");
        assertThat(h.cli("verify", "--layer", "mine=@ai-new").output())
            .contains("This change runs in shadow mode first")
            .containsPattern("SHADOW +06\\.lists \\(new\\)");

        mine.branch("ai-wide");
        mine.commit(
            "A rule for the trash",
            "rules/06.trash.toml",
            rule("06.trash", "event.data.from.contains('spam.example')", "Trash"),
            "tests/06.trash.toml",
            tests(test("spam goes to Trash", "x@spam.example", "", "Trash")));
        mine.checkout("main");
        assertThat(h.cli("verify", "--layer", "mine=@ai-wide").output())
            .contains("This change needs your approval:")
            .containsPattern("APPROVAL +06\\.trash \\(new\\): needs access that no grant covers");

        // the rules for approval are the user's to set
        Files.writeString(
            h.home.resolve("policy.toml"),
            Files.readString(h.home.resolve("policy.toml"))
                + "\n[approval]\nuser_rules = false\nshadow_first = false\n");
        String relaxed = h.cli("verify", "--layer", "mine=@ai-edit").output();
        assertThat(relaxed).contains("This change needs nothing more:");
      } finally {
        deleteTree(repos);
      }
    }
  }
}
