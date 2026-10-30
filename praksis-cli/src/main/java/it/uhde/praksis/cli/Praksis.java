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
      Praksis.Verify.class,
      Praksis.RunTests.class,
      Praksis.LogCommand.class,
      Praksis.AuditCommand.class,
      Praksis.Approvals.class,
      Praksis.GrantsCommand.class,
      Praksis.ShadowCommand.class,
      Praksis.Proposals.class,
      Praksis.CommandsCommand.class,
      Praksis.RunCommand.class,
      Praksis.ViewsCommand.class,
      Praksis.ViewCommand.class
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
      name = "commands",
      description =
          "Lists the commands that a person can start (rules with a command trigger), with their"
              + " arguments. Start one with: praksis run <command> name=value ...")
  static final class CommandsCommand extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Commands());
      if (!(response instanceof Response.CommandList l)) {
        return exitCodeFor(response);
      }
      if (l.commands().isEmpty()) {
        out().println("No commands. A rule becomes one with trigger kind = \"command\".");
      }
      for (Response.CommandList.Command c : l.commands()) {
        out()
            .printf(
                "%s  %s  [%s, layer %s]%s%n",
                c.id(),
                c.title(),
                c.effect(),
                c.owner(),
                c.disabled() == null ? "" : "  DISABLED: " + c.disabled());
        if (c.description() != null) {
          out().printf("    %s%n", c.description());
        }
        for (Response.CommandList.Param p : c.params()) {
          out()
              .printf(
                  "    %s (%s)%s%s%n",
                  p.name(),
                  p.type(),
                  p.required()
                      ? ", required"
                      : p.defaultValue() == null ? "" : ", default " + p.defaultValue(),
                  p.label() == null ? "" : "  " + p.label());
        }
      }
      return 0;
    }
  }

  @Command(
      name = "run",
      description =
          "Starts a command and waits for its result. Arguments are name=value; the types come"
              + " from the command. Lists and maps are given through the API, not here.")
  static final class RunCommand extends Base {
    @CommandLine.Parameters(index = "0", paramLabel = "<command>", description = "The command id.")
    String command;

    @CommandLine.Parameters(index = "1..*", paramLabel = "<name=value>", description = "Arguments.")
    java.util.List<String> arguments = new java.util.ArrayList<>();

    @Option(names = "--no-wait", description = "Do not wait for the result.")
    boolean noWait;

    @Override
    public Integer call() throws InterruptedException {
      Response listed = send(new Request.Commands());
      if (!(listed instanceof Response.CommandList l)) {
        return exitCodeFor(listed);
      }
      Response.CommandList.Command found =
          l.commands().stream().filter(c -> c.id().equals(command)).findFirst().orElse(null);
      if (found == null) {
        err().println("There is no command '" + command + "'. See: praksis commands");
        return EXIT_FAILED;
      }
      java.util.Map<String, Object> given = new java.util.LinkedHashMap<>();
      for (String a : arguments) {
        int eq = a.indexOf('=');
        if (eq < 1) {
          err().println("An argument is written name=value: " + a);
          return 2;
        }
        String name = a.substring(0, eq);
        String value = a.substring(eq + 1);
        Response.CommandList.Param p =
            found.params().stream().filter(x -> x.name().equals(name)).findFirst().orElse(null);
        if (p == null) {
          err().println("Command '" + command + "' has no argument '" + name + "'.");
          return 2;
        }
        try {
          given.put(name, typed(p.type(), value));
        } catch (IllegalArgumentException e) {
          err().println("Argument '" + name + "': " + e.getMessage());
          return 2;
        }
      }
      Response started = send(new Request.RunCommand(command, given, "praksis-cli"));
      if (!(started instanceof Response.CommandStarted s)) {
        return exitCodeFor(started);
      }
      out().println("Started " + command + " (event " + s.event() + ").");
      if (noWait) {
        return 0;
      }
      long end = System.nanoTime() + 60_000_000_000L;
      while (System.nanoTime() < end) {
        Response r = send(new Request.Outcome(s.event()));
        if (!(r instanceof Response.Outcome o)) {
          return exitCodeFor(r);
        }
        if (o.done()) {
          boolean ok = true;
          for (Response.LogLines.Line line : o.lines()) {
            ok &= line.outcome().equals("OK");
            out()
                .printf(
                    "  %-7s %s%s%n",
                    line.outcome(),
                    line.actionId() == null ? "" : line.actionId() + ": ",
                    line.message());
          }
          if (o.lines().isEmpty()) {
            out().println("  (nothing was done)");
          }
          return ok ? 0 : EXIT_FAILED;
        }
        Thread.sleep(50);
      }
      err().println("Still running after 60 seconds; see: praksis log");
      return EXIT_FAILED;
    }

    private static Object typed(String type, String value) {
      try {
        return switch (type) {
          case "int" -> Long.parseLong(value);
          case "double" -> Double.parseDouble(value);
          case "bool" -> {
            if (!value.equals("true") && !value.equals("false")) {
              throw new IllegalArgumentException("must be true or false");
            }
            yield Boolean.parseBoolean(value);
          }
          case "string", "timestamp", "bytes" -> value;
          default -> throw new IllegalArgumentException("a " + type + " cannot be given here");
        };
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException("must be a " + type);
      }
    }
  }

  @Command(
      name = "views",
      description = "Lists the views of the ruleset in use: screens made of data and components.")
  static final class ViewsCommand extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Views());
      if (!(response instanceof Response.ViewList l)) {
        return exitCodeFor(response);
      }
      if (l.views().isEmpty()) {
        out().println("No views. A layer adds one as views/<id>.toml.");
      }
      for (Response.ViewList.ViewInfo v : l.views()) {
        out().printf("%s  %s  [schema %d, layer %s]%n", v.id(), v.title(), v.schema(), v.owner());
        if (v.description() != null) {
          out().printf("    %s%n", v.description());
        }
        for (Response.ViewList.Param p : v.params()) {
          out().printf("    %s (%s), default %s%n", p.name(), p.type(), p.value());
        }
      }
      return 0;
    }
  }

  @Command(
      name = "view",
      description =
          "Shows a view as text, with its data: the daemon runs its sources and evaluates it."
              + " The state of the view is set with name=value.")
  static final class ViewCommand extends Base {
    @CommandLine.Parameters(index = "0", paramLabel = "<view>", description = "The view id.")
    String view;

    @CommandLine.Parameters(index = "1..*", paramLabel = "<name=value>", description = "State.")
    java.util.List<String> state = new java.util.ArrayList<>();

    @Override
    public Integer call() {
      Response listed = send(new Request.Views());
      if (!(listed instanceof Response.ViewList l)) {
        return exitCodeFor(listed);
      }
      Response.ViewList.ViewInfo info =
          l.views().stream().filter(v -> v.id().equals(view)).findFirst().orElse(null);
      java.util.Map<String, Object> given = new java.util.LinkedHashMap<>();
      for (String a : state) {
        int eq = a.indexOf('=');
        if (eq < 1) {
          err().println("State is written name=value: " + a);
          return 2;
        }
        String name = a.substring(0, eq);
        String value = a.substring(eq + 1);
        String type =
            info == null
                ? "string"
                : info.params().stream()
                    .filter(p -> p.name().equals(name))
                    .map(Response.ViewList.Param::type)
                    .findFirst()
                    .orElse("string");
        try {
          given.put(
              name,
              switch (type) {
                case "int" -> Long.parseLong(value);
                case "double" -> Double.parseDouble(value);
                case "bool" -> Boolean.parseBoolean(value);
                default -> value;
              });
        } catch (NumberFormatException e) {
          err().println("State '" + name + "' must be a " + type + ": " + value);
          return 2;
        }
      }
      Response response = send(new Request.ViewResolve(view, given, 1));
      if (!(response instanceof Response.ResolvedView r)) {
        return exitCodeFor(response);
      }
      out().printf("== %s ==%n", r.title());
      r.params().forEach(p -> out().printf("   %s = %s%n", p.name(), p.value()));
      r.warnings().forEach(w -> out().printf("   ! %s%n", w));
      r.components().forEach(c -> show(c, 0));
      return 0;
    }

    private void showBlocks(java.util.List<Response.ViewComponent.Block> blocks, String pad) {
      for (Response.ViewComponent.Block b : blocks) {
        String text =
            b.runs().stream().map(Response.ViewComponent.Run::text).reduce("", String::concat);
        switch (b.kind()) {
          case "heading" ->
              out().printf("%s%s %s%n", pad, "#".repeat(Math.max(1, b.level())), text);
          case "rule" -> out().printf("%s----%n", pad);
          case "item" -> out().printf("%s- %s%n", pad, text);
          default -> {
            if (!text.isEmpty()) {
              out().printf("%s%s%s%n", pad, b.kind().equals("quote") ? "> " : "", text);
            }
          }
        }
        showBlocks(b.children(), pad + "  ");
      }
    }

    private void show(Response.ViewComponent c, int depth) {
      String pad = "  ".repeat(depth + 1);
      if (c.error() != null) {
        out().printf("%s[%s: %s]%n", pad, c.type(), c.error());
        return;
      }
      switch (c.type()) {
        case "text" -> out().printf("%s%s%n", pad, c.text());
        case "table", "list" -> {
          if (c.type().equals("table")) {
            out()
                .printf(
                    "%s| %s%n",
                    pad,
                    String.join(
                        " | ",
                        c.columns().stream().map(Response.ViewComponent.Column::title).toList()));
          }
          for (Response.ViewComponent.Row row : c.rows()) {
            out()
                .printf(
                    "%s| %s%s%n",
                    pad,
                    String.join(" | ", row.cells().stream().map(String::valueOf).toList()),
                    row.select() == null
                        ? ""
                        : "   [select " + row.select().param() + "=" + row.select().value() + "]");
            row.actions()
                .forEach(
                    a ->
                        out()
                            .printf(
                                "%s    -> %s (%s)%n",
                                pad,
                                a.label(),
                                a.download() != null
                                    ? "download " + a.download().filename()
                                    : a.command()));
          }
          if (c.rows().isEmpty() && c.empty() != null) {
            out().printf("%s%s%n", pad, c.empty());
          }
        }
        case "detail" ->
            c.fields().forEach(f -> out().printf("%s%s: %s%n", pad, f.label(), f.value()));
        case "button" ->
            out()
                .printf(
                    "%s[%s]%s%n",
                    pad, c.label(), Boolean.FALSE.equals(c.enabled()) ? " (disabled)" : "");
        case "input" ->
            out().printf("%s[%s: %s]%n", pad, c.label() == null ? "input" : c.label(), c.text());
        case "richtext" -> {
          showBlocks(c.rich().blocks(), pad);
          if (c.rich().blocked() > 0) {
            out().printf("%s(%d remote images are not loaded)%n", pad, c.rich().blocked());
          }
        }
        case "form" ->
            out()
                .printf(
                    "%sform for %s: %s%n",
                    pad,
                    c.form().command(),
                    String.join(
                        ", ",
                        c.form().fields().stream().map(f -> f.name() + "=" + f.value()).toList()));
        default -> {
          out().printf("%s%s:%n", pad, c.type());
          c.children().forEach(child -> show(child, depth + 1));
        }
      }
    }
  }

  @Command(
      name = "proposals",
      description =
          "Changes that an AI proposes as branches of a layer with ai_maintained = true. Without"
              + " a subcommand it lists the branches that are not part of the layer yet. Praksis"
              + " never merges or pushes: approving pins the layer to the commit you looked at.",
      subcommands = {
        Praksis.Proposals.Show.class,
        Praksis.Proposals.Approve.class,
        Praksis.Proposals.Reject.class
      })
  static final class Proposals extends Base {
    @Override
    public Integer call() throws InterruptedException {
      return runDaemonCommand("--admin", "proposals", "list");
    }

    @Command(
        name = "show",
        description =
            "Shows who changed what and why in a branch, runs the checks, the tests and the replay"
                + " on it, and says what it needs: your approval, shadow mode, or nothing more.")
    static final class Show extends Base {
      @CommandLine.Parameters(
          index = "0",
          paramLabel = "<layer>@<branch>",
          description = "The proposal.")
      String proposal;

      @Override
      public Integer call() throws InterruptedException {
        return runDaemonCommand("--admin", "proposals", "show", proposal);
      }
    }

    @Command(
        name = "approve",
        description =
            "Pins the layer to the commit of the branch, if the checks find no errors. You name the"
                + " commit you looked at, so a branch that moved since is not approved. Restart the"
                + " daemon afterwards; with shadow mode on, new and changed rules are observed first.")
    static final class Approve extends Base {
      @CommandLine.Parameters(
          index = "0",
          paramLabel = "<layer>@<branch>",
          description = "The proposal.")
      String proposal;

      @CommandLine.Parameters(
          index = "1",
          paramLabel = "<commit>",
          description = "The commit you looked at (7 characters at least).")
      String commit;

      @Option(
          names = "--without-shadow",
          description = "Approve although shadow mode is off, so new rules run at once.")
      boolean withoutShadow;

      @Override
      public Integer call() throws InterruptedException {
        java.util.List<String> args =
            new java.util.ArrayList<>(
                java.util.List.of("--admin", "proposals", "approve", proposal, commit));
        if (withoutShadow) {
          args.add("--without-shadow");
        }
        return runDaemonCommand(args.toArray(String[]::new));
      }
    }

    @Command(
        name = "reject",
        description =
            "Records that you do not want this branch. It is not listed again unless it changes.")
    static final class Reject extends Base {
      @CommandLine.Parameters(
          index = "0",
          paramLabel = "<layer>@<branch>",
          description = "The proposal.")
      String proposal;

      @CommandLine.Parameters(
          index = "1..*",
          paramLabel = "<reason>",
          description = "Why, for the audit log.")
      java.util.List<String> reason = new java.util.ArrayList<>();

      @Override
      public Integer call() throws InterruptedException {
        java.util.List<String> args =
            new java.util.ArrayList<>(
                java.util.List.of("--admin", "proposals", "reject", proposal));
        args.addAll(reason);
        return runDaemonCommand(args.toArray(String[]::new));
      }
    }
  }

  @Command(
      name = "shadow",
      description =
          "Shows the rules on trial: new and changed rules run in shadow mode first, which means"
              + " they are observed and do nothing. For each one: how many events it was seen with,"
              + " what it would have called and which rules really handled the same events.",
      subcommands = {Praksis.ShadowCommand.Promote.class, Praksis.ShadowCommand.Reset.class})
  static final class ShadowCommand extends Base {
    @Override
    public Integer call() {
      Response response = send(new Request.Shadow());
      if (!(response instanceof Response.ShadowList l)) {
        return exitCodeFor(response);
      }
      if (!l.enabled()) {
        out().println("Shadow mode is off. Turn it on with [shadow] enabled = true.");
      }
      if (l.rules().isEmpty()) {
        out().println("No rule is on trial.");
      }
      for (Response.ShadowList.Item r : l.rules()) {
        out()
            .printf(
                "%s  %s%n    since %s, %s%n    seen with %d event(s): would call for %d, would be"
                    + " refused for %d, would fail on %d%n    %s%n",
                r.rule(),
                r.replaces()
                    ? "(a changed rule: the older version runs meanwhile)"
                    : "(a new rule)",
                r.since(),
                r.status(),
                r.events(),
                r.calling(),
                r.denied(),
                r.failing(),
                r.hash());
        for (Response.ShadowList.Sample s : r.samples()) {
          out().printf("    event %s%n", s.event());
          s.calls().forEach(c -> out().printf("      would call   %s%n", c));
          if (!s.note().isEmpty()) {
            out().printf("      note         %s%n", s.note());
          }
          out()
              .printf(
                  "      really done  %s%n",
                  s.live().isEmpty() ? "(nothing)" : String.join(", ", s.live()));
        }
      }
      return 0;
    }

    @Command(
        name = "promote",
        description = "Ends the trial of a rule: its new version runs live from now on.")
    static final class Promote extends Base {
      @CommandLine.Parameters(index = "0", paramLabel = "<rule>", description = "The rule id.")
      String rule;

      @Override
      public Integer call() {
        Response response = send(new Request.ShadowPromote(rule));
        if (response instanceof Response.Done d) {
          out().println(d.message());
          return 0;
        }
        return exitCodeFor(response);
      }
    }

    @Command(
        name = "reset",
        description = "Forgets what a rule on trial was seen to do and starts the trial again.")
    static final class Reset extends Base {
      @CommandLine.Parameters(index = "0", paramLabel = "<rule>", description = "The rule id.")
      String rule;

      @Override
      public Integer call() {
        Response response = send(new Request.ShadowReset(rule));
        if (response instanceof Response.Done d) {
          out().println(d.message());
          return 0;
        }
        return exitCodeFor(response);
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
      subcommands = {
        Praksis.Modules.Lock.class,
        Praksis.Modules.Install.class,
        Praksis.Modules.Remove.class,
        Praksis.Modules.ListCommand.class
      })
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

    @Command(
        name = "install",
        description =
            "Installs a module from its archive (a .praksis-module file) in the home directory and"
                + " accepts it in modules.lock. A module is code that runs with the rights of the"
                + " daemon: install only what you trust. A module of the same name that ships with"
                + " the runtime image is hidden by it.")
    static final class Install extends Base {
      @Option(names = "--replace", description = "Replace the module of the same name in the home.")
      boolean replace;

      @CommandLine.Parameters(paramLabel = "<archive>", description = "The module archive.")
      String archive;

      @Override
      public Integer call() throws InterruptedException {
        return replace
            ? runDaemonCommand("--install-module", archive, "--replace")
            : runDaemonCommand("--install-module", archive);
      }
    }

    @Command(name = "remove", description = "Removes a module that was installed in the home.")
    static final class Remove extends Base {
      @CommandLine.Parameters(paramLabel = "<name>", description = "The name of the module.")
      String name;

      @Override
      public Integer call() throws InterruptedException {
        return runDaemonCommand("--remove-module", name);
      }
    }

    @Command(name = "list", description = "Lists the modules and where each one is.")
    static final class ListCommand extends Base {
      @Override
      public Integer call() throws InterruptedException {
        return runDaemonCommand("--list-modules");
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
                + " that results is valid and gives no rule access that no grant covers. Otherwise"
                + " the pins stay as they are.")
    static final class UpdateCommand extends Base {
      @Option(names = "--dry-run", description = "Only show what would change.")
      boolean dryRun;

      @Option(
          names = "--accept-new-access",
          description =
              "Move the pins although rules get access that no grant covers (their calls are"
                  + " denied until granted).")
      boolean acceptNewAccess;

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
        if (acceptNewAccess) {
          args.add("--accept-new-access");
        }
        args.addAll(names);
        return runDaemonCommand(args.toArray(String[]::new));
      }
    }
  }

  @Command(
      name = "verify",
      description =
          "Runs the checks on the ruleset that would run (or on a candidate, see the options), on its tests"
              + " and on policy.toml: it must be valid, every rule needs a passing test, rules may only"
              + " ask for what they call, and grants must name layers and capabilities that exist. Exit"
              + " code 1 if there are errors.")
  static final class Verify extends Base {
    @Option(
        names = "--replay",
        description =
            "Also run the stored events through the ruleset in use and through the candidate, and"
                + " list the events that they treat differently.")
    boolean replay;

    @Option(names = "-n", description = "How many of the newest stored events to replay (200).")
    Integer events;

    @Option(
        names = "--update",
        description = "Check the ruleset that 'layers update' would produce (asks the servers).")
    boolean update;

    @Option(
        names = "--layer",
        paramLabel = "<name>=<directory>|<name>=@<ref>",
        description =
            "Check with this layer replaced by a directory, or by a branch or tag of its"
                + " repository, e.g. an AI's branch of the private layer. Repeatable.")
    java.util.List<String> layers = new java.util.ArrayList<>();

    @Override
    public Integer call() throws InterruptedException {
      java.util.List<String> args =
          new java.util.ArrayList<>(java.util.List.of("--admin", "verify"));
      if (replay) {
        args.add("--replay");
      }
      if (events != null) {
        args.addAll(java.util.List.of("-n", String.valueOf(events)));
      }
      if (update) {
        args.add("--update");
      }
      for (String layer : layers) {
        args.addAll(java.util.List.of("--layer", layer));
      }
      return runDaemonCommand(args.toArray(String[]::new));
    }
  }

  @Command(
      name = "test",
      description =
          "Runs the tests in the tests/ directory of every layer on the real engine, with the"
              + " capabilities replaced by recorders. The tests of a layer run against the ruleset"
              + " up to that layer. Exit code 1 if a test fails or cannot be read.")
  static final class RunTests extends Base {
    @Override
    public Integer call() throws InterruptedException {
      return runDaemonCommand("--admin", "test");
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
