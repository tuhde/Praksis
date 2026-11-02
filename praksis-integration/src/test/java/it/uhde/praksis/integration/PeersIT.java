// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Two real daemons from the runtime image as peers on one machine, the way two identities are: the
 * daemon "work" offers invoices, the daemon "private" uses them. The person introduces the keys,
 * grants the peer a narrow read, accepts what it offers, and a rule of the private identity reads
 * the invoices of the work identity. Each side keeps its own audit log; when work is away the rule
 * does not fail, it waits, and the event is handled when work is back.
 */
class PeersIT {
  private static void append(Path file, String text) throws Exception {
    Files.writeString(file, (Files.exists(file) ? Files.readString(file) : "") + text);
  }

  private static void name(Harness h, String identity) throws Exception {
    Path file = h.home.resolve("praksis.toml");
    String text = Files.readString(file);
    Files.writeString(
        file,
        text.replace(
            "format_version = 1\n", "format_version = 1\nidentity = \"" + identity + "\"\n"));
  }

  /** How the daemon of h sees its peer "work" now. */
  private static String state(Harness h) throws Exception {
    var overview =
        (it.uhde.praksis.protocol.Response.PeerOverview)
            h.ask(new it.uhde.praksis.protocol.Request.Peers());
    return overview.peers().get(0).state();
  }

  private static String audit(Harness h) throws Exception {
    return h.cli("audit", "-n", "1000").output();
  }

  private static int count(String text, String what) {
    return (int) Pattern.compile(Pattern.quote(what)).matcher(text).results().count();
  }

