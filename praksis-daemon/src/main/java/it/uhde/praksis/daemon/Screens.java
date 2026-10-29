// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.Engine;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.Replay;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Permissions;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetValidator;
import it.uhde.praksis.ruleset.Trust;
import it.uhde.praksis.ruleset.Verifier;
import it.uhde.praksis.store.Store;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The engine's own screens as data: what the admin commands print, in structures that a frontend
 * can show. Everything is worked out by the same code as the command line, with the modules the
 * daemon has already loaded.
 */
final class Screens {
  private final DaemonConfig config;
  private final Layers layers;
  private final GitSource git;
  private final Store store;
  private final LoadedModules modules;
  private final ExpressionCompiler compiler;
  private final LayerStack.Effective inUse;
  private final Engine engine;

  Screens(
      DaemonConfig config,
      Layers layers,
      GitSource git,
      Store store,
      LoadedModules modules,
      ExpressionCompiler compiler,
      LayerStack.Effective inUse,
      Engine engine) {
    this.config = config;
    this.layers = layers;
    this.git = git;
    this.store = store;
    this.modules = modules;
    this.compiler = compiler;
    this.inUse = inUse;
    this.engine = engine;
  }

  /** The ruleset in use, with where each rule comes from. */
  Response.EffectiveView effective() throws DaemonException {
    Trust trust = Admin.trust(config);
    Map<String, String> commits = layers.commits();
    java.util.Set<String> onTrial = engine.shadowRules();
    List<Response.EffectiveView.Rule> rules = new ArrayList<>();
    for (LayerStack.Origin o : inUse.origins()) {
      Provenance who = layers.provenance(o, commits);
      rules.add(
          new Response.EffectiveView.Rule(
              o.ruleId(),
              o.layer(),
              o.owner(),
              o.hash(),
              o.history(),
              who.author(trust).label(),
              who.describe(trust),
              who.reasoning(),
              onTrial.contains(o.ruleId()),
              triggerText(inUse.ruleset().rules(), o.ruleId())));
    }
    List<Response.EffectiveView.Param> params = new ArrayList<>();
    inUse
        .ruleset()
        .params()
        .forEach(
            p ->
                params.add(
                    new Response.EffectiveView.Param(
                        p.name(),
                        p.type().id(),
                        p.defaultValue().map(String::valueOf).orElse(""))));
    List<Response.EffectiveView.Disabled> disabled = new ArrayList<>();
    inUse
        .disabled()
        .forEach(
            d ->
                disabled.add(
                    new Response.EffectiveView.Disabled(
                        d.ruleId(), d.layer(), d.reason().orElse(""))));
    return new Response.EffectiveView(
        inUse.ruleset().name(), pinsChanged(), params, rules, disabled);
  }

  private static String triggerText(List<it.uhde.praksis.ruleset.Rule> rules, String id) {
    for (it.uhde.praksis.ruleset.Rule r : rules) {
      if (r.id().equals(id)) {
        return switch (r.trigger()) {
          case it.uhde.praksis.ruleset.Trigger.OnEvent e -> "event " + e.type();
          case it.uhde.praksis.ruleset.Trigger.OnCommand c -> "command " + c.title();
        };
      }
    }
    return "";
  }

  /** The commands that a person can start. */
  Response.CommandList commands() {
    Map<String, String> owners = new HashMap<>();
    inUse.origins().forEach(o -> owners.put(o.ruleId(), o.owner()));
    Map<String, String> disabled = engine.disabledRules();
    List<Response.CommandList.Command> out = new ArrayList<>();
    for (it.uhde.praksis.ruleset.Rule r : inUse.ruleset().rules()) {
      if (r.trigger() instanceof it.uhde.praksis.ruleset.Trigger.OnCommand c) {
        out.add(
            new Response.CommandList.Command(
                r.id(),
                c.title(),
                c.description().orElse(null),
                owners.getOrDefault(r.id(), ""),
                r.effect().id(),
                disabled.get(r.id()),
                c.params().stream()
                    .map(
                        p ->
                            new Response.CommandList.Param(
                                p.name(),
                                p.type().id(),
                                p.required(),
                                p.defaultValue().map(String::valueOf).orElse(null),
                                p.label().orElse(null),
                                p.widget().orElse(null)))
                    .toList()));
      }
    }
    return new Response.CommandList(out);
  }

