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
      Praksis.Modules.class,
      Praksis.Layers.class,
      Praksis.Effective.class,
      Praksis.LogCommand.class,
      Praksis.AuditCommand.class,
      Praksis.Approvals.class,
      Praksis.GrantsCommand.class
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
    @Spec CommandSpec spec;

    Praksis parent() {
      return (Praksis) spec.root().userObject();
    }

    PrintWriter out() {
      return spec.commandLine().getOut();
    }

    PrintWriter err() {
      return spec.commandLine().getErr();
    }

    /** Sends one request. Returns null, after saying why, if the daemon cannot be reached. */
    Response send(Request request) {
      Path socket = parent().socket();
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

    /**
     * Runs the daemon's main class with the given arguments, prints its output, returns its exit
     * code.
     */
    int runDaemonCommand(String... args) throws InterruptedException {
      try {
        Process p =
            new ProcessBuilder(DaemonCommand.buildWith(parent().home(), args))
                .redirectErrorStream(true)
                .start();
        p.getOutputStream().close();
        String output = new String(p.getInputStream().readAllBytes());
        int exit = p.waitFor();
        PrintWriter target = exit == 0 ? out() : err();
        target.print(output);
        target.flush();
        return exit == 0 ? 0 : EXIT_FAILED;
      } catch (IOException e) {
        err().println("Cannot run the daemon's command: " + e.getMessage());
        return EXIT_FAILED;
      }
    }

    /** True if the daemon was reached, false if not. */
    boolean running() {
      try (Client client = Client.connect(parent().socket())) {
        return client.send(new Request.Status()) instanceof Response.StatusReport;
      } catch (IOException e) {
        return false;
      }
    }

    int exitCodeFor(Response response) {
      if (response == null) {
        return Files.exists(parent().socket()) ? EXIT_FAILED : EXIT_NOT_RUNNING;
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

  @Command(
      name = "audit",
      description =
          "Shows the latest lines of the audit log: every capability call with its arguments and"
              + " whether it was allowed, and the grants found at start. Newest first.")
  static final class AuditCommand extends Base {
    @Option(
        names = {"-n", "--lines"},
        defaultValue = "20",
        description = "How many lines.")
    int lines;

    @Override
    public Integer call() {
      Response response = send(new Request.Audit(lines));
      if (response instanceof Response.AuditLines l) {
        for (Response.AuditLines.Line line : l.lines()) {
          String verdict =
              line.kind().equals("CALL") || line.kind().equals("CREDENTIAL")
                  ? (line.allowed() ? "allowed" : "DENIED")
                  : "";
          out()
              .printf(
                  "%s  %-6s %-14s %-8s %s %s%s%n",
                  line.time(),
                  line.kind(),
                  line.ruleId() == null ? "-" : line.ruleId(),
                  verdict,
                  line.subject(),
                  line.detail(),
                  line.error() == null ? "" : "  [" + line.error() + "]");
        }
        return 0;
      }
      return exitCodeFor(response);
    }
  }

  @Command(
      name = "approvals",
      description =
          "Lists the requests for permissions, with the disabled rules and the events they missed."
              + " A rule whose call no grant covers is disabled until a request is answered.",
      subcommands = {
        Praksis.Approvals.Allow.class,
        Praksis.Approvals.Deny.class,
        Praksis.Approvals.Replay.class,
        Praksis.Approvals.Discard.class
      })
  static final class Approvals extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Approvals());
      if (response instanceof Response.ApprovalList l) {
        if (l.approvals().isEmpty()) {
          out().println("No requests.");
        }
        for (Response.ApprovalList.Item a : l.approvals()) {
          out()
              .printf(
                  "#%d  %-14s %s (layer %s) needs %s %s  [%s]%n",
                  a.id(), a.state(), a.ruleId(), a.layer(), a.capability(), a.args(), a.time());
        }
        if (!l.disabled().isEmpty()) {
          out().println("Disabled rules:");
          l.disabled().forEach((rule, why) -> out().printf("  %s: %s%n", rule, why));
        }
        if (!l.missed().isEmpty()) {
          out().println("Missed events:");
          l.missed().forEach((rule, n) -> out().printf("  %s: %d%n", rule, n));
        }
        return 0;
      }
      return exitCodeFor(response);
    }

    abstract static class Answering extends Base {
      int print(Response response) {
        if (response instanceof Response.Done d) {
          out().println(d.message());
          return 0;
        }
        return exitCodeFor(response);
      }
    }

    @Command(
        name = "allow",
        description =
            "Allows what a rule asked for. --once lets the one denied event through. --always adds"
                + " a grant to policy.toml for exactly the call that was seen and needs --replay or"
                + " --discard for the events the rule missed meanwhile.")
    static final class Allow extends Answering {
      @CommandLine.Parameters(index = "0", paramLabel = "<request>", description = "The number.")
      long id;

      @Option(names = "--once", description = "Let the one denied event through.")
      boolean once;

      @Option(names = "--always", description = "Add a grant for what was seen.")
      boolean always;

      @Option(names = "--replay", description = "Handle the missed events now, in order.")
      boolean replay;

      @Option(names = "--discard", description = "Drop the missed events.")
      boolean discard;

      @Override
      public Integer call() {
        if (once == always) {
          err().println("Say either --once or --always.");
          return 2;
        }
        if (replay && discard) {
          err().println("Say either --replay or --discard.");
          return 2;
        }
        return print(
            send(
                new Request.Answer(
                    id, once ? "once" : "always", replay ? "replay" : discard ? "discard" : null)));
      }
    }

    @Command(
        name = "deny",
        description = "Refuses a request. The rule stays disabled, also after a restart.")
    static final class Deny extends Answering {
      @CommandLine.Parameters(index = "0", paramLabel = "<request>", description = "The number.")
      long id;

      @Option(names = "--discard", description = "Drop the events the rule missed.")
      boolean discard;

      @Override
      public Integer call() {
        return print(send(new Request.Answer(id, "deny", discard ? "discard" : null)));
      }
    }

    @Command(
        name = "replay",
        description = "Handles the events a rule missed, in order, for a rule that runs again.")
    static final class Replay extends Answering {
      @CommandLine.Parameters(index = "0", paramLabel = "<rule>", description = "The rule id.")
      String rule;

      @Override
      public Integer call() {
        return print(send(new Request.Missed(rule, "replay")));
      }
    }

    @Command(name = "discard", description = "Drops the events a rule missed.")
    static final class Discard extends Answering {
      @CommandLine.Parameters(index = "0", paramLabel = "<rule>", description = "The rule id.")
      String rule;

      @Override
      public Integer call() {
        return print(send(new Request.Missed(rule, "discard")));
      }
    }
  }

  @Command(
      name = "grants",
      description = "Lists the grants and credential bindings in policy.toml that are in effect.",
      subcommands = {Praksis.GrantsCommand.Revoke.class, Praksis.GrantsCommand.Reload.class})
  static final class GrantsCommand extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Grants());
      if (response instanceof Response.GrantList l) {
        if (l.grants().isEmpty()) {
          out().println("No grants: nothing is allowed.");
        }
        if (!l.credentials().isEmpty()) {
          out().println("Credentials (the secrets are never shown):");
          for (Response.GrantList.Credential c : l.credentials()) {
            out()
                .printf(
                    "  %s  for %s, from the command '%s'%s%n",
                    c.name(),
                    String.join(", ", c.components()),
                    c.executable(),
                    c.note() == null ? "" : "  (" + c.note() + ")");
          }
        }
        for (Response.GrantList.Item g : l.grants()) {
          out().printf("%s  layer %s may call %s%n", g.id(), g.layer(), g.capability());
          g.args().forEach((arg, how) -> out().printf("    %s: %s%n", arg, how));
          if (g.note() != null) {
            out().printf("    %s%n", g.note());
          }
        }
        return 0;
      }
      return exitCodeFor(response);
    }

    @Command(name = "revoke", description = "Removes a grant from policy.toml.")
    static final class Revoke extends Base {
      @CommandLine.Parameters(index = "0", paramLabel = "<grant>", description = "The grant id.")
      String id;

      @Override
      public Integer call() {
        Response response = send(new Request.Revoke(id));
        if (response instanceof Response.Done d) {
          out().println(d.message());
          return 0;
        }
        return exitCodeFor(response);
      }
    }

    @Command(
        name = "reload",
        description = "Reads policy.toml again after it was edited by hand and checks it.")
    static final class Reload extends Base {
      @Override
      public Integer call() {
        Response response = send(new Request.Reload());
        if (response instanceof Response.Done d) {
          out().println(d.message());
          return 0;
        }
        return exitCodeFor(response);
      }
    }
  }

  @Command(
      name = "modules",
      description = "Manages the modules.",
      subcommands = Praksis.Modules.Lock.class)
  static final class Modules {
    @Command(
        name = "lock",
        description =
            "Accepts the modules as they are now: records their hashes in modules.lock. The"
                + " daemon loads only modules that match.")
    static final class Lock extends Base {
      @Override
      public Integer call() throws InterruptedException {
        return runDaemonCommand("--lock-modules");
      }
    }
  }

  @Command(
      name = "layers",
      description = "Manages the ruleset layers that come from git.",
      subcommands = {Praksis.Layers.StatusCommand.class, Praksis.Layers.UpdateCommand.class})
  static final class Layers {
    @Command(name = "status", description = "Shows the commit every layer is pinned to.")
    static final class StatusCommand extends Base {
      @Override
      public Integer call() throws InterruptedException {
        return runDaemonCommand("--admin", "layers", "status");
      }
    }

    @Command(
        name = "update",
        description =
            "Moves layers to the latest commit of their branch or tag, but only if the ruleset"
                + " that results is valid. Otherwise the pins stay as they are.")
    static final class UpdateCommand extends Base {
      @Option(names = "--dry-run", description = "Only show what would change.")
      boolean dryRun;

      @CommandLine.Parameters(
          paramLabel = "<layer>",
          description = "Layers to move (default: all).")
      java.util.List<String> names = new java.util.ArrayList<>();

      @Override
      public Integer call() throws InterruptedException {
        java.util.List<String> args =
            new java.util.ArrayList<>(java.util.List.of("--admin", "layers", "update"));
        if (dryRun) {
          args.add("--dry-run");
        }
        args.addAll(names);
        return runDaemonCommand(args.toArray(String[]::new));
      }
    }
  }

  @Command(
      name = "effective",
      description =
          "Shows the ruleset that would run: every rule in the order it fires, which layer it comes"
              + " from, what later layers did to it, and its hash for 'based_on'.")
  static final class Effective extends Base {
    @Override
    public Integer call() throws InterruptedException {
      return runDaemonCommand("--admin", "effective");
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
      Path home = parent().home();
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
