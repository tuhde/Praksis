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
      args = { path = { cel = "event.data.path" }, folder = "Archive" }
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
                      .contains("missing required field 'path'")
                      .contains("field 'size': must be of type int")
                      .contains("unknown field 'x'"));

      Map<String, Object> data =
          Map.of("path", mail + "/new/gone", "filename", "gone", "folder", "INBOX", "size", 1);
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
