// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.engine.AccessPolicy;
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
              config.modules(),
              config.lockfile(),
              config.moduleSettings(),
              config.enabledModules());
      ExpressionCompiler compiler = compiler(config);
      LayerStack.Effective effective =
          new Layers(config, new GitCli(config.gitCache())).effective();
      ruleset = effective.ruleset();
      AccessPolicy access = accessPolicy(config, modules.catalog(), effective);
      store = Store.open(config.database());
      auditPolicy(config, store);
      Map<String, Capability> capabilities = modules.capabilities();
      engine =
          new Engine(
              ruleset,
              modules.catalog(),
              compiler,
              capabilities,
              store,
              new Engine.Options(access, new StoreAuditLog(store), clock, config.dryRun()));
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

  /** The grants in {@code policy.toml}, checked against the modules and layers in use. */
  static AccessPolicy accessPolicy(
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
        case Request.Stop s -> new Response.Stopped();
      };
    } catch (Injection.Invalid e) {
      return new Response.Error(e.getMessage());
    } catch (StoreException e) {
      return new Response.Error("store: " + e.getMessage());
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
