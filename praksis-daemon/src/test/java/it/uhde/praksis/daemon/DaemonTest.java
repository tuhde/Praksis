// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Runs a real daemon with the real Maildir module and the real expression layer. */
class DaemonTest {
  private static final String RULE =
      """
      id = "mail.archive"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Archive" }
      """;

  @TempDir Path home;

  private Path mail;

  @BeforeEach
  void setUp() throws Exception {
    mail = home.resolve("mail");
    for (String d : new String[] {"new", "cur", "tmp"}) {
      Files.createDirectories(mail.resolve(d));
    }
    Path moduleDir = Files.createDirectories(home.resolve("modules/maildir"));
    Files.copy(
        Path.of("../modules/praksis-module-maildir/module.toml"), moduleDir.resolve("module.toml"));
    pack(
        Path.of("../modules/praksis-module-maildir/target/classes"),
        moduleDir.resolve("maildir.jar"));
    ModuleLoader.lock(home.resolve("modules"), home.resolve("modules.lock"));

    write(
        "praksis.toml",
        "format_version = 1\n[module.maildir]\nroot = '"
            + mail
            + "'\n"
            + "poll_interval_ms = 20\n");
    write(
        "policy.toml",
        """
        format_version = 1

        [[grant]]
        id = "archive"
        layer = "ruleset"
        capability = "mail.move"
        [grant.args]
        folder = { equals = "Archive" }
        """);
    write("ruleset/ruleset.toml", "format_version = 1\nname = \"mail\"\n");
    write("ruleset/rules/mail.archive.toml", RULE);
  }

  private void write(String relative, String content) throws IOException {
    Path file = home.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private static void pack(Path source, Path jar) throws IOException {
    if (Files.isRegularFile(source)) {
      Files.copy(source, jar);
      return;
    }
    try (OutputStream out = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(out);
        Stream<Path> files = Files.walk(source)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        jos.putNextEntry(new JarEntry(source.relativize(f).toString().replace('\\', '/')));
        jos.write(Files.readAllBytes(f));
        jos.closeEntry();
      }
    }
  }

  private final java.util.List<Daemon> started = new java.util.ArrayList<>();

  private Daemon start() throws Exception {
    Daemon daemon = Daemon.start(DaemonConfig.load(home));
    started.add(daemon);
    return daemon;
  }

