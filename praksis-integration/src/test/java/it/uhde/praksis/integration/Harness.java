// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A Praksis home directory with the real modules installed, and real daemon and CLI processes run
 * from the built jars.
 */
final class Harness implements AutoCloseable {
  static final String RULE =
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

  private final Path modulePath = Path.of(System.getProperty("praksis.modulepath"));
  private final Path extras = Path.of(System.getProperty("praksis.extras"));
  private final List<Process> processes = new ArrayList<>();

  /** Short, because Unix socket paths are. */
  final Path home;

  final Path mail;

  Harness() throws Exception {
    home = Files.createTempDirectory("pk");
    mail = home.resolve("mail");
    for (String d : new String[] {"new", "cur", "tmp"}) {
      Files.createDirectories(mail.resolve(d));
    }
    Path moduleDir = Files.createDirectories(home.resolve("modules/maildir"));
    Files.copy(
        Path.of("../modules/praksis-module-maildir/module.toml"), moduleDir.resolve("module.toml"));
    Path expressionDir = Files.createDirectories(home.resolve("lib/expression"));
    try (DirectoryStream<Path> jars = Files.newDirectoryStream(extras, "*.jar")) {
      for (Path jar : jars) {
        String name = jar.getFileName().toString();
        if (name.startsWith("praksis-module-maildir")) {
          Files.copy(jar, moduleDir.resolve(name));
        } else if (name.startsWith("praksis-expression-cel")) {
          Files.copy(jar, expressionDir.resolve(name));
        }
      }
    }
    ModuleLoader.lock(home.resolve("modules"), home.resolve("modules.lock"));
    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\nexpression = 'lib/expression'\n[module.maildir]\nroot = '"
            + mail
            + "'\npoll_interval_ms = 20\n");
    Files.createDirectories(home.resolve("ruleset/rules"));
    Files.writeString(
        home.resolve("ruleset/ruleset.toml"), "format_version = 1\nname = \"mail\"\n");
    Files.writeString(home.resolve("ruleset/rules/mail.archive.toml"), RULE);
  }

  Path socket() {
    return home.resolve("praksis.sock");
  }

  private List<String> java(String module) {
    return new ArrayList<>(
        List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "--enable-native-access=org.xerial.sqlitejdbc",
            "--module-path",
            modulePath.toString(),
            "--module",
            module,
            "--home",
            home.toString()));
  }

  /** Starts the daemon itself (not through the CLI) and waits until it answers. */
  Process startDaemon() throws Exception {
    Process p =
        new ProcessBuilder(java("it.uhde.praksis.daemon/it.uhde.praksis.daemon.Main"))
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(home.resolve("daemon.log").toFile()))
            .start();
    processes.add(p);
    await(
        "the daemon to answer",
        () -> {
          if (!p.isAlive()) {
            throw new AssertionError(
                "the daemon exited with " + p.exitValue() + ":\n" + daemonLog());
          }
          return status() != null;
        });
    return p;
  }

  /** Kills with SIGKILL, so nothing gets a chance to clean up, and waits for the exit. */
  void kill(Process p) throws InterruptedException {
    p.destroyForcibly();
    p.waitFor();
  }

  /** Runs the CLI and returns its exit code and everything it printed. */
  record CliResult(int exit, String output) {}

  CliResult cli(String... args) throws Exception {
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--module-path",
                modulePath.toString(),
                "--module",
                "it.uhde.praksis.cli/it.uhde.praksis.cli.Praksis",
                args[0],
                "--home",
                home.toString()));
    command.addAll(List.of(args).subList(1, args.length));
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    p.getOutputStream().close();
    String output = new String(p.getInputStream().readAllBytes());
    return new CliResult(p.waitFor(), output);
  }

  /** The daemon's status, or null if it does not answer. */
  Response.StatusReport status() {
    try (Client client = Client.connect(socket())) {
      return client.send(new Request.Status()) instanceof Response.StatusReport s ? s : null;
    } catch (IOException e) {
      return null;
    }
  }

  Response ask(Request request) throws IOException {
    try (Client client = Client.connect(socket())) {
      return client.send(request);
    }
  }

  List<Response.LogLines.Line> log() throws IOException {
    return ((Response.LogLines) ask(new Request.Log(1000))).lines();
  }

  /** Delivers a mail the way a mail delivery agent does: write in tmp, then rename into new. */
  void deliver(String uniqueName, String subject) throws IOException {
    Path tmp = mail.resolve("tmp").resolve(uniqueName);
    Files.writeString(tmp, "From: a@example.org\nSubject: " + subject + "\n\nbody\n");
    Files.move(tmp, mail.resolve("new").resolve(uniqueName));
  }

  List<String> names(String dir) throws IOException {
    Path d = mail.resolve(dir);
    if (!Files.isDirectory(d)) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(d)) {
      return files.map(f -> f.getFileName().toString()).sorted(Comparator.naturalOrder()).toList();
    }
  }

  String daemonLog() {
    try {
      return Files.readString(home.resolve("daemon.log"));
    } catch (IOException e) {
      return "(no daemon.log)";
    }
  }

  /** A condition that may fail with an exception; the exception fails the test. */
  @FunctionalInterface
  interface Condition {
    boolean holds() throws Exception;
  }

  static void await(String what, Condition condition) throws Exception {
    long end = System.nanoTime() + 30_000_000_000L;
    while (!condition.holds()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out waiting for " + what);
      }
      Thread.sleep(10);
    }
  }

  @Override
  public void close() throws IOException {
    Response.StatusReport s = status();
    if (s != null) {
      ProcessHandle.of(s.pid()).ifPresent(ProcessHandle::destroyForcibly);
    }
    processes.forEach(Process::destroyForcibly);
    try (Stream<Path> files = Files.walk(home)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    }
  }
}