  /** Starts a command: checks the arguments, hands the event to the engine and audits the start. */
  Response run(Request.RunCommand r, java.time.Clock clock)
      throws it.uhde.praksis.store.StoreException {
    it.uhde.praksis.ruleset.Rule rule =
        inUse.ruleset().rules().stream()
            .filter(x -> x.id().equals(r.command()))
            .findFirst()
            .orElse(null);
    if (rule == null || !(rule.trigger() instanceof it.uhde.praksis.ruleset.Trigger.OnCommand c)) {
      return new Response.Error("there is no command '" + r.command() + "'");
    }
    it.uhde.praksis.capability.Event event;
    try {
      event = Injection.command(rule, c, r.args(), clock);
    } catch (Injection.Invalid e) {
      return new Response.Error(e.getMessage());
    }
    Map<String, Object> detail = new java.util.LinkedHashMap<>();
    detail.put("event", event.id());
    detail.put("client", r.client() == null ? "unknown" : r.client());
    detail.put("args", String.valueOf(event.data()));
    store.audit(
        it.uhde.praksis.store.AuditEntry.of(
            clock.instant(),
            it.uhde.praksis.store.AuditEntry.Kind.COMMAND,
            java.util.Optional.of(rule.id()),
            rule.id(),
            detail));
    engine.submit(event);
    return new Response.CommandStarted(event.id());
  }

  /** What became of an event. */
  Response.Outcome outcome(String eventId) throws it.uhde.praksis.store.StoreException {
    List<Response.LogLines.Line> lines = new ArrayList<>();
    for (it.uhde.praksis.store.LogEntry e : store.logOf(eventId)) {
      lines.add(
          new Response.LogLines.Line(
              e.time().toString(),
              e.eventId(),
              e.ruleId().orElse(null),
              e.actionId().orElse(null),
              e.outcome().name(),
              e.message()));
    }
    return new Response.Outcome(store.isDone(eventId), lines);
  }

  /** Whether the pins now give another ruleset than the one the daemon runs. */
  private boolean pinsChanged() {
    try {
      Map<String, String> now = new HashMap<>();
      layers.effective().origins().forEach(o -> now.put(o.ruleId(), o.hash()));
      Map<String, String> running = new HashMap<>();
      inUse.origins().forEach(o -> running.put(o.ruleId(), o.hash()));
      return !now.equals(running);
    } catch (DaemonException | RulesetException e) {
      return true;
    }
  }

  /** The layers and what they are pinned to. */
  Response.LayerList layerList() throws DaemonException {
    List<Response.LayerList.Layer> out = new ArrayList<>();
    var pins = layers.readLock();
    if (config.layers().isEmpty()) {
      out.add(
          new Response.LayerList.Layer(
              "ruleset", "directory " + config.ruleset(), null, false, true, false));
    }
    for (LayerConfig layer : config.layers()) {
      if (!layer.isGit()) {
        out.add(
            new Response.LayerList.Layer(
                layer.name(),
                "directory " + layer.path().get(),
                null,
                false,
                true,
                layer.aiMaintained()));
        continue;
      }
      var pin = pins.get(layer.name());
      boolean stale =
          pin != null
              && (!pin.git().equals(layer.git().get()) || !pin.ref().equals(layer.ref().get()));
      boolean present =
          pin != null
              && java.nio.file.Files.isDirectory(
                  config.snapshots().resolve(layer.name()).resolve(pin.commit()));
      out.add(
          new Response.LayerList.Layer(
              layer.name(),
              layer.git().get() + " @ " + layer.ref().get(),
              pin == null ? null : pin.commit(),
              stale,
              present,
              layer.aiMaintained()));
    }
    return new Response.LayerList(out);
  }

  /** {@code layers update}: moves the pins if the result is valid and its access is covered. */
  Response.Lines update(Request.LayersUpdate r) throws DaemonException {
    Layers.Update result =
        layers.update(
            new java.util.LinkedHashSet<>(r.names()),
            r.dryRun(),
            r.acceptNewAccess(),
            ruleset ->
                new ArrayList<>(
                    new RulesetValidator(compiler, modules.catalog()).validate(ruleset)),
            effective -> {
              try {
                Permissions.Needs needs = Permissions.of(effective, modules.catalog());
                return new Layers.Access(
                    needs,
                    Permissions.uncovered(needs.requirements(), Policy.load(config.policy())));
              } catch (RulesetException e) {
                throw new DaemonException(
                    "cannot work out what the rules need: " + e.getMessage(), e);
              }
            });
    if (result.refused()) {
      return new Response.Lines(false, result.report());
    }
    if (!result.problems().isEmpty()) {
      List<String> lines = new ArrayList<>();
      lines.add("Not updated: the ruleset that would result has problems.");
      result.problems().forEach(p -> lines.add("  " + p));
      lines.add("The pins are unchanged.");
      return new Response.Lines(false, lines);
    }
    return new Response.Lines(true, result.report());
  }