  @AfterEach
  void closeDaemons() {
    started.forEach(Daemon::close);
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + 10_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(10);
    }
  }

  private boolean logHas(java.util.function.Predicate<Response.LogLines.Line> test) {
    try {
      return ask(new Request.Log(10)) instanceof Response.LogLines l
          && l.lines().stream().anyMatch(test);
    } catch (IOException e) {
      return false;
    }
  }

  private Response ask(Request request) throws IOException {
    try (Client client = Client.connect(home.resolve("praksis.sock"))) {
      return client.send(request);
    }
  }

  @Test
  void aMailDroppedIntoTheMaildirIsMovedByTheRule() throws Exception {
    Files.writeString(mail.resolve("new/1700000000.M1P2.host"), "Subject: hi\n\nbody\n");
    {
      Daemon daemon = start();
      Path moved = mail.resolve(".Archive/cur/1700000000.M1P2.host:2,");
      await(() -> Files.exists(moved));
      assertThat(mail.resolve("new/1700000000.M1P2.host")).doesNotExist();

      assertThat(ask(new Request.Status()))
          .isInstanceOfSatisfying(
              Response.StatusReport.class,
              s -> {
                assertThat(s.ruleset()).isEqualTo("mail");
                assertThat(s.rules()).isEqualTo(1);
                assertThat(s.modules()).containsExactly("maildir");
                assertThat(s.pid()).isEqualTo(ProcessHandle.current().pid());
              });
      await(() -> logHas(x -> "OK".equals(x.outcome())));
      daemon.close();
    }
    assertThat(home.resolve("praksis.sock")).doesNotExist();
  }

  @Test
  void withoutAGrantTheRuleIsDisabledAndTheMailStays() throws Exception {
    Files.delete(home.resolve("policy.toml"));
    Files.writeString(mail.resolve("new/1700000000.M1P2.host"), "Subject: hi\n\nbody\n");
    start();
    await(
        () ->
            logHas(
                x ->
                    "DENIED".equals(x.outcome())
                        && x.message().contains("layer 'ruleset' has no grant for mail.move")));
    // mail that arrives after the rule was disabled is left alone and logged as skipped
    Files.writeString(mail.resolve("new/1700000001.M1P2.host"), "Subject: hi again\n\nbody\n");
    await(() -> logHas(x -> "SKIPPED".equals(x.outcome())));
    assertThat(mail.resolve("new/1700000000.M1P2.host")).exists();
    assertThat(mail.resolve("new/1700000001.M1P2.host")).exists();
    assertThat(mail.resolve(".Archive")).doesNotExist();

    // the audit log has the denied call with its arguments, and the (absent) policy at start
    assertThat(ask(new Request.Audit(10)))
        .isInstanceOfSatisfying(
            Response.AuditLines.class,
            a -> {
              assertThat(a.lines())
                  .anySatisfy(
                      l -> {
                        assertThat(l.kind()).isEqualTo("CALL");
                        assertThat(l.ruleId()).isEqualTo("mail.archive");
                        assertThat(l.subject()).isEqualTo("mail.move");
                        assertThat(l.allowed()).isFalse();
                        assertThat(l.detail()).contains("\"folder\":\"Archive\"");
                        assertThat(l.error()).contains("no grant");
                      });
              assertThat(a.lines())
                  .anySatisfy(
                      l -> {
                        assertThat(l.kind()).isEqualTo("POLICY");
                        assertThat(l.detail()).isEqualTo("{\"sha256\":\"absent\",\"grants\":[]}");
                      });
            });
  }

  @Test
  void theGrantsAreAuditedAtStartOnlyWhenTheyChanged() throws Exception {
    start().close();
    start().close();
    write(
        "policy.toml",
        Files.readString(home.resolve("policy.toml")) + "\n# a comment changes the hash\n");
    Daemon daemon = start();
    assertThat(ask(new Request.Audit(10)))
        .isInstanceOfSatisfying(
            Response.AuditLines.class,
            a ->
                assertThat(a.lines())
                    .filteredOn(l -> l.kind().equals("POLICY"))
                    .hasSize(2)
                    .allSatisfy(l -> assertThat(l.detail()).contains("\"grants\":[\"archive\"]")));
    daemon.close();
  }

  private void dropMail(String name) throws IOException {
    Files.writeString(mail.resolve("new/" + name), "Subject: " + name + "\n\nbody\n");
  }

  private boolean archived(String name) {
    return Files.exists(mail.resolve(".Archive/cur/" + name + ":2,"));
  }

  private Response.ApprovalList approvals() throws IOException {
    return (Response.ApprovalList) ask(new Request.Approvals());
  }

  private int requests() {
    try {
      return approvals().approvals().size();
    } catch (IOException e) {
      return -1;
    }
  }

  private void awaitRequest(int missed) throws Exception {
    await(
        () -> {
          try {
            return approvals().missed().getOrDefault("mail.archive", 0) == missed;
          } catch (IOException e) {
            return false;
          }
        });
  }

  @Test
  void allowAlwaysAddsAGrantAndReplaysTheMissedMailInOrder() throws Exception {
    Files.delete(home.resolve("policy.toml"));
    Daemon daemon = start();
    dropMail("1.a");
    awaitRequest(1);
    dropMail("2.b");
    awaitRequest(2);

    Response.ApprovalList list = approvals();
    assertThat(list.approvals()).hasSize(1);
    Response.ApprovalList.Item request = list.approvals().get(0);
    assertThat(request.state()).isEqualTo("PENDING");
    assertThat(request.ruleId()).isEqualTo("mail.archive");
    assertThat(request.layer()).isEqualTo("ruleset");
    assertThat(request.capability()).isEqualTo("mail.move");
    assertThat(request.args()).containsExactly(Map.entry("folder", "Archive"));
    assertThat(list.disabled()).containsKey("mail.archive");

    // the answer must say what happens to the missed mail
    assertThat(ask(new Request.Answer(request.id(), "always", null)))
        .isInstanceOfSatisfying(
            Response.Error.class, e -> assertThat(e.message()).contains("replay or discard"));
    assertThat(ask(new Request.Answer(request.id(), "maybe", null)))
        .isInstanceOf(Response.Error.class);
    assertThat(ask(new Request.Answer(99, "deny", null)))
        .isInstanceOfSatisfying(
            Response.Error.class, e -> assertThat(e.message()).contains("no request #99"));

    assertThat(ask(new Request.Answer(request.id(), "always", "replay")))
        .isInstanceOfSatisfying(
            Response.Done.class, d -> assertThat(d.message()).contains("Granted 'approval-1'"));
    await(() -> archived("1.a") && archived("2.b"));
    assertThat(Files.readString(home.resolve("policy.toml")))
        .contains("id = \"approval-1\"")
        .contains("layer = \"ruleset\"")
        .contains("folder = { equals = \"Archive\" }");
    assertThat(approvals().approvals().get(0).state()).isEqualTo("ALLOWED_ALWAYS");
    await(() -> logHasMissed(0));
    assertThat(ask(new Request.Answer(request.id(), "deny", null)))
        .isInstanceOfSatisfying(
            Response.Error.class, e -> assertThat(e.message()).contains("answered already"));

    // from now on the rule just works, and the grant and the answer are in the audit log
    dropMail("3.c");
    await(() -> archived("3.c"));
    assertThat(ask(new Request.Grants()))
        .isInstanceOfSatisfying(
            Response.GrantList.class,
            g ->
                assertThat(g.grants())
                    .extracting(Response.GrantList.Item::id)
                    .containsExactly("approval-1"));
    assertThat(ask(new Request.Audit(50)))
        .isInstanceOfSatisfying(
            Response.AuditLines.class,
            a -> {
              assertThat(a.lines())
                  .anyMatch(l -> l.kind().equals("APPROVAL") && l.subject().equals("request #1"));
              assertThat(a.lines())
                  .anyMatch(
                      l ->
                          l.kind().equals("GRANT")
                              && l.subject().equals("approval-1")
                              && l.detail().contains("\"action\":\"added\""));
            });
    daemon.close();
  }

  private boolean logHasMissed(int count) {
    try {
      return approvals().missed().getOrDefault("mail.archive", 0) == count;
    } catch (IOException e) {
      return false;
    }
  }

  @Test
  void allowOnceHandlesTheDeniedMailAndTheNextOneAsksAgain() throws Exception {
    Files.delete(home.resolve("policy.toml"));
    start();
    dropMail("1.a");
    awaitRequest(1);
    long first = approvals().approvals().get(0).id();
    assertThat(ask(new Request.Answer(first, "once", "replay"))).isInstanceOf(Response.Error.class);
    assertThat(ask(new Request.Answer(first, "once", null))).isInstanceOf(Response.Done.class);
    await(() -> archived("1.a"));
    assertThat(Files.exists(home.resolve("policy.toml"))).isFalse();

    dropMail("2.b");
    await(() -> requests() == 2);
    assertThat(approvals().approvals())
        .extracting(Response.ApprovalList.Item::state)
        .containsExactly("ALLOWED_ONCE", "PENDING");
    assertThat(archived("2.b")).isFalse();
  }

  @Test
  void aRefusedRuleStaysDisabledAfterARestartUntilAGrantIsAdded() throws Exception {
    Files.delete(home.resolve("policy.toml"));
    Daemon daemon = start();
    dropMail("1.a");
    awaitRequest(1);
    long id = approvals().approvals().get(0).id();
    assertThat(ask(new Request.Answer(id, "deny", null)))
        .isInstanceOfSatisfying(
            Response.Done.class, d -> assertThat(d.message()).contains("stays disabled"));
    daemon.close();

    // after the restart the rule is disabled from the start: the mail is skipped, not denied again
    start();
    dropMail("2.b");
    awaitRequest(2);
    assertThat(approvals().approvals()).hasSize(1);
    assertThat(approvals().approvals().get(0).state()).isEqualTo("DENIED");
    assertThat(approvals().disabled().get("mail.archive")).contains("refused in request #1");
    assertThat(archived("1.a")).isFalse();

    // a grant added by hand frees the rule; the missed mail waits until someone decides
    write(
        "policy.toml",
        """
        format_version = 1

        [[grant]]
        id = "by-hand"
        layer = "ruleset"
        capability = "mail.move"
        [grant.args]
        folder = { prefix = "Arch" }
        """);
    assertThat(ask(new Request.Reload())).isInstanceOf(Response.Done.class);
    assertThat(approvals().approvals().get(0).state()).isEqualTo("COVERED");
    assertThat(approvals().disabled()).isEmpty();
    assertThat(approvals().missed()).containsEntry("mail.archive", 2);

    assertThat(ask(new Request.Missed("nope", "replay"))).isInstanceOf(Response.Error.class);
    assertThat(ask(new Request.Missed("mail.archive", "replay"))).isInstanceOf(Response.Done.class);
    await(() -> archived("1.a") && archived("2.b"));
    awaitRequest(0);
  }

  @Test
  void discardingDropsTheMissedMailWithoutMovingIt() throws Exception {
    Files.delete(home.resolve("policy.toml"));
    start();
    dropMail("1.a");
    awaitRequest(1);
    long id = approvals().approvals().get(0).id();
    assertThat(ask(new Request.Answer(id, "always", "discard"))).isInstanceOf(Response.Done.class);
    awaitRequest(0);
    assertThat(archived("1.a")).isFalse();
    assertThat(mail.resolve("new/1.a")).exists();
  }

  @Test
  void aRevokedGrantMakesTheRuleAskAgain() throws Exception {
    Daemon daemon = start();
    dropMail("1.a");
    await(() -> archived("1.a"));

    assertThat(ask(new Request.Revoke("nope"))).isInstanceOf(Response.Error.class);
    assertThat(ask(new Request.Revoke("archive")))
        .isInstanceOfSatisfying(
            Response.Done.class, d -> assertThat(d.message()).contains("Revoked 'archive'"));
    assertThat(Files.readString(home.resolve("policy.toml"))).doesNotContain("archive");

    dropMail("2.b");
    awaitRequest(1);
    assertThat(archived("2.b")).isFalse();
    daemon.close();
  }

  @Test
  void aBrokenPolicyEditIsRefusedAndTheOldGrantsStayInUse() throws Exception {
    start();
    write("policy.toml", "format_version = 1\n[[grant]]\nid = \"x\"\n");
    assertThat(ask(new Request.Reload()))
        .isInstanceOfSatisfying(
            Response.Error.class,
            e ->
                assertThat(e.message())
                    .contains("old grants stay in use")
                    .contains("missing 'layer'"));
    dropMail("1.a");
    await(() -> archived("1.a"));
  }

  @Test
  void aPolicyThatDoesNotMatchTheModulesAndLayersStopsTheStart() throws Exception {
    write(
        "policy.toml",
        """
        format_version = 1

        [[grant]]
        id = "x"
        layer = "elsewhere"
        capability = "mail.copy"
        """);
    assertThatThrownBy(this::start)
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("the policy is not valid")
        .hasMessageContaining("no layer named 'elsewhere'")
        .hasMessageContaining("no capability named 'mail.copy'");
  }

  @Test
  void injectedEventsAreCheckedTypedAndDeduplicated() throws Exception {
    {
      start();
      assertThat(ask(new Request.Inject("nope", null, null, null)))
          .isEqualTo(new Response.Error("unknown event type 'nope'"));
      assertThat(
              ask(new Request.Inject("mail.received", null, null, Map.of("size", "big", "x", 1))))
          .isInstanceOfSatisfying(
              Response.Error.class,
              e ->
                  assertThat(e.message())
                      .contains("missing required field 'message'")
                      .contains("field 'size': must be of type int")
                      .contains("unknown field 'x'"));

      Map<String, Object> data =
          Map.of("message", mail + "/new/gone", "folder", "INBOX", "size", 1);
      Response first = ask(new Request.Inject("mail.received", "t", "k1", data));
      assertThat(first)
          .isInstanceOfSatisfying(Response.Injected.class, i -> assertThat(i.accepted()).isTrue());
      assertThat(ask(new Request.Inject("mail.received", "t", "k1", data)))
          .isInstanceOfSatisfying(Response.Injected.class, i -> assertThat(i.accepted()).isFalse());

      // the mail does not exist, so the action fails and says so in the log
      await(
          () ->
              logHas(x -> "FAILED".equals(x.outcome()) && x.message().contains("mail not found")));
    }
  }

  @Test
  void stopRequestsEndTheDaemonAndRemoveTheSocket() throws Exception {
    Daemon daemon = start();
    try {
      assertThat(ask(new Request.Stop())).isEqualTo(new Response.Stopped());
      daemon.awaitStop();
    } finally {
      daemon.close();
    }
    assertThat(home.resolve("praksis.sock")).doesNotExist();
    daemon.close(); // closing twice is fine
  }

  @Test
  void refusesASecondDaemonButReplacesAStaleSocket() throws Exception {
    {
      Daemon first = start();
      assertThatThrownBy(this::start)
          .isInstanceOf(DaemonException.class)
          .hasMessageContaining("already running");
      assertThat(ask(new Request.Status())).isInstanceOf(Response.StatusReport.class);
      first.close();
    }
    Files.writeString(home.resolve("praksis.sock"), "left over by a crash");
    {
      start();
      assertThat(ask(new Request.Status())).isInstanceOf(Response.StatusReport.class);
    }
  }

  @Test
  void anInvalidRulesetPreventsTheStartAndLeavesNothingRunning() throws Exception {
    write("ruleset/rules/mail.archive.toml", RULE.replace("\"mail.move\"]", "\"mail.nothing\"]"));
    assertThatThrownBy(this::start)
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("is not valid")
        .hasMessageContaining("mail.archive.toml");
    assertThat(home.resolve("praksis.sock")).doesNotExist();
  }

  @Test
  void aSocketPathThatIsTooLongForUnixSocketsIsExplained() throws Exception {
    Path longHome = Files.createDirectories(home.resolve("x".repeat(120)));
    assertThatThrownBy(() -> Daemon.start(DaemonConfig.load(longHome)))
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("Use a shorter home directory");
  }

  @Test
  void aModuleThatChangedSinceLockingPreventsTheStart() throws Exception {
    Files.writeString(home.resolve("modules/maildir/extra.txt"), "swapped");
    assertThatThrownBy(this::start).hasMessageContaining("has changed since it was locked");
  }
}
