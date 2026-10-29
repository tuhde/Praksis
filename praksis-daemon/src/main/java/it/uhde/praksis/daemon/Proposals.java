// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.Trust;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Changes that an AI (or anyone) proposes as a branch of an AI-maintained layer. Praksis lists the
 * branches that are not part of the layer yet, shows what a branch would change (the checks, the
 * tier, the replay), and applies one only when the user approves exactly the commit they looked at:
 * the pin of the layer moves to that commit. Praksis never merges or pushes; getting the branch
 * into the layer\'s ref is the user\'s own action, and until then the next {@code praksis layers
 * update} moves the pin back to the ref.
 */
final class Proposals {
  private Proposals() {}

  /** A branch of an AI-maintained layer that is not part of what the layer is pinned to. */
  record Proposal(String layer, String branch, String commit, List<GitSource.Change> commits) {
    String name() {
      return layer + "@" + branch;
    }
  }

  /** Runs {@code proposals ...}; returns the exit code. */
  static int run(
      DaemonConfig config, GitSource git, List<String> args, PrintWriter out, PrintWriter err)
      throws DaemonException {
    try (Store store = Store.open(config.database())) {
      return run(config, git, args, out, err, store, null);
    } catch (StoreException e) {
      throw new DaemonException(e.getMessage(), e);
    }
  }

  /**
   * As above, with the store and the modules of the caller (the daemon uses its own; the command
   * line passes null for modules and loads them when it needs them).
   */
  static int run(
      DaemonConfig config,
      GitSource git,
      List<String> args,
      PrintWriter out,
      PrintWriter err,
      Store store,
      LoadedModules modules)
      throws DaemonException {
    String command = args.isEmpty() ? "list" : args.get(0);
    List<String> rest = args.isEmpty() ? List.of() : args.subList(1, args.size());
    Layers layers = new Layers(config, git);
    return switch (command) {
      case "list" -> list(config, layers, git, store, out, err);
      case "show" ->
          rest.size() == 1
              ? show(config, layers, git, store, rest.get(0), out, err, modules)
              : usage(err);
      case "approve" -> approve(config, layers, git, store, rest, out, err, modules);
      case "reject" -> rest.isEmpty() ? usage(err) : reject(config, git, store, rest, out, err);
      default -> usage(err);
    };
  }

  /** Runs the checks with the given modules, or loads them if there are none. */
  private static Admin.Checked checked(
      LoadedModules modules,
      DaemonConfig config,
      Layers layers,
      Admin.Check.Options options,
      PrintWriter err)
      throws DaemonException {
    return modules == null
        ? Admin.check(config, layers, options, err)
        : Admin.check(config, layers, options, err, modules);
  }

  /** Every proposal of the AI-maintained layers, with the reason there may be none. */
  static List<Proposal> pending(DaemonConfig config, Layers layers, GitSource git, Store store)
      throws Exception {
    List<Proposal> out = new ArrayList<>();
    for (LayerConfig layer : maintained(config)) {
      out.addAll(proposalsOf(layer, layers, git, store));
    }
    return out;
  }

  /** The proposal {@code layer@branch}, or null with the reason printed to {@code err}. */
  static Proposal lookup(
      String name, DaemonConfig config, Layers layers, GitSource git, Store store, PrintWriter err)
      throws Exception {
    return find(name, config, layers, git, store, err);
  }

  private static int usage(PrintWriter err) {
    err.println(
        "usage: proposals [list] | proposals show <layer>@<branch> | proposals approve"
            + " <layer>@<branch> <commit> [--without-shadow] | proposals reject <layer>@<branch>"
            + " [reason...]");
    return 2;
  }

  /** The decisions made so far: commit id to the decision, newest decision wins. */
  private static Map<String, String> decided(Store store) throws StoreException {
    Map<String, String> out = new LinkedHashMap<>();
    for (AuditEntry e : store.recentAudit(1000).reversed()) {
      if (e.kind() == AuditEntry.Kind.PROPOSAL) {
        String detail = e.detail();
        int at = detail.indexOf("\"commit\":\"");
        if (at >= 0) {
          String commit = detail.substring(at + 10, detail.indexOf('"', at + 10));
          out.put(commit, detail.contains("\"decision\":\"approved\"") ? "approved" : "rejected");
        }
      }
    }
    return out;
  }

  private static List<LayerConfig> maintained(DaemonConfig config) {
    return config.aiMaintainedLayers().stream().filter(LayerConfig::isGit).toList();
  }

  /** The proposals of one layer: branches other than its ref that are not merged, nor decided. */
  private static List<Proposal> proposalsOf(
      LayerConfig layer, Layers layers, GitSource git, Store store) throws Exception {
    Layers.Pin pin = layers.readLock().get(layer.name());
    if (pin == null) {
      throw new DaemonException(
          "layer '" + layer.name() + "' is not pinned yet. Run: praksis layers update");
    }
    Map<String, String> decided = decided(store);
    List<Proposal> out = new ArrayList<>();
    for (GitSource.Branch b : git.branches(layer.name(), layer.git().get())) {
      if (b.name().equals(layer.ref().get())
          || b.commit().equals(pin.commit())
          || git.isAncestor(layer.name(), b.commit(), pin.commit())
          || decided.containsKey(b.commit())) {
        continue;
      }
      out.add(
          new Proposal(
              layer.name(),
              b.name(),
              b.commit(),
              git.commits(layer.name(), pin.commit(), b.commit(), 20)));
    }
    return out;
  }

