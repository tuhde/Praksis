// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static it.uhde.praksis.integration.DoneCriterionIT.deleteTree;
import static it.uhde.praksis.integration.DoneCriterionIT.rule;
import static it.uhde.praksis.integration.DoneCriterionIT.test;
import static it.uhde.praksis.integration.DoneCriterionIT.tests;
import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The API that a frontend uses for the engine screens, asked through the socket of a running
 * daemon: what changed, the ruleset in use, the layers, the checks, and the proposals.
 */
class ScreensApiIT {
  @Test
  void aFrontendCanShowAndOperateTheEngineScreens() throws Exception {
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
            """
                .formatted(mail.url(), mine.url(), h.mail));
        h.grantMoves(Map.of("mail", List.of("Lists"), "mine", List.of("VIP", "Lists")));
        assertThat(h.cli("layers", "update").exit()).isZero();
        Process daemon = h.startDaemon();

        // what changed: everything at first, then only what happens, and the call waits for it
        Response.ChangeList first = (Response.ChangeList) h.ask(new Request.Changes(0, 0));
        assertThat(first.topics()).containsExactly("events", "log", "approvals", "shadow", "audit");
        AtomicReference<Response> waited = new AtomicReference<>();
        Thread waiter =
            Thread.ofVirtual()
                .start(
                    () -> {
                      try {
                        waited.set(h.ask(new Request.Changes(first.counter(), 20_000)));
                      } catch (Exception e) {
                        throw new IllegalStateException(e);
                      }
                    });
        Thread.sleep(200);
        assertThat(waited.get()).isNull(); // nothing has happened yet
        h.deliverWithHeaders(
            "1.a", "From: a@l.example", "List-Id: <dev.l.example>", "Subject: hello");
        waiter.join(15_000);
        Response.ChangeList changed = (Response.ChangeList) waited.get();
        assertThat(changed.topics()).contains("events");
        // the answer comes with the first change; the log follows, and asking again gets it
        Harness.await(
            "the log to change",
            () ->
                ((Response.ChangeList) h.ask(new Request.Changes(first.counter(), 0)))
                    .topics()
                    .contains("log"));

        // the ruleset in use, with where it comes from
        Response.EffectiveView effective = (Response.EffectiveView) h.ask(new Request.Effective());
        assertThat(effective.ruleset()).isEqualTo("praksis-mail+myPraksis");
        assertThat(effective.pinsChanged()).isFalse();
        assertThat(effective.rules())
            .extracting(Response.EffectiveView.Rule::id)
            .containsExactly("05.vip", "mail.20-lists");
        assertThat(effective.rules().get(0).owner()).isEqualTo("mine");
        assertThat(effective.rules().get(0).who()).contains("last changed by Test");

        Response.LayerList layers = (Response.LayerList) h.ask(new Request.Layers());
        assertThat(layers.layers())
            .extracting(Response.LayerList.Layer::name)
            .containsExactly("mail", "mine");
        assertThat(layers.layers().get(1).aiMaintained()).isTrue();
        assertThat(layers.layers().get(1).pinned()).hasSize(40);

        Response.Verification now =
            (Response.Verification) h.ask(new Request.Verify(false, 0, false, null));
        assertThat(now.candidate()).isFalse();
        assertThat(now.errors()).isZero();
        assertThat(now.tier()).isNull();

        Response.Lines dry = (Response.Lines) h.ask(new Request.LayersUpdate(true, false, null));
        assertThat(dry.ok()).isTrue();
        assertThat(dry.lines()).anyMatch(l -> l.contains("Dry run"));

        // proposals: show, check, approve at the commit that was looked at
        mine.branch("ai-new");
        String tip =
            mine.commit(
                "A rule for announcements\n\nPraksis-Author: ai",
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

        Response.ProposalList list = (Response.ProposalList) h.ask(new Request.Proposals());
        assertThat(list.proposals())
            .extracting(Response.ProposalList.Proposal::name)
            .containsExactlyInAnyOrder("mine@ai-new", "mine@ai-wide");
        Response.ProposalList.Proposal proposal =
            list.proposals().stream().filter(p -> p.branch().equals("ai-new")).findFirst().get();
        assertThat(proposal.commits().get(0).authorKind()).isEqualTo("ai");
        assertThat(proposal.commits().get(0).subject()).isEqualTo("A rule for announcements");

        Response.ProposalDetail detail =
            (Response.ProposalDetail) h.ask(new Request.ProposalShow("mine@ai-new"));
        assertThat(detail.verification().candidate()).isTrue();
        assertThat(detail.verification().errors()).isZero();
        assertThat(detail.verification().tier()).isEqualTo("SHADOW");
        assertThat(detail.verification().decisions())
            .extracting(Response.Verification.Decision::rule)
            .containsExactly("06.lists");
        assertThat(detail.verification().replay()).isNotNull();

        Response.Verification wideCheck =
            (Response.Verification)
                h.ask(new Request.Verify(false, 0, false, Map.of("mine", "@" + wide)));
        assertThat(wideCheck.errors()).isPositive();
        assertThat(wideCheck.findings())
            .anyMatch(f -> f.check().equals("access") && f.message().contains("Trash"));

        Response.Lines stale =
            (Response.Lines) h.ask(new Request.ProposalApprove("mine@ai-new", "0123456789", false));
        assertThat(stale.ok()).isFalse();
        Response.Lines refused =
            (Response.Lines)
                h.ask(new Request.ProposalApprove("mine@ai-wide", wide.substring(0, 10), false));
        assertThat(refused.ok()).isFalse();
        Response.Lines approved =
            (Response.Lines)
                h.ask(new Request.ProposalApprove("mine@ai-new", tip.substring(0, 10), false));
        assertThat(approved.ok()).as(String.join("\n", approved.lines())).isTrue();
        assertThat(approved.lines()).anyMatch(l -> l.contains("Approved mine@ai-new"));
        assertThat(((Response.EffectiveView) h.ask(new Request.Effective())).pinsChanged())
            .isTrue();
        assertThat(((Response.LayerList) h.ask(new Request.Layers())).layers().get(1).pinned())
            .isEqualTo(tip);

        Response.Lines rejected =
            (Response.Lines) h.ask(new Request.ProposalReject("mine@ai-wide", "not that folder"));
        assertThat(rejected.ok()).isTrue();
        assertThat(((Response.ProposalList) h.ask(new Request.Proposals())).proposals()).isEmpty();
        assertThat(h.ask(new Request.ProposalShow("mail@main"))).isInstanceOf(Response.Error.class);

        h.stop(daemon);
      } finally {
        deleteTree(repos);
      }
    }
  }
}
