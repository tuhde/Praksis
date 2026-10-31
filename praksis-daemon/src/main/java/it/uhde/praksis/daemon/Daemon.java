// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.engine.CredentialResolver;
import it.uhde.praksis.engine.Engine;
import it.uhde.praksis.engine.GrantPolicy;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.engine.StoreAuditLog;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.git.GitCli;
import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.Trust;
import it.uhde.praksis.ruleset.Verifier;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The running system: modules, ruleset, store, engine and the local API.
 *
 * <p>Start order: load modules, check and compile the ruleset, open the store, queue what was left
 * unfinished, open the API, and only then start the event sources. Closing goes the other way.
 */
public final class Daemon implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(Daemon.class.getName());
  private static final int MAX_LOG_LINES = 1000;

  /** The limit is 108 on Linux and 104 on macOS, including the terminating zero. */
  private static final int MAX_SOCKET_PATH_BYTES = 100;

  private final DaemonConfig config;
  private final Clock clock = Clock.systemUTC();
  private final Instant startedAt = clock.instant();
  private final CountDownLatch stopped = new CountDownLatch(1);
  private final AtomicBoolean closed = new AtomicBoolean();

  private LoadedModules modules;
  private Ruleset ruleset;
  private Store store;
  private Engine engine;
  private Approvals approvals;
  private Shadows shadows;
  private Screens screens;
  private ViewResolver views;
  private final Topics topics = new Topics();
  private ApiServer api;

  private Daemon(DaemonConfig config) {
    this.config = config;
  }

  /**
   * Starts a daemon.
   *
   * @throws DaemonException if it cannot start; everything that was started is closed again
   */
  public static Daemon start(DaemonConfig config) throws DaemonException {
    Daemon daemon = new Daemon(config);
    try {
      daemon.open();
    } catch (DaemonException | RuntimeException e) {
      daemon.close();
      throw e;
    }
    return daemon;
  }

  private void open() throws DaemonException {
    int socketBytes =
        config.socket().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
    if (socketBytes > MAX_SOCKET_PATH_BYTES) {
      throw new DaemonException(
          "the socket path "
              + config.socket()
              + " is "
              + socketBytes
              + " bytes long, but Unix sockets allow about "
              + MAX_SOCKET_PATH_BYTES
              + ". Use a shorter home directory.");
    }
    refuseIfRunning();
    try {
      modules =
          ModuleLoader.load(
              config.moduleRoots(),
              config.lockfile(),
              config.moduleSettings(),
              config.enabledModules());
      Agent agent = new Agent();
      modules = Builtins.add(modules, agent);
      ExpressionCompiler compiler = compiler(config);
      Layers layers = new Layers(config, new GitCli(config.gitCache()));
      LayerStack.Effective effective = layers.effective();
      if (!effective.skipped().isEmpty()) {
        LOG.log(
            System.Logger.Level.INFO,
            "left out, because a module is not loaded: "
                + String.join(
                    "; ", effective.skipped().stream().map(LayerStack.Skipped::describe).toList()));
      }
      ruleset = effective.ruleset();
      GrantPolicy access = accessPolicy(config, modules.catalog(), effective);
      store = Store.open(config.database());
      agent.bind(
          config, layers, modules, store, () -> effective, access, new StoreAuditLog(store), clock);
      store.onChange(topics::bump);
      auditPolicy(config, store);
      auditRules(effective, layers, compiler, modules.catalog(), store, Admin.trust(config));
      modules.useCredentials(new CredentialResolver(access::policy, store, clock)::forComponent);
      modules.useDataDirectory(config.dataDirectory());
      Downloads.cleanup(config.downloadsDirectory(), clock.instant());
      Uploads.cleanup(config.uploadsDirectory(), clock.instant());
      modules.useUploadsDirectory(config.uploadsDirectory());
      approvals = new Approvals(config, store, access, modules.catalog(), clock);
      Map<String, Capability> capabilities = modules.capabilities();
      shadows = new Shadows(config, store, clock, effective);
      Shadows.Plan plan = shadows.plan();
      engine =
          new Engine(
              plan.live(),
              plan.trial(),
              modules.catalog(),
              compiler,
              capabilities,
              store,
              new Engine.Options(
                  access, new StoreAuditLog(store), clock, config.dryRun(), approvals));
      approvals.bind(engine);
      shadows.bind(engine);
      Map<String, String> viewOwners = new java.util.HashMap<>();
      effective.viewOrigins().forEach(v -> viewOwners.put(v.viewId(), v.owner()));
      views =
          new ViewResolver(
              effective.ruleset(),
              viewOwners,
              modules.catalog(),
              compiler,
              capabilities,
              access,
              new StoreAuditLog(store),
              clock);
      screens =
          new Screens(
              config,
              layers,
              new GitCli(config.gitCache()),
              store,
              modules,
              compiler,
              effective,
              engine,
              agent.plansOf());
      approvals.sync();
      engine.recover();
      api = new ApiServer(config.socket(), this::handle, this::requestStop);
      api.start();
      modules.startSources(engine);
    } catch (RulesetException e) {
      throw new DaemonException(
          "the ruleset is not valid:\n  "
              + String.join("\n  ", e.problems().stream().map(Problem::toString).toList()),
          e);
    } catch (ModuleLoadException | CapabilityException | StoreException | IOException e) {
      throw new DaemonException(e.getMessage(), e);
    }
    LOG.log(System.Logger.Level.INFO, "praksis daemon started, home " + config.home());
  }

  /**
   * Writes a {@code RULE} line to the audit log for every rule whose content differs from the last
   * line about it: who wrote it, why, and how the static checks judged the ruleset it is part of. A
   * change made in a layer shows up here at the next start, whoever made it.
   */
  static void auditRules(
      LayerStack.Effective effective,
      Layers layers,
      ExpressionCompiler compiler,
      ModuleCatalog catalog,
      Store store,
      Trust trust)
      throws StoreException, DaemonException {
    Verifier.Report report = null;
    Map<String, String> commits = layers.commits();
    for (LayerStack.Origin origin : effective.origins()) {
      var last = store.lastAuditForRule(AuditEntry.Kind.RULE, origin.ruleId());
      if (last.isPresent() && last.get().subject().equals(origin.hash())) {
        continue;
      }
      if (report == null) {
        report = new Verifier(compiler, catalog).verify(effective.ruleset());
      }
      Provenance provenance = layers.provenance(origin, commits);
      Map<String, Object> detail = new java.util.LinkedHashMap<>();
      detail.put("layer", origin.owner());
      detail.put("file", provenance.file());
      detail.put("history", String.join("; ", origin.history()));
      detail.put("author", provenance.author(trust).label());
      provenance
          .change()
          .ifPresent(
              c -> {
                detail.put("name", c.authorName());
                detail.put("email", c.authorEmail());
                detail.put("commit", c.commit());
                detail.put("subject", c.subject());
                detail.put("signed", c.signed() ? c.signer() : "no");
              });
      detail.put("reasoning", provenance.reasoning());
      detail.put(
          "verified",
          "static checks: " + report.errors() + " error(s), " + report.warnings() + " warning(s)");
      store.audit(
          AuditEntry.of(
              Instant.now(),
              AuditEntry.Kind.RULE,
              Optional.of(origin.ruleId()),
              origin.hash(),
              detail));
    }
  }

  /** The grants in {@code policy.toml}, checked against the modules and layers in use. */
  static GrantPolicy accessPolicy(
      DaemonConfig config, ModuleCatalog catalog, LayerStack.Effective effective)
      throws DaemonException {
    try {
      Policy policy = Policy.load(config.policy());
      List<Problem> problems = policy.check(catalog, config.layerNames());
      if (!problems.isEmpty()) {
        throw new RulesetException(problems);
      }
      Map<String, String> layers = new java.util.HashMap<>();
      effective.origins().forEach(o -> layers.put(o.ruleId(), o.owner()));
      effective.viewOrigins().forEach(v -> layers.put("view:" + v.viewId(), v.owner()));
      return new GrantPolicy(policy, layers);
    } catch (RulesetException e) {
      throw new DaemonException(
          "the policy is not valid:\n  "
              + String.join("\n  ", e.problems().stream().map(Problem::toString).toList()),
          e);
    }
  }

  /** The expression implementation: from its directory if configured, else from the class path. */
  static ExpressionCompiler compiler(DaemonConfig config) throws DaemonException {
    try {
      return config.expression().isPresent()
          ? ModuleLoader.loadExpressionCompiler(config.expression().get())
          : Expressions.compiler();
    } catch (ModuleLoadException e) {
      throw new DaemonException(e.getMessage(), e);
    }
  }

  /** Fails if another daemon answers on the socket; removes the socket of a dead one. */
  private void refuseIfRunning() throws DaemonException {
    if (!Files.exists(config.socket())) {
      return;
    }
    try (Client client = Client.connect(config.socket())) {
      client.send(new Request.Status());
      throw new DaemonException("a daemon is already running for " + config.home());
    } catch (IOException e) {
      try {
        Files.delete(config.socket());
      } catch (IOException ex) {
        throw new DaemonException("cannot remove stale socket " + config.socket(), ex);
      }
    }
  }

  private Response handle(Request request) {
    try {
      return switch (request) {
        case Request.Status s ->
            new Response.StatusReport(
                ProcessHandle.current().pid(),
                startedAt.toString(),
                ruleset.name(),
                ruleset.rules().size(),
                modules.descriptors().stream().map(ModuleDescriptor::name).toList(),
                store.pending().size());
        case Request.Inject i -> {
          Event event = Injection.event(i, modules.catalog(), clock);
          yield new Response.Injected(event.id(), engine.submit(event));
        }
        case Request.Log l ->
            new Response.LogLines(
                store.recentLog(Math.max(1, Math.min(l.limit(), MAX_LOG_LINES))).stream()
                    .map(Daemon::line)
                    .toList());
        case Request.Audit a ->
            new Response.AuditLines(
                store
                    .recentAudit(Math.max(1, Math.min(a.limit(), MAX_LOG_LINES)))
                    .reversed()
                    .stream()
                    .map(Daemon::auditLine)
                    .toList());
        case Request.Approvals a -> approvals.list();
        case Request.Answer a ->
            new Response.Done(approvals.answer(a.id(), a.decision(), a.missed()));
        case Request.Missed m -> new Response.Done(approvals.missed(m.rule(), m.action()));
        case Request.Grants g -> approvals.grants();
        case Request.Revoke r -> new Response.Done(approvals.revoke(r.id()));
        case Request.Reload r -> new Response.Done(approvals.reload());
        case Request.Shadow s -> shadows.list();
        case Request.ShadowPromote p -> new Response.Done(shadows.promote(p.rule()));
        case Request.ShadowReset r -> new Response.Done(shadows.reset(r.rule()));
        case Request.Changes c -> topics.changes(c.since(), c.waitMillis());
        case Request.Effective e -> screens.effective();
        case Request.Layers l -> screens.layerList();
        case Request.LayersUpdate u -> screens.update(u);
        case Request.Verify v -> screens.verify(v);
        case Request.Proposals p -> screens.proposals();
        case Request.ProposalShow p -> screens.proposalDetail(p.name());
        case Request.Staged s -> screens.staged();
        case Request.Untested u -> screens.untested();
        case Request.AgentPlans p -> screens.plans();
        case Request.AgentPlanShow p -> screens.planDetail(p.id());
        case Request.AgentPlanRun p -> screens.planRun(p.id());
        case Request.AgentPlanDrop p -> screens.planDrop(p.id());
        case Request.Notes n -> screens.notes(n.limit());
        case Request.StagedShow s -> screens.stagedDetail(s.id());
        case Request.StagedDrop s -> screens.stagedDrop(s.id());
        case Request.ProposalApprove a -> {
          java.util.List<String> args =
              new java.util.ArrayList<>(java.util.List.of("approve", a.name(), a.commit()));
          if (a.withoutShadow()) {
            args.add("--without-shadow");
          }
          yield screens.decide(args);
        }
        case Request.ProposalReject r ->
            screens.decide(
                java.util.List.of(
                    "reject",
                    r.name(),
                    r.reason() == null || r.reason().isBlank() ? "-" : r.reason()));
        case Request.Commands c -> screens.commands();
        case Request.RunCommand r -> screens.run(r, clock);
        case Request.Outcome o -> screens.outcome(o.event());
        case Request.Views v -> views.list();
        case Request.ViewResolve v -> views.resolve(v.view(), v.params(), v.maxSchema());
        case Request.Lookup l -> views.lookup(l.view(), l.capability(), l.args());
        case Request.Dictate d -> views.dictate(d.view(), d.capability());
        case Request.Upload u -> Uploads.ticket(config.uploadsDirectory(), clock.instant());
        case Request.Fetch f ->
            views.fetch(
                f.view(), f.capability(), f.args(), f.filename(), config.downloadsDirectory());
        case Request.Stop s -> new Response.Stopped();
      };
    } catch (Injection.Invalid | Approvals.Failure | Shadows.Failure | DaemonException e) {
      return new Response.Error(e.getMessage());
    } catch (StoreException e) {
      return new Response.Error("store: " + e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Response.Error("interrupted");
    }
  }

  private static Response.AuditLines.Line auditLine(AuditEntry e) {
    return new Response.AuditLines.Line(
        e.time().toString(),
        e.kind().name(),
        e.ruleId().orElse(null),
        e.subject(),
        e.allowed(),
        e.detail(),
        e.error().orElse(null));
  }

  /**
   * Writes the grants found in {@code policy.toml} to the audit log, unless they are the same as
   * the last time. A change made outside the daemon shows up here at the next start.
   */
  static void auditPolicy(DaemonConfig config, Store store) throws DaemonException {
    try {
      String hash = "absent";
      List<String> ids = List.of();
      if (Files.exists(config.policy())) {
        hash =
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(config.policy())));
        ids = Policy.load(config.policy()).grants().stream().map(Grant::id).toList();
      }
      String detail =
          "{\"sha256\":\""
              + hash
              + "\",\"grants\":["
              + String.join(",", ids.stream().map(i -> "\"" + i + "\"").toList())
              + "]}";
      if (store
          .lastAudit(AuditEntry.Kind.POLICY)
          .map(AuditEntry::detail)
          .filter(detail::equals)
          .isEmpty()) {
        store.audit(
            new AuditEntry(
                Instant.now(),
                AuditEntry.Kind.POLICY,
                java.util.Optional.empty(),
                "policy.toml",
                true,
                detail,
                java.util.Optional.empty()));
      }
    } catch (StoreException
        | IOException
        | RulesetException
        | java.security.NoSuchAlgorithmException e) {
      throw new DaemonException("cannot audit the policy: " + e.getMessage(), e);
    }
  }

  private static Response.LogLines.Line line(LogEntry e) {
    return new Response.LogLines.Line(
        e.time().toString(),
        e.eventId(),
        e.ruleId().orElse(null),
        e.actionId().orElse(null),
        e.outcome().name(),
        e.message());
  }

  private void requestStop() {
    stopped.countDown();
  }

  /** Blocks until a client asked the daemon to stop. */
  public void awaitStop() throws InterruptedException {
    stopped.await();
  }

  /** Stops the sources, the API and the engine, in that order. Safe to call more than once. */
  @Override
  public void close() {
    if (!closed.compareAndSet(false, true)) {
      return;
    }
    stopped.countDown();
    topics.close();
    if (modules != null) {
      modules.stopSources();
    }
    if (api != null) {
      api.close();
    }
    if (engine != null) {
      engine.close();
    }
    if (store != null) {
      try {
        store.close();
      } catch (StoreException e) {
        LOG.log(System.Logger.Level.WARNING, "cannot close the store: " + e.getMessage());
      }
    }
    if (modules != null) {
      modules.close();
    }
  }

  /** The events the engine has not finished, for tests and diagnostics. */
  List<Event> pending() throws StoreException {
    return store.pending();
  }
}