  /** The checks on the ruleset in use or on a candidate. */
  Response verify(Request.Verify r) throws DaemonException {
    StringWriter err = new StringWriter();
    Admin.Checked checked =
        Admin.check(
            config,
            layers,
            new Admin.Check.Options(
                r.replay(), r.update(), r.count() > 0 ? r.count() : 200, r.replacements()),
            new PrintWriter(err, true),
            modules);
    return checked == null ? new Response.Error(err.toString().strip()) : verification(checked);
  }

  /** Turns the result of the checks into the structure of the protocol. */
  static Response.Verification verification(Admin.Checked checked) {
    Verifier.Report report = checked.report();
    LayerStack.Effective e = checked.layers().effective();
    List<Response.Verification.Finding> findings =
        report.findings().stream()
            .map(
                f ->
                    new Response.Verification.Finding(
                        f.severity().name(), f.check(), f.where(), f.message()))
            .toList();
    List<Response.Verification.Decision> decisions = null;
    String tier = null;
    if (checked.verdict() != null) {
      tier = checked.verdict().tier().name();
      decisions =
          checked.verdict().decisions().stream()
              .map(
                  d ->
                      new Response.Verification.Decision(
                          d.rule(), d.change(), d.tier().name(), d.reasons()))
              .toList();
    }
    Response.Verification.Replay replay = null;
    if (checked.replayed() != null) {
      List<Response.Verification.Difference> differences = new ArrayList<>();
      for (Replay.Difference d : checked.replayed().differences()) {
        if (differences.size() == 20) {
          break;
        }
        differences.add(
            new Response.Verification.Difference(
                d.event().id(), d.event().type(), d.current(), d.candidate()));
      }
      replay =
          new Response.Verification.Replay(
              checked.replayed().events(),
              differences,
              Math.max(0, checked.replayed().differences().size() - differences.size()));
    }
    return new Response.Verification(
        checked.candidate(),
        e.ruleset().name(),
        e.ruleset().rules().size(),
        report.errors(),
        report.warnings(),
        findings,
        tier,
        decisions,
        replay);
  }

  /** The proposals of the AI-maintained layers. */
  Response proposals() throws DaemonException {
    try {
      List<Response.ProposalList.Proposal> out = new ArrayList<>();
      Trust trust = Admin.trust(config);
      for (Proposals.Proposal p : Proposals.pending(config, layers, git, store)) {
        out.add(proposal(p, trust));
      }
      String note =
          config.aiMaintainedLayers().stream().noneMatch(LayerConfig::isGit)
              ? "No git layer is AI-maintained (ai_maintained = true)."
              : null;
      return new Response.ProposalList(out, note);
    } catch (DaemonException e) {
      throw e;
    } catch (Exception e) {
      return new Response.Error(e.getMessage());
    }
  }

  private static Response.ProposalList.Proposal proposal(Proposals.Proposal p, Trust trust) {
    List<Response.ProposalList.Commit> commits = new ArrayList<>();
    for (GitSource.Change c : p.commits()) {
      Provenance who = new Provenance(p.layer(), "", java.util.Optional.of(c));
      commits.add(
          new Response.ProposalList.Commit(
              c.commit(),
              c.authorName(),
              who.author(trust).label(),
              c.signed(),
              c.signed() ? c.signer() : "",
              c.subject(),
              who.reasoning()));
    }
    return new Response.ProposalList.Proposal(p.name(), p.layer(), p.branch(), p.commit(), commits);
  }

  /** One proposal with the checks on it. */
  Response proposalDetail(String name) throws DaemonException {
    StringWriter err = new StringWriter();
    PrintWriter errors = new PrintWriter(err, true);
    try {
      Proposals.Proposal p = Proposals.lookup(name, config, layers, git, store, errors);
      if (p == null) {
        return new Response.Error(err.toString().strip());
      }
      Admin.Checked checked =
          Admin.check(
              config,
              layers,
              Admin.Check.Options.of(Map.of(p.layer(), "@" + p.commit()), true),
              errors,
              modules);
      if (checked == null) {
        return new Response.Error(err.toString().strip());
      }
      return new Response.ProposalDetail(proposal(p, Admin.trust(config)), verification(checked));
    } catch (DaemonException e) {
      throw e;
    } catch (Exception e) {
      return new Response.Error(e.getMessage());
    }
  }

  /** Approves or rejects a proposal, with the same checks and words as the command line. */
  Response decide(List<String> args) throws DaemonException {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exit =
        Proposals.run(
            config,
            git,
            args,
            new PrintWriter(out, true),
            new PrintWriter(err, true),
            store,
            modules);
    List<String> lines = new ArrayList<>();
    out.toString().lines().forEach(lines::add);
    err.toString().lines().forEach(lines::add);
    return new Response.Lines(exit == 0, lines);
  }

  /** Problems as lines of text. */
  static List<String> lines(List<Problem> problems) {
    return problems.stream().map(Problem::toString).toList();
  }
}
