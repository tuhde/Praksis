// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * M4's done criterion: the verifier rejects a deliberately broken change and a deliberately
 * over-permissive change, and lets a good one through. The changes are branches of the private
 * layer's repository, the way an AI would propose them, checked with {@code verify --layer} before
 * anything is merged or pinned.
 */
class DoneCriterionIT {
  static String rule(String id, String filter, String folder) {
    return """
        id = "%s"
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
        .formatted(id, filter, folder);
  }

  /** A test that fires the rule with this mail and expects a move to the folder, or nothing. */
  static String test(String name, String from, String listId, String folder) {
    return """

        [[test]]
        name = "%s"
        [test.event]
        type = "mail.received"
        data = { message = "m1", from = "%s", list_id = "%s", x_spam_flag = "no" }
        %s
        """
        .formatted(
            name,
            from,
            listId,
            folder == null
                ? ""
                : "[[test.call]]\ncapability = \"mail.move\"\nargs = { folder = \""
                    + folder
                    + "\" }");
  }

  static String tests(String... tests) {
    return "format_version = 1\n" + String.join("\n", tests);
  }

  private static final String VIP_FILTER = "event.data.from.contains('boss@example.org')";
  private static final String LISTS_FILTER = "event.data.list_id != ''";

  private static final String PATCH =
      """
      [[change]]
      path = "trigger.filter"
      expect = "event.data.list_id != ''"
      value = "event.data.list_id != '' && !event.data.list_id.contains('important')"
      """;

  static void deleteTree(Path dir) throws Exception {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(f);
      }
    }
  }

  @Test
  void aBrokenAndAnOverPermissiveChangeAreRejectedAndAGoodOneIsNot() throws Exception {
    try (Harness h = new Harness()) {
      Path repos = Files.createTempDirectory("pkr");
      try {
        Repo mail = new Repo(repos.resolve("mail"));
        mail.commit(
            "mail",
            "ruleset.toml",
            "format_version = 1\nname = \"praksis-mail\"\n",
            "rules/mail.20-lists.toml",
            rule("mail.20-lists", LISTS_FILTER, "Lists"),
            "tests/mail.20-lists.toml",
            tests(test("a list mail goes to Lists", "a@l.example", "dev.l.example", "Lists")));
        Repo mine = new Repo(repos.resolve("mine"));
        mine.commit(
            "mine",
            "ruleset.toml",
            "format_version = 1\nname = \"myPraksis\"\n",
            "rules/05.vip.toml",
            rule("05.vip", VIP_FILTER, "VIP"),
            "patch/mail.20-lists.toml",
            PATCH,
            "tests/05.vip.toml",
            tests(test("the boss's mail is a VIP", "boss@example.org", "", "VIP")),
            "tests/mail.20-lists.toml",
            tests(
                test("a list mail goes to Lists", "a@l.example", "dev.l.example", "Lists"),
                test(
                    "an important list is left alone",
                    "a@l.example",
                    "important.l.example",
                    null)));
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
        assertThat(h.cli("layers", "update").exit()).isZero();

        Harness.CliResult now = h.cli("verify");
        assertThat(now.exit()).as(now.output()).isZero();
        assertThat(now.output()).contains("Ruleset praksis-mail+myPraksis (2 rules): 0 error(s)");

        // broken: the filter reads a field that mail does not have
        mine.branch("ai-broken");
        mine.commit(
            "broken",
            "rules/05.vip.toml",
            rule("05.vip", "event.data.sender.contains('boss@example.org')", "VIP"));
        mine.checkout("main");
        Harness.CliResult broken = h.cli("verify", "--layer", "mine=@ai-broken");
        assertThat(broken.exit()).as(broken.output()).isEqualTo(1);
        assertThat(broken.output()).containsPattern("ERROR +validate +rules/05\\.vip\\.toml");

        // over-permissive: valid, tested, and it files mail into a folder nobody granted
        mine.branch("ai-wide");
        mine.commit(
            "wide",
            "rules/06.trash.toml",
            rule("06.trash", "event.data.from.contains('spam.example')", "Trash"),
            "tests/06.trash.toml",
            tests(test("spam goes to Trash", "x@spam.example", "", "Trash")));
        mine.checkout("main");
        Harness.CliResult wide = h.cli("verify", "--layer", "mine=@ai-wide");
        assertThat(wide.exit()).as(wide.output()).isEqualTo(1);
        assertThat(wide.output())
            .doesNotContain("coverage")
            .containsPattern("ERROR +access +06\\.trash: new access that no grant covers")
            .contains("mine: mail.move folder=\"Trash\"");

        // untested: valid and within the grants, but nothing shows what it does
        mine.branch("ai-untested");
        mine.commit(
            "untested",
            "rules/06.lists.toml",
            rule("06.lists", "event.data.list_id != ''", "Lists"));
        mine.checkout("main");
        Harness.CliResult untested = h.cli("verify", "--layer", "mine=@ai-untested");
        assertThat(untested.exit()).as(untested.output()).isEqualTo(1);
        assertThat(untested.output())
            .containsPattern("ERROR +coverage +rules/06\\.lists\\.toml: no test of layer 'mine'");

        // good: within the grants, with a test
        mine.branch("ai-good");
        mine.commit(
            "good",
            "rules/06.lists.toml",
            rule("06.lists", "event.data.list_id.contains('announce')", "Lists"),
            "tests/06.lists.toml",
            tests(test("announcements go to Lists", "a@l.example", "announce.l.example", "Lists")));
        mine.checkout("main");
        Harness.CliResult good = h.cli("verify", "--layer", "mine=@ai-good");
        assertThat(good.exit()).as(good.output()).isZero();
        assertThat(good.output())
            .contains("Candidate praksis-mail+myPraksis (3 rules): 0 error(s)");

        // none of the checks changed what runs
        assertThat(h.cli("verify").output()).contains("(2 rules): 0 error(s)");
      } finally {
        deleteTree(repos);
      }
    }
  }
}