  private static int list(
      DaemonConfig config,
      Layers layers,
      GitSource git,
      Store store,
      PrintWriter out,
      PrintWriter err)
      throws DaemonException {
    List<LayerConfig> maintained = maintained(config);
    if (maintained.isEmpty()) {
      out.println(
          "No git layer is AI-maintained. Set ai_maintained = true on the [[layer]] that an AI"
              + " may propose changes to.");
      return 0;
    }
    Trust trust = Admin.trust(config);
    int found = 0;
    for (LayerConfig layer : maintained) {
      List<Proposal> proposals;
      try {
        proposals = proposalsOf(layer, layers, git, store);
      } catch (DaemonException e) {
        err.println(e.getMessage());
        return 1;
      } catch (Exception e) {
        err.println("layer '" + layer.name() + "': " + e.getMessage());
        return 1;
      }
      for (Proposal p : proposals) {
        found++;
        out.printf(
            "%s  %s  %d commit(s)%n", p.name(), p.commit().substring(0, 10), p.commits().size());
        p.commits().stream()
            .limit(3)
            .forEach(
                c ->
                    out.printf(
                        "    %s  %s (%s): %s%n",
                        c.commit().substring(0, 10),
                        c.authorName(),
                        new Provenance(layer.name(), "", Optional.of(c)).author(trust).label(),
                        c.subject()));
      }
    }
    if (found == 0) {
      out.println("No proposals: every branch is merged, decided, or there are none.");
    } else {
      out.println("Look at one with: praksis proposals show <layer>@<branch>");
    }
    return 0;
  }

  /** Finds the proposal {@code layer@branch}; null, with the reason printed, if there is none. */
  private static Proposal find(
      String name, DaemonConfig config, Layers layers, GitSource git, Store store, PrintWriter err)
      throws Exception {
    int at = name.indexOf('@');
    if (at < 1 || at == name.length() - 1) {
      err.println("name a proposal as <layer>@<branch>: " + name);
      return null;
    }
    String layerName = name.substring(0, at);
    String branch = name.substring(at + 1);
    LayerConfig layer =
        maintained(config).stream()
            .filter(l -> l.name().equals(layerName))
            .findFirst()
            .orElse(null);
    if (layer == null) {
      err.println(
          "layer '"
              + layerName
              + "' is not an AI-maintained git layer (ai_maintained = true), so it takes no proposals");
      return null;
    }
    // a branch that was decided is not a proposal any more, but it may still be looked at
    Layers.Pin pin = layers.readLock().get(layerName);
    if (pin == null) {
      err.println("layer '" + layerName + "' is not pinned yet. Run: praksis layers update");
      return null;
    }
    for (GitSource.Branch b : git.branches(layerName, layer.git().get())) {
      if (b.name().equals(branch)) {
        return new Proposal(
            layerName, branch, b.commit(), git.commits(layerName, pin.commit(), b.commit(), 20));
      }
    }
    err.println("layer '" + layerName + "' has no branch '" + branch + "'");
    return null;
  }

  private static int show(
      DaemonConfig config,
      Layers layers,
      GitSource git,
      Store store,
      String name,
      PrintWriter out,
      PrintWriter err,
      LoadedModules modules)
      throws DaemonException {
    try {
      Proposal p = find(name, config, layers, git, store, err);
      if (p == null) {
        return 1;
      }
      Trust trust = Admin.trust(config);
      out.printf("Proposal %s at %s%n", p.name(), p.commit());
      for (GitSource.Change c : p.commits()) {
        Provenance who = new Provenance(p.layer(), "", Optional.of(c));
        out.printf(
            "  %s  %s (%s%s): %s%n",
            c.commit().substring(0, 10),
            c.authorName(),
            who.author(trust).label(),
            c.signed() ? ", signed by " + c.signer() : ", not signed",
            c.subject());
        who.reasoning().lines().limit(8).forEach(l -> out.println("      | " + l));
      }
      if (p.commits().isEmpty()) {
        out.println("  (nothing that the layer does not have already)");
      }
      Admin.Checked checked =
          checked(
              modules,
              config,
              layers,
              Admin.Check.Options.of(Map.of(p.layer(), "@" + p.commit()), true),
              err);
      if (checked == null) {
        return 1;
      }
      Admin.print(checked, out);
      if (checked.report().ok()) {
        out.printf(
            "To approve exactly this: praksis proposals approve %s %s%n",
            p.name(), p.commit().substring(0, 10));
        return 0;
      }
      out.println("It cannot be approved while there are errors.");
      return 1;
    } catch (DaemonException e) {
      throw e;
    } catch (Exception e) {
      throw new DaemonException(e.getMessage(), e);
    }
  }

