// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.engine.AuditLog;
import it.uhde.praksis.engine.Engine;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetLoader;
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
      modules = ModuleLoader.load(config.modules(), config.lockfile(), config.moduleSettings());
      ExpressionCompiler compiler =
          config.expression().isPresent()
              ? ModuleLoader.loadExpressionCompiler(config.expression().get())
              : Expressions.compiler();
      ruleset = RulesetLoader.load(config.ruleset());
      store = Store.open(config.database());
      Map<String, Capability> capabilities = modules.capabilities();
      engine =
          new Engine(
              ruleset,
              modules.catalog(),
              compiler,
              capabilities,
              store,
              new Engine.Options(AccessPolicy.allowAll(), AuditLog.none(), clock, config.dryRun()));
      engine.recover();
      api = new ApiServer(config.socket(), this::handle, this::requestStop);
      api.start();
      modules.startSources(engine);
    } catch (RulesetException e) {
      throw new DaemonException(
          "the ruleset in "
              + config.ruleset()
              + " is not valid:\n  "
              + String.join("\n  ", e.problems().stream().map(Problem::toString).toList()),
          e);
    } catch (ModuleLoadException | CapabilityException | StoreException | IOException e) {
      throw new DaemonException(e.getMessage(), e);
    }
    LOG.log(System.Logger.Level.INFO, "praksis daemon started, home " + config.home());
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
        case Request.Stop s -> new Response.Stopped();
      };
    } catch (Injection.Invalid e) {
      return new Response.Error(e.getMessage());
    } catch (StoreException e) {
      return new Response.Error("store: " + e.getMessage());
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
