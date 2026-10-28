// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;
import picocli.CommandLine.ScopeType;
import picocli.CommandLine.Spec;

/**
 * The {@code praksis} command.
 *
 * <p>Exit codes: 0 done, 1 failed, 2 wrong usage, 3 the daemon is not running.
 */
@Command(
    name = "praksis",
    mixinStandardHelpOptions = true,
    version = "praksis 0.1.0-SNAPSHOT",
    description = "Talks to the Praksis daemon.",
    subcommands = {
      Praksis.Start.class,
      Praksis.Stop.class,
      Praksis.Status.class,
      Praksis.Inject.class,
      Praksis.LogCommand.class
    })
public final class Praksis {
  static final int EXIT_FAILED = 1;
  static final int EXIT_NOT_RUNNING = 3;

  @Option(
      names = "--home",
      scope = ScopeType.INHERIT,
      description =
          "The Praksis home directory (default: $PRAKSIS_HOME, else ~/.praksis). It holds the"
              + " configuration, the ruleset, the modules and the daemon's socket.")
  Path home;

  @Spec CommandSpec spec;

  public static void main(String[] args) {
    System.exit(new CommandLine(new Praksis()).execute(args));
  }

  /** The home directory from the option, the environment or the default. */
  Path home() {
    if (home != null) {
      return home.toAbsolutePath().normalize();
    }
    String env = System.getenv("PRAKSIS_HOME");
    if (env != null && !env.isBlank()) {
      return Path.of(env).toAbsolutePath().normalize();
    }
    return Path.of(System.getProperty("user.home"), ".praksis");
  }

  Path socket() {
    return home().resolve("praksis.sock");
  }

  /** What every subcommand shares: it knows the home and prints to the right streams. */
  abstract static class Base implements Callable<Integer> {
    @ParentCommand Praksis parent;
    @Spec CommandSpec spec;

    PrintWriter out() {
      return spec.commandLine().getOut();
    }

    PrintWriter err() {
      return spec.commandLine().getErr();
    }

    /** Sends one request. Returns null, after saying why, if the daemon cannot be reached. */
    Response send(Request request) {
      Path socket = parent.socket();
      try (Client client = Client.connect(socket)) {
        return client.send(request);
      } catch (IOException e) {
        if (!Files.exists(socket)) {
          err().println("The daemon is not running (no socket at " + socket + ").");
          err().println("Start it with: praksis start");
        } else {
          err().println("Cannot talk to the daemon at " + socket + ": " + e.getMessage());
        }
        return null;
      }
    }

    /** True if the daemon was reached, false if not. */
    boolean running() {
      try (Client client = Client.connect(parent.socket())) {
        return client.send(new Request.Status()) instanceof Response.StatusReport;
      } catch (IOException e) {
        return false;
      }
    }

    int exitCodeFor(Response response) {
      if (response == null) {
        return Files.exists(parent.socket()) ? EXIT_FAILED : EXIT_NOT_RUNNING;
      }
      if (response instanceof Response.Error e) {
        err().println("Error: " + e.message());
        return EXIT_FAILED;
      }
      return 0;
    }
  }

