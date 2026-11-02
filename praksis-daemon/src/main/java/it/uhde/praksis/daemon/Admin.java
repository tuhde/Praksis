// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.engine.Replay;
import it.uhde.praksis.engine.RuleTestRunner;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Permissions;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetValidator;
import it.uhde.praksis.ruleset.Trust;
import it.uhde.praksis.ruleset.Verifier;
import it.uhde.praksis.store.Store;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Commands that work on the home directory without a running daemon:
 *
 * <pre>
 * layers status                          which commit each layer is pinned to
 * layers update [--dry-run] [--accept-new-access] [name...]
 *                                        move pins to the latest commit, if the result is valid
 *                                        and gives no rule access that no grant covers
 * effective                              the ruleset that would run, and where each rule comes from
 * verify [--replay] [-n N] [--update] [--layer name=dir|@ref]...
 *                                        the checks on that ruleset (or on a candidate: the
 *                                        pins moved with --update, layers replaced by a directory
 *                                        or a ref), on its tests and on policy.toml; --replay
 *                                        also runs stored events through both and shows the
 *                                        differences
 * test                                   run the tests (tests/*.toml) of every layer
 * </pre>
 */
final class Admin {
  private Admin() {}

  /** Runs a command and returns the exit code: 0 done, 1 failed, 2 wrong usage. */
  static int run(
      DaemonConfig config,
      GitSource git,
      Function<Ruleset, List<Problem>> validator,
      List<String> args,
      PrintWriter out,
      PrintWriter err) {
    return run(config, git, validator, Layers.AccessCheck.NONE, args, out, err);
  }

  /** As above, with the check of the access that rulesets need against the grants. */
  static int run(
      DaemonConfig config,
      GitSource git,
      Function<Ruleset, List<Problem>> validator,
      Layers.AccessCheck access,
      List<String> args,
      PrintWriter out,
      PrintWriter err) {
    Layers layers = new Layers(config, git);
    try {
      if (args.equals(List.of("layers", "status"))) {
        return status(config, layers, out);
      }
      if (args.size() >= 2 && args.get(0).equals("layers") && args.get(1).equals("update")) {
        boolean dryRun = false;
        boolean acceptNewAccess = false;
        Set<String> names = new LinkedHashSet<>();
        for (String a : args.subList(2, args.size())) {
          if (a.equals("--dry-run")) {
            dryRun = true;
          } else if (a.equals("--accept-new-access")) {
            acceptNewAccess = true;
          } else {
            names.add(a);
          }
        }
        return update(layers, names, dryRun, acceptNewAccess, validator, access, out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("identity")) {
        return identity(args.subList(1, args.size()), out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("web")) {
        return web(config, args.subList(1, args.size()), out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("peers")) {
        return peers(config, args.subList(1, args.size()), out, err);
      }
      if (args.equals(List.of("effective"))) {
        return effective(config, layers, out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("proposals")) {
        return Proposals.run(config, git, args.subList(1, args.size()), out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("agent")) {
        return AgentCommands.run(config, layers, args.subList(1, args.size()), out, err);
      }
      if (!args.isEmpty() && args.get(0).equals("verify")) {
        return verify(config, layers, args.subList(1, args.size()), out, err);
      }
      if (args.equals(List.of("test"))) {
        return test(config, layers, out, err);
      }
    } catch (DaemonException e) {
      err.println(e.getMessage());
      return 1;
    }
    err.println(
        "usage: web token [create <name> [--read-only]|list|revoke <name>] | identity link <homeA> <nameA> <homeB> <nameB> | identity check <name> <home>... | peers [list|show-key|add <name> <key> [--socket path] [--standing-read]|remove <name>|catalog <name>|call <name> <capability> [--arg k=v]... [--dry-run]|pins [name]|pin <name> <set>|accept-pin <name> <set>|unpin <name> <set>] | layers status | layers update [--dry-run] [--accept-new-access] [name...] | proposals [...] | agent [...] | effective | verify [--replay] [-n N] [--update] [--layer name=dir|@ref] | test");
    return 2;
  }

  /** The tokens of web clients. */
  private static int web(DaemonConfig config, List<String> args, PrintWriter out, PrintWriter err) {
    if (args.isEmpty() || !args.get(0).equals("token")) {
      err.println("usage: web token [create <name> [--read-only]|list|revoke <name>]");
      return 2;
    }
    WebTokens tokens = new WebTokens(config.home());
    try {
      String sub = args.size() < 2 ? "list" : args.get(1);
      switch (sub) {
        case "create" -> {
          if (args.size() < 3) {
            err.println("usage: web token create <name> [--read-only]");
            return 2;
          }
          boolean readOnly = args.contains("--read-only");
          String secret = tokens.create(args.get(2), readOnly, java.time.Instant.now());
          out.println(secret);
          out.println(
              "This is shown once; only a hash is kept. Give it to the client '"
                  + args.get(2)
                  + "'; revoke it with: praksis web token revoke "
                  + args.get(2));
          return 0;
        }
        case "list" -> {
          List<WebTokens.Token> all = tokens.list();
          if (all.isEmpty()) {
            out.println("no web tokens");
          }
          for (WebTokens.Token t : all) {
            out.println(t.name() + (t.readOnly() ? " (read only)" : "") + "  made " + t.created());
          }
          return 0;
        }
        case "revoke" -> {
          if (args.size() != 3) {
            err.println("usage: web token revoke <name>");
            return 2;
          }
          tokens.revoke(args.get(2));
          out.println("revoked " + args.get(2));
          return 0;
        }
        default -> {
          err.println("usage: web token [create <name> [--read-only]|list|revoke <name>]");
          return 2;
        }
      }
    } catch (DaemonException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /** Tools for people who run one daemon per role. */
  private static int identity(List<String> args, PrintWriter out, PrintWriter err) {
    try {
      if (!args.isEmpty() && args.get(0).equals("link") && args.size() == 5) {
        IdentityTools.link(
                java.nio.file.Path.of(args.get(1)),
                args.get(2),
                java.nio.file.Path.of(args.get(3)),
                args.get(4))
            .forEach(out::println);
        out.println(
            "Nothing is allowed yet: add a [[peer_grant]] to the policy.toml of each side for what"
                + " the other may do.");
        return 0;
      }
      if (!args.isEmpty() && args.get(0).equals("check") && args.size() >= 3) {
        java.util.Map<String, java.util.Map<String, java.util.Map<String, Object>>> settings =
            new java.util.TreeMap<>();
        for (int i = 1; i + 1 < args.size(); i += 2) {
          settings.put(
              args.get(i),
              DaemonConfig.load(java.nio.file.Path.of(args.get(i + 1))).moduleSettings());
        }
        List<String> shared = IdentityTools.sharedAccounts(settings);
        if (shared.isEmpty()) {
          out.println("No account or place is used by two identities.");
          return 0;
        }
        shared.forEach(line -> out.println("warning: " + line));
        out.println(
            "A mailbox belongs to exactly one identity: two daemons on the same one would both act"
                + " on it.");
        return 1;
      }
      err.println(
          "usage: identity link <homeA> <nameA> <homeB> <nameB> | identity check <name> <home>...");
      return 2;
    } catch (java.io.IOException | DaemonException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  private static int peers(
      DaemonConfig config, List<String> args, PrintWriter out, PrintWriter err) {
    Peers peers = new Peers(config.home());
    try {
      String sub = args.isEmpty() ? "list" : args.get(0);
      switch (sub) {
        case "show-key" -> {
          String key = peers.publicKey();
          out.println(key);
          out.println("fingerprint " + Peers.fingerprint(key));
          return 0;
        }
        case "list" -> {
          List<Peers.Peer> all = peers.list();
          if (all.isEmpty()) {
            out.println("no peers");
          }
          for (Peers.Peer p : all) {
            out.println(
                p.name()
                    + "  "
                    + p.fingerprint()
                    + "  "
                    + p.socket().orElse("(connects to us)")
                    + (p.standingRead() ? "  standing read" : ""));
          }
          return 0;
        }
        case "add" -> {
          if (args.size() < 3) {
            err.println("usage: peers add <name> <key> [--socket path] [--standing-read]");
            return 2;
          }
          java.util.Optional<String> socket = java.util.Optional.empty();
          boolean standing = false;
          for (int i = 3; i < args.size(); i++) {
            if (args.get(i).equals("--standing-read")) {
              standing = true;
            } else if (args.get(i).equals("--socket") && i + 1 < args.size()) {
              socket = java.util.Optional.of(args.get(++i));
            } else {
              err.println("unknown option " + args.get(i));
              return 2;
            }
          }
          peers.add(args.get(1), args.get(2), socket, standing);
          out.println("added " + args.get(1) + "  " + Peers.fingerprint(args.get(2)));
          return 0;
        }
        case "catalog" -> {
          if (args.size() != 2) {
            err.println("usage: peers catalog <name>");
            return 2;
          }
          return catalog(config, peers, args.get(1), out, err);
        }
        case "pins" -> {
          return pins(config, args.subList(1, args.size()), out, err);
        }
        case "pin", "accept-pin", "unpin" -> {
          return pin(config, sub, args.subList(1, args.size()), out, err);
        }
        case "call" -> {
          return call(config, peers, args.subList(1, args.size()), out, err);
        }
        case "remove" -> {
          if (args.size() != 2) {
            err.println("usage: peers remove <name>");
            return 2;
          }
          if (!peers.remove(args.get(1))) {
            err.println("no peer named " + args.get(1));
            return 1;
          }
          out.println("removed " + args.get(1));
          return 0;
        }
        default -> {
          err.println(
              "usage: peers [list|show-key|add <name> <key> [--socket path] [--standing-read]|remove <name>]");
          return 2;
        }
      }
    } catch (java.io.IOException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /** Lists the pinned remote sets and how the remotes' sets differ from them now. */
  private static int pins(DaemonConfig config, List<String> args, PrintWriter out, PrintWriter err)
      throws java.io.IOException {
    RemotePins pins = new RemotePins(config.home());
    RemoteCatalogs catalogs = new RemoteCatalogs(config.home());
    if (pins.pins().isEmpty()) {
      out.println("no pinned remote sets");
    }
    for (RemotePins.Pin pin : pins.pins().values()) {
      if (!args.isEmpty() && !args.get(0).equals(pin.peer())) {
        continue;
      }
      out.println(pin.name() + "  " + pin.members() + "  approved " + pin.approved());
      var remote = catalogs.cached(pin.peer());
      if (remote.isEmpty()) {
        out.println("  (no catalog of " + pin.peer() + " to compare with)");
        continue;
      }
      for (var p : pins.pending(pin.peer(), remote.get().catalog())) {
        if (p.pin().set().equals(pin.set()) && p.changed()) {
          out.println(
              p.gone()
                  ? "  CHANGED: the set is no longer offered"
                  : "  CHANGED: adds "
                      + p.added()
                      + ", removes "
                      + p.removed()
                      + (remote.get().stale() ? "  (catalog is stale)" : "")
                      + "  -> praksis peers accept-pin "
                      + pin.peer()
                      + " "
                      + pin.set());
        }
      }
    }
    return 0;
  }

  private static int pin(
      DaemonConfig config, String sub, List<String> args, PrintWriter out, PrintWriter err)
      throws java.io.IOException {
    if (args.size() != 2) {
      err.println("usage: peers " + sub + " <name> <set>");
      return 2;
    }
    RemotePins pins = new RemotePins(config.home());
    if (sub.equals("unpin")) {
      if (!pins.unpin(args.get(0), args.get(1))) {
        err.println(args.get(0) + "/" + args.get(1) + " is not pinned");
        return 1;
      }
      out.println("unpinned " + args.get(0) + "/" + args.get(1));
      return 0;
    }
    var remote = new RemoteCatalogs(config.home()).cached(args.get(0));
    if (remote.isEmpty()) {
      err.println("no catalog of " + args.get(0) + " (praksis peers catalog " + args.get(0) + ")");
      return 1;
    }
    if (remote.get().stale()) {
      err.println(
          "warning: the catalog of " + args.get(0) + " was fetched " + remote.get().fetched());
    }
    var pin =
        sub.equals("pin")
            ? pins.pin(args.get(0), args.get(1), remote.get().catalog(), java.time.Instant.now())
            : pins.accept(
                args.get(0), args.get(1), remote.get().catalog(), java.time.Instant.now());
    out.println(pin.name() + " is pinned to " + pin.members());
    out.println("run 'praksis reload' for a running daemon to use it");
    return 0;
  }

  /** A person calls a capability of a peer: no local policy applies, the remote's grants do. */
  private static int call(
      DaemonConfig config, Peers peers, List<String> args, PrintWriter out, PrintWriter err)
      throws java.io.IOException {
    if (args.size() < 2) {
      err.println("usage: peers call <name> <capability> [--arg k=v]... [--dry-run]");
      return 2;
    }
    java.util.Map<String, Object> given = new java.util.LinkedHashMap<>();
    boolean dryRun = false;
    for (int i = 2; i < args.size(); i++) {
      if (args.get(i).equals("--dry-run")) {
        dryRun = true;
      } else if (args.get(i).equals("--arg") && i + 1 < args.size()) {
        String kv = args.get(++i);
        int eq = kv.indexOf('=');
        if (eq < 1) {
          err.println("--arg needs name=value");
          return 2;
        }
        given.put(kv.substring(0, eq), literal(kv.substring(eq + 1)));
      } else {
        err.println("unknown option " + args.get(i));
        return 2;
      }
    }
    RemoteCalls calls =
        new RemoteCalls(
            peers,
            new RemoteCatalogs(config.home()),
            (rule, capability, a) -> AccessPolicy.Decision.allow(),
            (time, rule, capability, a, allowed, error) -> {},
            java.time.Clock.systemUTC(),
            (peer, capability) -> true); // a person at the socket accepts by calling
    try {
      var results = calls.call("user", args.get(0), args.get(1), given, dryRun);
      results.forEach((k, v) -> out.println(k + " = " + v));
      return 0;
    } catch (java.io.IOException e) {
      err.println(e.getMessage());
      return 1;
    }
  }

  /** A value given on the command line: a number or true/false if it reads as one. */
  private static Object literal(String text) {
    if (text.equals("true") || text.equals("false")) {
      return Boolean.parseBoolean(text);
    }
    try {
      return Long.parseLong(text);
    } catch (NumberFormatException e) {
      return text;
    }
  }

  private static int catalog(
      DaemonConfig config, Peers peers, String name, PrintWriter out, PrintWriter err)
      throws java.io.IOException {
    Peers.Peer peer = peers.byName(name).orElse(null);
    if (peer == null) {
      err.println("no peer named " + name);
      return 1;
    }
    RemoteCatalogs catalogs = new RemoteCatalogs(config.home());
    RemoteCatalogs.Remote remote;
    try (PeerLink link = PeerLink.connect(peers, peer)) {
      remote = catalogs.fetch(link);
    } catch (java.io.IOException e) {
      remote = catalogs.cached(name).orElse(null);
      if (remote == null) {
        err.println(e.getMessage());
        return 1;
      }
      err.println("warning: " + e.getMessage() + " (showing the catalog kept from before)");
    }
    out.println(
        name
            + (remote.stale() ? "  STALE, fetched " : "  fetched ")
            + remote.fetched()
            + (remote.dropped() > 0 ? "  (" + remote.dropped() + " entries left out)" : ""));
    var c = remote.catalog();
    for (var x : c.capabilities()) {
      out.println("capability  " + remote.qualified(x.name()) + "  " + x.effect());
    }
    for (var t : c.types()) {
      out.println("type        " + remote.qualified(t.id()) + "  " + t.title());
    }
    for (var s : c.sets()) {
      out.println(
          "set         "
              + remote.qualified(s.id())
              + "  "
              + s.members().stream().map(remote::qualified).toList());
    }
    RemoteFeed feed = new RemoteFeed(config.home(), name, java.time.Clock.systemUTC(), t -> {});
    feed.gone()
        .forEach(
            (key, since) -> out.println("unavailable  " + key + "  not offered since " + since));
    return 0;
  }

  private static int status(DaemonConfig config, Layers layers, PrintWriter out)
      throws DaemonException {
    var pins = layers.readLock();
    if (config.layers().isEmpty()) {
      out.println("no layers configured: the directory " + config.ruleset() + " is the ruleset");
      return 0;
    }
    for (LayerConfig layer : config.layers()) {
      if (!layer.isGit()) {
        out.println(
            layer.name()
                + ": directory "
                + layer.path().get()
                + " (not pinned)"
                + (layer.aiMaintained() ? " [AI-maintained]" : ""));
        continue;
      }
      var pin = pins.get(layer.name());
      String where = layer.git().get() + " @ " + layer.ref().get();
      if (pin == null) {
        out.println(
            layer.name()
                + ": "
                + where
                + " - not pinned yet, run: praksis layers update"
                + (layer.aiMaintained() ? " [AI-maintained]" : ""));
      } else {
        boolean stale =
            !pin.git().equals(layer.git().get()) || !pin.ref().equals(layer.ref().get());
        boolean present =
            java.nio.file.Files.isDirectory(
                config.snapshots().resolve(layer.name()).resolve(pin.commit()));
        out.println(
            layer.name()
                + ": "
                + where
                + " - pinned to "
                + Layers.abbreviate(pin.commit())
                + (stale ? " (the configuration changed since, run: praksis layers update)" : "")
                + (present ? "" : " (files not on disk, fetched when needed)")
                + (layer.aiMaintained() ? " [AI-maintained]" : ""));
      }
    }
    return 0;
  }

  private static int update(
      Layers layers,
      Set<String> names,
      boolean dryRun,
      boolean acceptNewAccess,
      Function<Ruleset, List<Problem>> validator,
      Layers.AccessCheck access,
      PrintWriter out,
      PrintWriter err)
      throws DaemonException {
    Layers.Update result = layers.update(names, dryRun, acceptNewAccess, validator, access);
    if (result.refused()) {
      result.report().forEach(err::println);
      return 1;
    }
    if (!result.problems().isEmpty()) {
      err.println("Not updated: the ruleset that would result has problems.");
      result.problems().forEach(p -> err.println("  " + p));
      err.println("The pins are unchanged.");
      return 1;
    }
    result.report().forEach(out::println);
    return 0;
  }

  private static int effective(DaemonConfig config, Layers layers, PrintWriter out, PrintWriter err)
      throws DaemonException {
    LayerStack.Effective e;
    try {
      e = layers.effective();
    } catch (RulesetException ex) {
      err.println("The layers cannot be put together:");
      ex.problems().forEach(p -> err.println("  " + p));
      return 1;
    }
    Trust trust = trust(config);
    java.util.Map<String, String> commits = layers.commits();
    out.println("ruleset: " + e.ruleset().name() + " (" + e.ruleset().rules().size() + " rules)");
    out.println("rules, in the order they fire:");
    for (LayerStack.Origin o : e.origins()) {
      out.println("  " + o.ruleId());
      out.println("      " + o.hash());
      out.println("      " + String.join("; ", o.history()));
      Provenance provenance = layers.provenance(o, commits);
      out.println("      " + provenance.describe(trust));
      provenance.reasoning().lines().limit(6).forEach(l -> out.println("        | " + l));
    }
    if (!e.disabled().isEmpty()) {
      out.println("disabled:");
      for (LayerStack.Disabled d : e.disabled()) {
        out.println(
            "  "
                + d.ruleId()
                + " by "
                + d.layer()
                + d.reason().map(r -> " (" + r + ")").orElse(""));
      }
    }
    if (!e.ruleset().params().isEmpty()) {
      out.println("parameters:");
      e.ruleset()
          .params()
          .forEach(
              p ->
                  out.println(
                      "  "
                          + p.name()
                          + " ("
                          + p.type().id()
                          + ") = "
                          + p.defaultValue().map(String::valueOf).orElse("(no value)")));
    }
    return 0;
  }

  /**
   * Runs the checks on the ruleset that would run, or on a candidate, with its tests and
   * policy.toml. For a candidate, access that is new compared with the ruleset in use and that no
   * grant covers is an error. With {@code --replay}, stored events go through both rulesets and the
   * events that they treat differently are listed.
   */
  private static int verify(
      DaemonConfig config, Layers layers, List<String> options, PrintWriter out, PrintWriter err)
      throws DaemonException {
    Check.Options parsed = Check.Options.parse(options, err);
    if (parsed == null) {
      return 2;
    }
    Checked checked = check(config, layers, parsed, err);
    if (checked == null) {
      return 1;
    }
    print(checked, out);
    return checked.report().ok() ? 0 : 1;
  }

  /** What the checks found. */
  record Checked(
      boolean candidate,
      LayerStack.Effective current,
      Layers.Candidate layers,
      Verifier.Report report,
      Tiers.Verdict verdict,
      Replay.Report replayed) {}

  /** Namespace for the options of the checks. */
  static final class Check {
    private Check() {}

    /**
     * @param replay whether to replay stored events
     * @param update whether the pins move to the latest commits first
     * @param count how many events to replay
     * @param replacements layer name to a directory or {@code @ref}
     */
    record Options(
        boolean replay, boolean update, int count, java.util.Map<String, String> replacements) {
      private static final String USAGE =
          "usage: verify [--replay] [-n N] [--update] [--layer name=dir|@ref]";

      static Options of(java.util.Map<String, String> replacements, boolean replay) {
        return new Options(replay, false, 200, replacements);
      }

      /**
       * Reads the options of the command line; null, with the reason printed, if they are wrong.
       */
      static Options parse(List<String> options, PrintWriter err) {
        boolean replay = false;
        boolean update = false;
        int count = 200;
        java.util.Map<String, String> replacements = new java.util.LinkedHashMap<>();
        for (int i = 0; i < options.size(); i++) {
          String o = options.get(i);
          switch (o) {
            case "--replay" -> replay = true;
            case "--update" -> update = true;
            case "-n", "--layer" -> {
              if (i + 1 >= options.size()) {
                err.println(USAGE);
                return null;
              }
              String value = options.get(++i);
              if (o.equals("-n")) {
                try {
                  count = Integer.parseInt(value);
                } catch (NumberFormatException ex) {
                  err.println("-n needs a number: " + value);
                  return null;
                }
              } else {
                int eq = value.indexOf('=');
                if (eq < 1) {
                  err.println("--layer needs name=directory or name=@ref: " + value);
                  return null;
                }
                replacements.put(value.substring(0, eq), value.substring(eq + 1));
              }
            }
            default -> {
              err.println(USAGE);
              return null;
            }
          }
        }
        return new Options(replay, update, count, replacements);
      }

      boolean candidate() {
        return update || !replacements.isEmpty();
      }
    }
  }

  /**
   * Runs the checks on the ruleset that would run, or on a candidate, with its tests and
   * policy.toml. For a candidate, access that is new compared with the ruleset in use and that no
   * grant covers is an error, and the tier of the change is worked out. With replay, stored events
   * go through both rulesets.
   *
   * @return the result, or null if the checks could not be run (the reason is printed)
   */
  static Checked check(DaemonConfig config, Layers layers, Check.Options options, PrintWriter err)
      throws DaemonException {
    try (LoadedModules modules =
        Remotes.add(
            config,
            Builtins.add(
                ModuleLoader.load(
                    config.moduleRoots(),
                    config.lockfile(),
                    config.moduleSettings(),
                    config.enabledModules()),
                new Agent()))) {
      return check(config, layers, options, err, modules);
    } catch (ModuleLoadException ex) {
      err.println("cannot check the ruleset against the modules: " + ex.getMessage());
      return null;
    }
  }

  /** As above, with the modules of the caller (the daemon uses the ones it has loaded). */
  static Checked check(
      DaemonConfig config,
      Layers layers,
      Check.Options options,
      PrintWriter err,
      LoadedModules modules)
      throws DaemonException {
    boolean isCandidate = options.candidate();
    LayerStack.Effective current;
    Layers.Candidate candidate;
    try {
      current = layers.effective();
      candidate =
          isCandidate
              ? layers.candidate(options.replacements(), options.update())
              : new Layers.Candidate(layers.sources(layers.readLock()), current, layers.commits());
    } catch (RulesetException ex) {
      err.println("The layers cannot be put together:");
      ex.problems().forEach(p -> err.println("  " + p));
      return null;
    }
    LayerStack.Effective e = candidate.effective();
    Replay.Report replayed = null;
    Tiers.Verdict verdict = null;
    List<Verifier.Finding> findings;
    {
      findings =
          new ArrayList<>(
              new Verifier(Daemon.compiler(config), modules.catalog())
                  .verify(e.ruleset())
                  .findings());
      try {
        Map<String, Set<String>> candidateSets = EntitySets.members(candidate.sources());
        Policy policy = EntitySets.policy(config, candidateSets);
        policy
            .check(
                modules.catalog(),
                config.layerNames(),
                config.storageComponents(),
                candidateSets.keySet())
            .forEach(p -> findings.add(policyFinding(p)));
        if (isCandidate) {
          // a set that grows gives the grants that name it access nobody decided on
          EntitySets.growth(
                  policy, EntitySets.members(layers.sources(layers.readLock())), candidateSets)
              .forEach(
                  g ->
                      findings.add(
                          new Verifier.Finding(
                              Verifier.Severity.ERROR,
                              "access",
                              "entity-sets",
                              "new access: " + g)));
        }
        Permissions.Needs needs = Permissions.of(e, modules.catalog());
        Permissions.Diff diff =
            isCandidate
                ? Permissions.diff(Permissions.of(current, modules.catalog()), needs)
                : new Permissions.Diff(List.of(), List.of());
        List<Permissions.Requirement> isNew = diff.added();
        if (isCandidate) {
          java.util.Map<String, String> now = layers.commits();
          verdict =
              Tiers.decide(
                  current,
                  e,
                  o -> layers.provenance(o, now),
                  o -> layers.provenance(o, candidate.commits()),
                  needs,
                  diff,
                  policy);
        }
        for (Permissions.Requirement r : Permissions.uncovered(needs.requirements(), policy)) {
          findings.add(
              new Verifier.Finding(
                  isNew.contains(r) ? Verifier.Severity.ERROR : Verifier.Severity.WARNING,
                  "access",
                  String.join(", ", needs.rules().get(r)),
                  (isNew.contains(r) ? "new access that no grant covers: " : "no grant covers ")
                      + r.describe()));
        }
      } catch (RulesetException ex) {
        ex.problems().forEach(p -> findings.add(policyFinding(p)));
      }
      findings.addAll(entityFindings(candidate.sources(), e));
      findings.addAll(testFindings(config, candidate.sources(), e, modules));
      if (options.replay()) {
        replayed = replay(config, current, e, options.count(), modules, err);
        if (replayed == null) {
          return null;
        }
      }
    }
    return new Checked(
        isCandidate, current, candidate, new Verifier.Report(findings), verdict, replayed);
  }

  /** Prints what the checks found: findings, the tier of a change, the replay. */
  static void print(Checked checked, PrintWriter out) {
    LayerStack.Effective e = checked.layers().effective();
    Verifier.Report report = checked.report();
    Tiers.Verdict verdict = checked.verdict();
    Replay.Report replayed = checked.replayed();
    out.printf(
        "%s %s (%d rules): %d error(s), %d warning(s)%n",
        checked.candidate() ? "Candidate" : "Ruleset",
        e.ruleset().name(),
        e.ruleset().rules().size(),
        report.errors(),
        report.warnings());
    for (Verifier.Finding f : report.findings()) {
      out.printf("  %-7s %-16s %s: %s%n", f.severity(), f.check(), f.where(), f.message());
    }
    if (verdict != null) {
      out.println(
          switch (verdict.tier()) {
            case APPROVAL -> "This change needs your approval:";
            case SHADOW -> "This change runs in shadow mode first (see: praksis shadow):";
            case AUTOMATIC -> "This change needs nothing more:";
          });
      if (verdict.decisions().isEmpty()) {
        out.println("  no rule is new, changed or removed");
      }
      for (Tiers.Decision d : verdict.decisions()) {
        out.printf(
            "  %-9s %s (%s): %s%n", d.tier(), d.rule(), d.change(), String.join("; ", d.reasons()));
      }
    }
    if (replayed != null) {
      out.printf(
          "Replay of %d stored event(s): %d treated differently%n",
          replayed.events(), replayed.differences().size());
      int shown = 0;
      for (Replay.Difference d : replayed.differences()) {
        if (shown++ == 20) {
          out.printf("  ... and %d more%n", replayed.differences().size() - 20);
          break;
        }
        out.printf("  event %s (%s)%n", d.event().id(), d.event().type());
        d.current().forEach(l -> out.println("    now:       " + l));
        if (d.current().isEmpty()) {
          out.println("    now:       (nothing)");
        }
        d.candidate().forEach(l -> out.println("    candidate: " + l));
        if (d.candidate().isEmpty()) {
          out.println("    candidate: (nothing)");
        }
      }
    }
  }

  /** Stored events through the ruleset in use and the candidate; null (reported) if impossible. */
  private static Replay.Report replay(
      DaemonConfig config,
      LayerStack.Effective current,
      LayerStack.Effective candidate,
      int count,
      LoadedModules modules,
      PrintWriter err)
      throws DaemonException {
    if (!java.nio.file.Files.exists(config.database())) {
      return new Replay.Report(0, List.of());
    }
    try (Store store = Store.open(config.database())) {
      return Replay.compare(
          current.ruleset(),
          candidate.ruleset(),
          store.recentEvents(count),
          modules.catalog(),
          Daemon.compiler(config));
    } catch (RulesetException ex) {
      err.println("The engine cannot take the ruleset:");
      ex.problems().forEach(p -> err.println("  " + p));
      return null;
    } catch (Exception ex) {
      err.println("cannot replay the stored events: " + ex.getMessage());
      return null;
    }
  }

  /**
   * Runs the tests of the layers and reports the ones that fail or cannot be read ({@code test}),
   * and every rule that no test of its owner layer makes fire ({@code coverage}): a rule that
   * nothing tests is an error, which also means that a changed rule needs a changed or new test.
   */
  private static List<Verifier.Finding> testFindings(
      DaemonConfig config,
      List<LayerStack.Source> sources,
      LayerStack.Effective e,
      LoadedModules modules)
      throws DaemonException {
    RuleTestRunner.Report tests =
        new RuleTestRunner(modules.catalog(), Daemon.compiler(config))
            .run(sources, java.util.Map.of());
    List<Verifier.Finding> findings = new ArrayList<>();
    for (Problem p : tests.problems()) {
      findings.add(
          new Verifier.Finding(Verifier.Severity.ERROR, "test", p.location(), p.message()));
    }
    for (RuleTestRunner.Result r : tests.results()) {
      if (!r.passed()) {
        findings.add(
            new Verifier.Finding(
                Verifier.Severity.ERROR,
                "test",
                r.layer() + ": tests/" + r.test().rule() + ".toml, '" + r.test().name() + "'",
                r.message()));
      }
    }
    java.util.Map<String, String> owners = new java.util.HashMap<>();
    e.origins().forEach(o -> owners.put(o.ruleId(), o.owner()));
    Set<String> covered = tests.covered(owners);
    for (LayerStack.Origin o : e.origins()) {
      if (!covered.contains(o.ruleId())) {
        findings.add(
            new Verifier.Finding(
                Verifier.Severity.ERROR,
                "coverage",
                "rules/" + o.ruleId() + ".toml",
                "no test of layer '"
                    + o.owner()
                    + "' makes this rule fire; add a test with a matching event to tests/"
                    + o.ruleId()
                    + ".toml in that layer"));
      }
    }
    return findings;
  }

  /** Runs the tests of the layers with recording capabilities. */
  private static int test(DaemonConfig config, Layers layers, PrintWriter out, PrintWriter err)
      throws DaemonException {
    List<LayerStack.Source> sources = layers.sources(layers.readLock());
    RuleTestRunner.Report report;
    try (LoadedModules modules =
        Remotes.add(
            config,
            Builtins.add(
                ModuleLoader.load(
                    config.moduleRoots(),
                    config.lockfile(),
                    config.moduleSettings(),
                    config.enabledModules()),
                new Agent()))) {
      report =
          new RuleTestRunner(modules.catalog(), Daemon.compiler(config))
              .run(sources, java.util.Map.of());
    } catch (ModuleLoadException ex) {
      err.println("cannot run the tests against the modules: " + ex.getMessage());
      return 1;
    }
    long passed = report.results().stream().filter(RuleTestRunner.Result::passed).count();
    out.printf(
        "Tests: %d run, %d passed, %d failed%s%n",
        report.results().size(),
        passed,
        report.results().size() - passed,
        report.problems().isEmpty() ? "" : ", " + report.problems().size() + " unreadable");
    for (RuleTestRunner.Result r : report.results()) {
      out.printf(
          "  %-4s %s  %s: %s%n",
          r.passed() ? "ok" : "FAIL", r.layer(), r.test().rule(), r.test().name());
      if (!r.passed()) {
        out.println("         " + r.message().replace("\n", "\n         "));
      }
    }
    report.problems().forEach(p -> out.println("  ERROR " + p));
    return report.ok() ? 0 : 1;
  }

  /** The entity types of the candidate, and what its rules write about them. */
  private static List<Verifier.Finding> entityFindings(
      List<LayerStack.Source> sources, LayerStack.Effective effective) {
    List<Verifier.Finding> findings = new ArrayList<>();
    try {
      it.uhde.praksis.entity.EntityCatalog.Loaded loaded =
          it.uhde.praksis.entity.EntityCatalog.load(Daemon.entitySources(sources));
      loaded
          .problems()
          .forEach(
              p ->
                  findings.add(
                      new Verifier.Finding(
                          Verifier.Severity.ERROR,
                          it.uhde.praksis.entity.EntityChecks.CHECK,
                          p.location(),
                          p.message())));
      findings.addAll(
          it.uhde.praksis.entity.EntityChecks.check(effective.ruleset().rules(), loaded.catalog()));
    } catch (java.io.IOException ex) {
      findings.add(
          new Verifier.Finding(
              Verifier.Severity.ERROR,
              it.uhde.praksis.entity.EntityChecks.CHECK,
              "entities",
              "cannot read the entity types: " + ex.getMessage()));
    }
    return findings;
  }

  private static Verifier.Finding policyFinding(Problem p) {
    return new Verifier.Finding(Verifier.Severity.ERROR, "policy", p.location(), p.message());
  }

  /**
   * Works out the access a ruleset needs and which of it the grants in policy.toml do not cover.
   */
  static Layers.AccessCheck accessFor(DaemonConfig config) {
    return (effective, sources) -> {
      try (LoadedModules modules =
          Remotes.add(
              config,
              Builtins.add(
                  ModuleLoader.load(
                      config.moduleRoots(),
                      config.lockfile(),
                      config.moduleSettings(),
                      config.enabledModules()),
                  new Agent()))) {
        Permissions.Needs needs = Permissions.of(effective, modules.catalog());
        List<Permissions.Requirement> uncovered =
            Permissions.uncovered(
                needs.requirements(), EntitySets.policy(config, EntitySets.members(sources)));
        return new Layers.Access(needs, uncovered);
      } catch (ModuleLoadException | RulesetException ex) {
        throw new DaemonException("cannot work out what the rules need: " + ex.getMessage(), ex);
      }
    };
  }

  /** Whose commits count as the user's own; nobody's if policy.toml cannot be read. */
  static Trust trust(DaemonConfig config) {
    try {
      return Policy.load(config.policy()).trust();
    } catch (RulesetException ex) {
      return Trust.none();
    }
  }

  /** Checks a ruleset against the loaded modules, as the daemon does when it starts. */
  static Function<Ruleset, List<Problem>> validatorFor(DaemonConfig config) {
    return ruleset -> {
      try (LoadedModules modules =
          Remotes.add(
              config,
              Builtins.add(
                  ModuleLoader.load(
                      config.moduleRoots(),
                      config.lockfile(),
                      config.moduleSettings(),
                      config.enabledModules()),
                  new Agent()))) {
        ExpressionCompiler compiler = Daemon.compiler(config);
        return new ArrayList<>(new RulesetValidator(compiler, modules.catalog()).validate(ruleset));
      } catch (ModuleLoadException | DaemonException ex) {
        return List.of(
            new Problem("modules", "cannot check the ruleset against them: " + ex.getMessage()));
      }
    };
  }
}
