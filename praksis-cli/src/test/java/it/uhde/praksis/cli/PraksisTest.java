// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Wire;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import picocli.CommandLine;

class PraksisTest {
  @TempDir Path home;

  private ServerSocketChannel server;
  private final List<Request> received = new CopyOnWriteArrayList<>();
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  /** A stand-in for the daemon that answers every request with the given function. */
  private void fakeDaemon(Function<Request, Response> answer) throws IOException {
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    server.bind(UnixDomainSocketAddress.of(home.resolve("praksis.sock")));
    Thread.ofVirtual()
        .start(
            () -> {
              while (true) {
                try (SocketChannel c = server.accept()) {
                  var in = Channels.newInputStream(c);
                  var o = Channels.newOutputStream(c);
                  String line;
                  while ((line = Wire.readLine(in)) != null) {
                    Request r = Wire.decodeRequest(line);
                    received.add(r);
                    o.write((Wire.encode(answer.apply(r)) + "\n").getBytes(StandardCharsets.UTF_8));
                  }
                } catch (IOException e) {
                  return;
                }
              }
            });
  }

  @AfterEach
  void tearDown() throws IOException {
    if (server != null) {
      server.close();
    }
  }

  private int run(String... args) {
    CommandLine cli = new CommandLine(new Praksis());
    cli.setOut(new PrintWriter(out, true));
    cli.setErr(new PrintWriter(err, true));
    String[] all = new String[args.length + 1];
    all[0] = args[0];
    all[1] = "--home=" + home;
    System.arraycopy(args, 1, all, 2, args.length - 1);
    return cli.execute(all);
  }

  @Test
  void statusShowsWhatTheDaemonReports() throws Exception {
    fakeDaemon(
        r ->
            new Response.StatusReport(
                42, "2026-01-01T00:00:00Z", "mail", 3, List.of("maildir"), 2));
    assertThat(run("status")).isZero();
    assertThat(out.toString())
        .contains("running, pid 42")
        .contains("ruleset: mail (3 rules)")
        .contains("modules: maildir")
        .contains("events waiting: 2");
  }

  @Test
  void saysSoWhenNoDaemonIsRunning() {
    assertThat(run("status")).isEqualTo(3);
    assertThat(err.toString()).contains("The daemon is not running").contains("praksis start");
  }

  @Test
  void aLeftoverSocketIsAFailureNotANotRunning() throws Exception {
    Files.writeString(home.resolve("praksis.sock"), "x");
    assertThat(run("status")).isEqualTo(1);
    assertThat(err.toString()).contains("Cannot talk to the daemon");
  }

  @Test
  void injectSendsTheTypedRequestAndReportsTheOutcome() throws Exception {
    fakeDaemon(
        r ->
            received.size() == 1
                ? new Response.Injected("e1", true)
                : new Response.Injected("e2", false));
    assertThat(
            run(
                "inject",
                "mail.received",
                "--target",
                "t",
                "--key",
                "k",
                "--data",
                "{\"size\":3,\"s\":\"x\"}"))
        .isZero();
    assertThat(received.get(0))
        .isEqualTo(new Request.Inject("mail.received", "t", "k", Map.of("size", 3, "s", "x")));
    assertThat(out.toString()).contains("accepted, event e1");

    assertThat(run("inject", "mail.received", "--key", "k")).isZero();
    assertThat(out.toString()).contains("dropped: the key was seen before");
  }

  @Test
  void injectRejectsBrokenJsonBeforeTalkingToTheDaemon() throws Exception {
    fakeDaemon(r -> new Response.Injected("e", true));
    assertThat(run("inject", "t", "--data", "{oops")).isEqualTo(2);
    assertThat(err.toString()).contains("--data: not a JSON object");
    assertThat(received).isEmpty();
  }

  @Test
  void errorsFromTheDaemonAreShownAndFailTheCommand() throws Exception {
    fakeDaemon(r -> new Response.Error("unknown event type 'x'"));
    assertThat(run("inject", "x")).isEqualTo(1);
    assertThat(err.toString()).contains("Error: unknown event type 'x'");
  }

  @Test
  void logPrintsOldestFirst() throws Exception {
    fakeDaemon(
        r ->
            new Response.LogLines(
                List.of(
                    new Response.LogLines.Line("t2", "e2", "r", "a", "FAILED", "second"),
                    new Response.LogLines.Line("t1", "e1", null, null, "OK", "first"))));
    assertThat(run("log", "-n", "5")).isZero();
    assertThat(received.get(0)).isEqualTo(new Request.Log(5));
    String text = out.toString();
    assertThat(text.indexOf("first")).isLessThan(text.indexOf("second"));
    assertThat(text).contains("t2  e2  r/a  FAILED  second");
  }

  @Test
  void stopAsksTheDaemonToStop() throws Exception {
    fakeDaemon(r -> new Response.Stopped());
    assertThat(run("stop")).isZero();
    assertThat(received).containsExactly(new Request.Stop());
  }

  @Test
  void startDoesNothingIfADaemonAnswers() throws Exception {
    fakeDaemon(r -> new Response.StatusReport(1, "t", "mail", 0, List.of(), 0));
    assertThat(run("start")).isZero();
    assertThat(out.toString()).contains("already running");
  }

  @Test
  void theDaemonCommandUsesTheSameRuntimeAndModulePath() {
    assertThat(DaemonCommand.build(Path.of("/h"), "/jdk", "/mods:/more"))
        .containsExactly(
            "/jdk/bin/java",
            "--module-path",
            "/mods:/more",
            "--enable-native-access=org.xerial.sqlitejdbc",
            "--module",
            "it.uhde.praksis.daemon/it.uhde.praksis.daemon.Main",
            "--home",
            "/h");
    assertThat(DaemonCommand.build(Path.of("/h"), "/jdk", null)).doesNotContain("--module-path");
  }

  @Test
  void adminCommandsAreRunByTheDaemonMainClass() {
    assertThat(DaemonCommand.buildWith(Path.of("/h"), "--admin", "layers", "update", "--dry-run"))
        .endsWith("--home", "/h", "--admin", "layers", "update", "--dry-run");
  }
}