  @Command(name = "status", description = "Shows how the daemon is doing.")
  static final class Status extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Status());
      if (response instanceof Response.StatusReport s) {
        out().println("running, pid " + s.pid() + ", since " + s.startedAt());
        out().println("ruleset: " + s.ruleset() + " (" + s.rules() + " rules)");
        out()
            .println(
                "modules: " + (s.modules().isEmpty() ? "none" : String.join(", ", s.modules())));
        out().println("events waiting: " + s.pending());
        return 0;
      }
      return exitCodeFor(response);
    }
  }

  @Command(name = "stop", description = "Asks the daemon to finish its work and exit.")
  static final class Stop extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Stop());
      if (response instanceof Response.Stopped) {
        out().println("The daemon is stopping.");
        return 0;
      }
      return exitCodeFor(response);
    }
  }

  @Command(name = "inject", description = "Hands the daemon an event, as if a source had made it.")
  static final class Inject extends Base {
    @CommandLine.Parameters(index = "0", paramLabel = "<type>", description = "Event type.")
    String type;

    @Option(names = "--target", description = "What the event is about (default: cli).")
    String target;

    @Option(names = "--key", description = "Idempotency key; an event with a known key is dropped.")
    String key;

    @Option(
        names = "--data",
        paramLabel = "<json>",
        description =
            "The payload as a JSON object. Timestamps are ISO-8601 strings, bytes Base64.")
    String data;

    @Override
    public Integer call() {
      java.util.Map<String, Object> payload = null;
      if (data != null) {
        try {
          payload = it.uhde.praksis.protocol.Wire.parseObject(data);
        } catch (IOException e) {
          err().println("--data: " + e.getMessage());
          return CommandLine.ExitCode.USAGE;
        }
      }
      Response response = send(new Request.Inject(type, target, key, payload));
      if (response instanceof Response.Injected i) {
        out()
            .println(
                i.accepted() ? "accepted, event " + i.id() : "dropped: the key was seen before");
        return 0;
      }
      return exitCodeFor(response);
    }
  }

  @Command(name = "log", description = "Shows the latest lines of the run log, oldest first.")
  static final class LogCommand extends Base {
    @Option(
        names = {"-n", "--lines"},
        defaultValue = "20",
        description = "How many lines.")
    int lines;

    @Override
    public Integer call() {
      Response response = send(new Request.Log(lines));
      if (response instanceof Response.LogLines l) {
        for (int i = l.lines().size() - 1; i >= 0; i--) {
          Response.LogLines.Line line = l.lines().get(i);
          String where = line.ruleId() == null ? "-" : line.ruleId();
          if (line.actionId() != null) {
            where += "/" + line.actionId();
          }
          out()
              .printf(
                  "%s  %s  %s  %-7s %s%n",
                  line.time(), line.eventId(), where, line.outcome(), line.message());
        }
        return 0;
      }
      return exitCodeFor(response);
    }
  }

  @Command(name = "start", description = "Starts the daemon in the background.")
  static final class Start extends Base {
    @Option(
        names = "--wait",
        defaultValue = "15",
        description = "Seconds to wait for it to be ready.")
    int waitSeconds;

    @Override
    public Integer call() throws InterruptedException {
      if (running()) {
        out().println("The daemon is already running.");
        return 0;
      }
      Path home = parent.home();
      Path logFile = home.resolve("praksis.log");
      Process process;
      try {
        Files.createDirectories(home);
        process =
            new ProcessBuilder(DaemonCommand.build(home))
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile.toFile()))
                .start();
        process.getOutputStream().close();
      } catch (IOException e) {
        err().println("Cannot start the daemon: " + e.getMessage());
        return EXIT_FAILED;
      }
      long deadline = System.nanoTime() + waitSeconds * 1_000_000_000L;
      while (System.nanoTime() < deadline) {
        if (running()) {
          out().println("The daemon is running, pid " + process.pid() + ". Log: " + logFile);
          return 0;
        }
        if (!process.isAlive()) {
          break;
        }
        Thread.sleep(100);
      }
      err()
          .println(
              process.isAlive()
                  ? "The daemon did not become ready within " + waitSeconds + " seconds."
                  : "The daemon exited with code " + process.exitValue() + ".");
      err().println("See " + logFile + ":");
      tail(logFile).forEach(err()::println);
      return EXIT_FAILED;
    }

    private static java.util.List<String> tail(Path file) {
      try {
        java.util.List<String> all = Files.readAllLines(file);
        return all.subList(Math.max(0, all.size() - 15), all.size());
      } catch (IOException e) {
        return java.util.List.of("(cannot read the log: " + e.getMessage() + ")");
      }
    }
  }
}