  private static int approve(
      DaemonConfig config,
      Layers layers,
      GitSource git,
      Store store,
      List<String> args,
      PrintWriter out,
      PrintWriter err,
      LoadedModules modules)
      throws DaemonException {
    boolean withoutShadow = args.contains("--without-shadow");
    List<String> positional = args.stream().filter(a -> !a.startsWith("--")).toList();
    if (positional.size() != 2
        || args.stream().anyMatch(a -> a.startsWith("--") && !a.equals("--without-shadow"))) {
      return usage(err);
    }
    String seen = positional.get(1);
    try {
      Proposal p = find(positional.get(0), config, layers, git, store, err);
      if (p == null) {
        return 1;
      }
      if (seen.length() < 7 || !p.commit().startsWith(seen)) {
        err.printf(
            "Not approved: the branch is at %s, not at %s. Look at it again with: praksis"
                + " proposals show %s%n",
            p.commit().substring(0, 10), seen, p.name());
        return 1;
      }
      Admin.Checked checked =
          checked(
              modules,
              config,
              layers,
              Admin.Check.Options.of(Map.of(p.layer(), "@" + p.commit()), true),
              err);
      if (checked == null) {
        return 1;
      }
      Admin.print(checked, out);
      if (!checked.report().ok()) {
        err.println("Not approved: the checks found errors.");
        return 1;
      }
      Tiers.Tier tier = checked.verdict().tier();
      if (tier == Tiers.Tier.SHADOW && !config.shadow().enabled() && !withoutShadow) {
        err.println(
            "Not approved: these rules should run in shadow mode first, and shadow mode is off."
                + " Turn it on with [shadow] enabled = true, or approve with --without-shadow.");
        return 1;
      }
      layers.pin(p.layer(), p.commit());
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("decision", "approved");
      detail.put("layer", p.layer());
      detail.put("branch", p.branch());
      detail.put("commit", p.commit());
      detail.put("tier", tier.name());
      detail.put("shadow", config.shadow().enabled() ? "on" : withoutShadow ? "skipped" : "off");
      detail.put(
          "verified",
          checked.report().errors()
              + " error(s), "
              + checked.report().warnings()
              + " warning(s); tests and coverage checked");
      detail.put(
          "rules",
          String.join(
              ", ",
              checked.verdict().decisions().stream()
                  .map(d -> d.rule() + " (" + d.change() + ", " + d.tier() + ")")
                  .toList()));
      detail.put(
          "commits",
          String.join(
              "; ",
              p.commits().stream()
                  .map(c -> c.commit().substring(0, 10) + " " + c.authorName() + ": " + c.subject())
                  .toList()));
      store.audit(
          AuditEntry.of(
              Instant.now(), AuditEntry.Kind.PROPOSAL, Optional.empty(), p.name(), detail));
      out.printf(
          "Approved %s: layer '%s' is pinned to %s.%n",
          p.name(), p.layer(), p.commit().substring(0, 10));
      out.println("Restart the daemon to use it.");
      if (tier == Tiers.Tier.SHADOW && config.shadow().enabled()) {
        out.println("The new and changed rules run in shadow mode first: see praksis shadow.");
      }
      out.printf(
          "Merge the branch into '%s' and push it yourself; until then the next 'praksis layers"
              + " update' moves the pin back to the latest commit of '%s'.%n",
          config.layers().stream()
              .filter(l -> l.name().equals(p.layer()))
              .findFirst()
              .get()
              .ref()
              .get(),
          config.layers().stream()
              .filter(l -> l.name().equals(p.layer()))
              .findFirst()
              .get()
              .ref()
              .get());
      return 0;
    } catch (DaemonException | StoreException e) {
      throw e instanceof DaemonException d ? d : new DaemonException(e.getMessage(), e);
    } catch (Exception e) {
      throw new DaemonException(e.getMessage(), e);
    }
  }

  private static int reject(
      DaemonConfig config,
      GitSource git,
      Store store,
      List<String> args,
      PrintWriter out,
      PrintWriter err)
      throws DaemonException {
    try {
      Layers layers = new Layers(config, git);
      Proposal p = find(args.get(0), config, layers, git, store, err);
      if (p == null) {
        return 1;
      }
      String reason = String.join(" ", args.subList(1, args.size()));
      Map<String, Object> detail = new LinkedHashMap<>();
      detail.put("decision", "rejected");
      detail.put("layer", p.layer());
      detail.put("branch", p.branch());
      detail.put("commit", p.commit());
      detail.put("reason", reason);
      store.audit(
          AuditEntry.of(
              Instant.now(), AuditEntry.Kind.PROPOSAL, Optional.empty(), p.name(), detail));
      out.printf(
          "Rejected %s at %s. It is not listed again unless the branch changes.%n",
          p.name(), p.commit().substring(0, 10));
      return 0;
    } catch (DaemonException e) {
      throw e;
    } catch (Exception e) {
      throw new DaemonException(e.getMessage(), e);
    }
  }
}