  @Test
  void theIdentitiesIntroduceEachOtherGrantAcceptAndUseEachOthersFunctions() throws Exception {
    try (Harness work = new Harness();
        Harness me = new Harness()) {
      // before the first table of the file, or it would be a setting of the module
      name(work, "work");
      name(me, "private");

      // the introduction: each side lists the key of the other (the socket only where it connects)
      String workKey = work.cli("peers", "show-key").output().lines().findFirst().orElseThrow();
      String myKey = me.cli("peers", "show-key").output().lines().findFirst().orElseThrow();
      assertThat(
              me.cli("peers", "add", "work", workKey, "--socket", work.socket().toString()).exit())
          .isZero();
      assertThat(work.cli("peers", "add", "private", myKey).exit()).isZero();

      // work offers invoices to private, to read and query only
      Files.createDirectories(work.home.resolve("ruleset/entities"));
      Files.writeString(
          work.home.resolve("ruleset/entities/invoice.toml"),
          """
          format_version = 1
          id = "invoice"
          title = "Invoice"

          [[field]]
          name = "number"
          type = "string"
          required = true
          unique = true
          """);
      append(
          work.home.resolve("policy.toml"),
          """

          [[peer_grant]]
          id = "private-reads-invoices"
          peer = "private"
          capability = "entity.query"
          [peer_grant.args]
          type = { equals = "invoice" }

          [[peer_grant]]
          id = "private-reads-one-invoice"
          peer = "private"
          capability = "entity.read"
          [peer_grant.args]
          type = { equals = "invoice" }
          """);
      Process workDaemon = work.startDaemon();
      assertThat(work.status().identity()).isEqualTo("work");
      assertThat(work.cli("entities", "create", "invoice", "--set", "number=INV-1").exit())
          .isZero();

      // private connects, sees what it may use, and has to accept it
      Process myDaemon = me.startDaemon();
      assertThat(me.status().identity()).isEqualTo("private");
      Harness.await(
          "the requests of work",
          () -> me.cli("peers", "requests").output().contains("capability:entity.query"));
      Harness.CliResult requests = me.cli("peers", "requests");
      Matcher m = Pattern.compile("#(\\d+)\\s+PENDING").matcher(requests.output());
      List<String> numbers = m.results().map(r -> r.group(1)).toList();
      assertThat(numbers).hasSize(2);
      for (String n : numbers) {
        assertThat(me.cli("peers", "answer", n, "approve").exit()).isZero();
      }
      Harness.CliResult catalog = me.cli("peers", "catalog", "work");
      assertThat(catalog.exit()).as(catalog.output()).isZero();
      assertThat(catalog.output())
          .contains("capability  work/entity.query")
          .contains("capability  work/entity.read")
          .contains("type        work/invoice")
          // nothing else is offered: not delete, not create
          .doesNotContain("work/entity.delete")
          .doesNotContain("work/entity.create");

      // a person at private calls it; the grants of work decide
      Harness.CliResult called =
          me.cli("peers", "call", "work", "entity.query", "--arg", "type=invoice");
      assertThat(called.exit()).as(called.output()).isZero();
      assertThat(called.output()).contains("INV-1");
      Harness.CliResult refused =
          me.cli("peers", "call", "work", "entity.query", "--arg", "type=salary");
      assertThat(refused.exit()).isNotZero();
      assertThat(refused.output()).contains("has no grant");
      assertThat(audit(work)).contains("peer:private").contains("entity.query");

      // a rule of private reads the invoices of work (the functions of a peer are known to the
      // rules at the next start)
      work.stop(workDaemon);
      workDaemon = work.startDaemon();
      me.stop(myDaemon);
      Files.deleteIfExists(me.home.resolve("ruleset/rules/mail.archive.toml"));
      Files.writeString(
          me.home.resolve("ruleset/rules/peer.invoices.toml"),
          """
          id = "peer.invoices"
          effect = "read-only"
          needs = ["work/entity.query"]

          [trigger]
          kind = "event"
          type = "mail.received"

          [[action]]
          id = "q"
          capability = "work/entity.query"
          args = { type = "invoice" }
          """);
      Files.createDirectories(me.home.resolve("ruleset/tests"));
      Files.writeString(
          me.home.resolve("ruleset/tests/peer.invoices.toml"),
          """
          format_version = 1

          [[test]]
          name = "the invoices of work are asked for"
          [test.event]
          type = "mail.received"
          data = { message = "/some/mail" }
          [[test.call]]
          capability = "work/entity.query"
          args = { type = "invoice" }
          """);
      append(
          me.home.resolve("policy.toml"),
          """

          [[grant]]
          id = "work-invoices"
          layer = "ruleset"
          capability = "work/entity.query"
          [grant.args]
          type = { equals = "invoice" }
          """);
      myDaemon = me.startDaemon();
      Harness.CliResult verified = me.cli("verify");
      assertThat(verified.exit()).as(verified.output()).isZero();

      me.deliver("m1", "first");
      Harness.await("the rule to ask work", () -> count(audit(me), "work/entity.query") >= 1);
      assertThat(count(audit(work), "peer:private")).isGreaterThanOrEqualTo(2);

      // work is away: the rule neither fails nor is disabled, the event waits
      work.stop(workDaemon);
      Harness.await("private to notice that work is away", () -> state(me).equals("unreachable"));
      me.deliver("m2", "second");
      Harness.await(
          "the event to be recorded as missed",
          () ->
              me.log().stream()
                  .anyMatch(l -> l.message().startsWith("skipped, recorded as missed")));
      assertThat(me.cli("approvals").output()).doesNotContain("Disabled rules");

      // work is back: private finds it again by itself, and the event is handled
      work.startDaemon();
      int before = count(audit(me), "work/entity.query");
      try {
        Harness.await(
            "the missed event to be handled once work is back",
            () -> {
              me.cli("approvals", "replay", "peer.invoices");
              return count(audit(me), "work/entity.query") > before;
            });
      } catch (AssertionError e) {
        throw new AssertionError(
            e.getMessage()
                + "\nstate: "
                + state(me)
                + "\nreplay: "
                + me.cli("approvals", "replay", "peer.invoices").output()
                + "\nlog:\n"
                + me.cli("log", "-n", "30").output()
                + "\ndaemon log:\n"
                + me.daemonLog(),
            e);
      }
      Harness.await("private to see work again", () -> state(me).equals("connected"));
    }
  }
}
