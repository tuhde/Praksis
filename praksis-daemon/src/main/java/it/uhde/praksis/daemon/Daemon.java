// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.docs.Docs;
import it.uhde.praksis.engine.CredentialResolver;
import it.uhde.praksis.engine.Engine;
import it.uhde.praksis.engine.GrantPolicy;
import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.engine.StoreAuditLog;
import it.uhde.praksis.entity.EntityCatalog;
import it.uhde.praksis.entity.EntityMigrations;
import it.uhde.praksis.entity.EntityService;
import it.uhde.praksis.entity.EntityStorages;
import it.uhde.praksis.entity.SqliteEntityStorage;
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
  private volatile PeerAccess peerAccess;
  private volatile GrantPolicy grants;
  private volatile PeerCalls peerCalls;
  private volatile RemoteRequests remoteRequests;
  private final Map<String, RemoteFeed> remoteFeeds =
      new java.util.concurrent.ConcurrentHashMap<>();
  private final List<Thread> feeds = new java.util.concurrent.CopyOnWriteArrayList<>();
  private Entities entities;
  private EntityStorages entityStorages;
  private final java.util.concurrent.atomic.AtomicReference<EntityService> entityService =
      new java.util.concurrent.atomic.AtomicReference<>();
  private volatile Engine engine;
  private Approvals approvals;
  private Shadows shadows;
  private Screens screens;
  private ViewResolver views;
  private final Topics topics = new Topics();
  private ApiServer api;
  private WebServer web;

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

  /** The layers with the directories their entity types are read from. */
  static List<EntityCatalog.Source> entitySources(Layers layers) throws DaemonException {
    return entitySources(layers.sources(layers.readLock()));
  }

  static List<EntityCatalog.Source> entitySources(List<LayerStack.Source> sources) {
    return sources.stream().map(s -> new EntityCatalog.Source(s.name(), s.directory())).toList();
  }

  /**
   * Opens the entity service with the types of the {@code entities/} directories of all layers,
   * bottom to top. A file with a problem, or a layer that replaces a type wrongly, stops the start,
   * like a rule that is not valid.
   */
  private void openEntities(EntityCatalog catalog, Store store, GrantPolicy access)
      throws DaemonException {
    entityStorages = openStorages(catalog, store, access);
    EntityService service =
        new EntityService(
            store,
            catalog,
            clock,
            event -> {
              Engine running = engine;
              if (running != null) {
                running.queueStored(event);
              } // else the event is pending in the store and recover() finds it
            },
            entityStorages);
    try {
      Map<String, Integer> migrated = EntityMigrations.run(service, catalog, store, clock);
      migrated.forEach(
          (type, n) ->
              LOG.log(
                  System.Logger.Level.INFO,
                  "migrated " + n + " " + type + " entities to the new version of the type"));
    } catch (it.uhde.praksis.entity.EntityException e) {
      throw new DaemonException("cannot migrate the entities: " + e.getMessage(), e);
    }
    entityService.set(service);
  }

  /**
   * The storages of the entity types: the daemon's own database for every type that {@code
   * [entities.bind]} does not bind, and for each configured storage an instance of the backend a
   * module provides, configured and opened for its types.
   */
  private EntityStorages openStorages(EntityCatalog catalog, Store store, GrantPolicy access)
      throws DaemonException {
    EntitiesConfig cfg = config.entities();
    Map<String, String> binding = StorageBinding.resolve(cfg, catalog);
    java.util.List<it.uhde.praksis.entity.EntityType> unbound = new java.util.ArrayList<>();
    Map<String, java.util.List<it.uhde.praksis.entity.EntityType>> byStorage =
        new java.util.HashMap<>();
    for (it.uhde.praksis.entity.EntityType t : catalog.types().values()) {
      String name = binding.get(t.id());
      if (name == null) {
        unbound.add(t);
      } else {
        byStorage.computeIfAbsent(name, n -> new java.util.ArrayList<>()).add(t);
      }
    }
    {
      SqliteEntityStorage sqlite = new SqliteEntityStorage(store);
      sqlite.open(EntityStorages.storageTypes(unbound));
      Map<String, EntityStorage> bound = new java.util.HashMap<>();
      java.util.List<EntityStorage> owned = new java.util.ArrayList<>();
      CredentialResolver credentials = new CredentialResolver(access::policy, store, clock);
      try {
        for (EntitiesConfig.Storage cs : cfg.storages().values()) {
          java.util.List<it.uhde.praksis.entity.EntityType> types =
              byStorage.getOrDefault(cs.name(), java.util.List.of());
          if (types.isEmpty()) {
            LOG.log(
                System.Logger.Level.WARNING,
                "[entities.storage." + cs.name() + "] keeps no type: nothing is bound to it");
            continue;
          }
          EntityStorage storage;
          try {
            storage =
                modules
                    .newBackend(cs.backend())
                    .orElseThrow(
                        () ->
                            new DaemonException(
                                "[entities.storage."
                                    + cs.name()
                                    + "]: no module provides the entity storage '"
                                    + cs.backend()
                                    + "' (provided: "
                                    + new java.util.TreeSet<>(modules.backendNames())
                                    + ")"));
          } catch (IllegalStateException e) {
            throw new DaemonException("[entities.storage." + cs.name() + "]: " + e.getMessage(), e);
          }
          owned.add(storage);
          try {
            storage.configure(cs.settings());
            storage.useCredentials(credentials.forComponent("entity-storage:" + cs.name()));
            java.nio.file.Path data = config.dataDirectory().resolve("storage").resolve(cs.name());
            java.nio.file.Files.createDirectories(data);
            storage.useDataDirectory(data);
            storage.open(EntityStorages.storageTypes(types));
          } catch (it.uhde.praksis.capability.CapabilityException
              | it.uhde.praksis.capability.StorageException
              | java.io.IOException e) {
            throw new DaemonException("[entities.storage." + cs.name() + "]: " + e.getMessage(), e);
          }
          types.forEach(t -> bound.put(t.id(), storage));
        }
      } catch (DaemonException e) {
        owned.forEach(EntityStorage::close);
        throw e;
      }
      return new EntityStorages(sqlite, bound, owned);
    }
  }

  private void open() throws DaemonException {
    checkIdentity(config);
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
      modules = Builtins.add(modules, agent, entityService::get);
      modules =
          Remotes.add(
              config,
              modules,
              this::remoteAvailability,
              new RemoteCalls(
                  new Peers(config.home()),
                  new RemoteCatalogs(config.home()),
                  (rule, capability, a) -> it.uhde.praksis.engine.AccessPolicy.Decision.allow(),
                  (time, rule, capability, a, allowed, error) -> {},
                  clock,
                  (peer, capability) -> remoteAccepted(peer, capability)));
      if (config.offline()) {
        modules = modules.offline();
        LOG.log(
            System.Logger.Level.INFO, "offline = true: modules that need the network are not used");
      }
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
      EntityCatalog entityCatalog = EntitySets.catalog(layers.sources(layers.readLock()));
      GrantPolicy access = accessPolicy(config, modules.catalog(), effective, entityCatalog);
      store = Store.open(config.database());
      grants = access;
      remoteRequests =
          new RemoteRequests(
              store,
              new RemotePins(config.home()),
              new RemoteCatalogs(config.home()),
              clock,
              () -> {
                try {
                  approvals.reload();
                } catch (Exception e) {
                  System.err.println("praksis: cannot read the policy again: " + e.getMessage());
                }
              });
      peerAccess = new PeerAccess(access::policy, store::audit);
      peerCalls = new PeerCalls(modules.catalog()::capability, modules.capabilities(), peerAccess);
      openEntities(entityCatalog, store, access);
      entities = new Entities(entityService::get, store, clock);
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
      approvals =
          new Approvals(
              config, store, access, modules.catalog(), clock, entityCatalog.allMembers());
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
      api =
          new ApiServer(
              config.socket(),
              this::handle,
              this::requestStop,
              new PeerHandshake(new Peers(config.home())),
              this::handlePeer);
      api.start();
      if (config.web().listen().isPresent()) {
        web =
            new WebServer(
                config.web().listen().get(),
                config.web().origins(),
                new WebTokens(config.home()),
                this::handleWeb,
                clock);
        web.start();
      }
      startFeeds();
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

  /** Pins, accepts or removes a pin on behalf of a person, and reads the policy again. */
  private String pinSet(Request.PinSet r) throws IOException, Approvals.Failure, StoreException {
    RemotePins pins = new RemotePins(config.home());
    var remote = new RemoteCatalogs(config.home()).cached(r.peer());
    String message;
    switch (r.action()) {
      case "unpin" -> {
        if (!pins.unpin(r.peer(), r.set())) {
          throw new Approvals.Failure(r.peer() + "/" + r.set() + " is not pinned");
        }
        message = "unpinned " + r.peer() + "/" + r.set();
      }
      case "pin", "accept" -> {
        if (remote.isEmpty()) {
          throw new Approvals.Failure("there is no catalog of " + r.peer());
        }
        var pin =
            r.action().equals("pin")
                ? pins.pin(r.peer(), r.set(), remote.get().catalog(), clock.instant())
                : pins.accept(r.peer(), r.set(), remote.get().catalog(), clock.instant());
        message = pin.name() + " is pinned to " + pin.members();
      }
      default -> throw new Approvals.Failure("the action must be 'pin', 'accept' or 'unpin'");
    }
    approvals.reload();
    topics.bump("remote");
    return message;
  }

  /** Whether a function of a remote was accepted; unknown while the daemon starts. */
  private boolean remoteAccepted(String peer, String capability) throws IOException {
    RemoteRequests requests = remoteRequests;
    if (requests == null) {
      return false;
    }
    try {
      return requests.accepted(peer, capability);
    } catch (StoreException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** Why a capability of another daemon cannot be used now, if it cannot. */
  private Optional<String> remoteAvailability(String qualified) {
    int slash = qualified.indexOf('/');
    if (slash < 1) {
      return Optional.empty();
    }
    String peer = qualified.substring(0, slash);
    String capability = qualified.substring(slash + 1);
    RemoteFeed feed = remoteFeeds.get(peer);
    if (feed != null) {
      Optional<String> why = feed.unavailable("capability:" + capability);
      if (why.isPresent()) {
        return why;
      }
    }
    try {
      if (!remoteAccepted(peer, capability)) {
        return Optional.of("not accepted yet (praksis peers requests)");
      }
    } catch (IOException e) {
      return Optional.of(e.getMessage());
    }
    return Optional.empty();
  }

  /** Follows the catalog of every listed peer that has a socket. */
  private void startFeeds() throws IOException {
    Peers own = new Peers(config.home());
    for (Peers.Peer peer : own.list()) {
      if (peer.socket().isEmpty()) {
        continue;
      }
      RemoteFeed feed = new RemoteFeed(config.home(), peer.name(), clock, topics::bump);
      feed.onCatalog(c -> remoteRequests.onCatalog(peer, c));
      remoteFeeds.put(peer.name(), feed);
      feeds.add(
          Thread.ofVirtual().name("praksis-feed-" + peer.name()).start(() -> feed.run(own, peer)));
    }
  }

  /** What is offered to this peer now, or null while the daemon starts. */
  private Response.PeerCatalog catalogFor(Peers.Peer peer) {
    if (grants == null || modules == null || entities == null) {
      return null;
    }
    Response types = entities.handle(new Request.EntityTypes());
    Response sets = entities.handle(new Request.EntitySets());
    if (types instanceof Response.EntityTypeList t && sets instanceof Response.EntitySetList s) {
      return PeerCatalogs.build(peer, grants.policy(), modules.catalog(), t, s);
    }
    return null;
  }

  /**
   * A home that is named like an identity ({@code ~/.praksis-private}) must be that identity, so
   * that the daemon of one role is never started in the home of another.
   */
  static void checkIdentity(DaemonConfig config) throws DaemonException {
    String dir = config.home().getFileName() == null ? "" : config.home().getFileName().toString();
    if (dir.startsWith(".praksis-") && config.identity().isPresent()) {
      String named = dir.substring(".praksis-".length());
      if (!named.equals(config.identity().get())) {
        throw new DaemonException(
            "the home "
                + config.home()
                + " is the home of the identity '"
                + named
                + "', but praksis.toml says identity = \""
                + config.identity().get()
                + "\". One of them is wrong; nothing was started.");
      }
    }
  }

  /** Answers a request of another daemon that has proven its key. */
  private Response handlePeer(Peers.Peer peer, Request request) {
    if (request instanceof Request.PeerCatalog) {
      Response.PeerCatalog catalog = catalogFor(peer);
      return catalog == null ? new Response.Error("not ready") : catalog;
    }
    if (request instanceof Request.PeerChanges c) {
      try {
        Response.ChangeList changes = topics.changes(c.since(), c.waitMillis());
        Response.PeerCatalog catalog = catalogFor(peer);
        if (catalog == null) {
          return new Response.Error("not ready");
        }
        return new Response.PeerChangeList(
            changes.counter(),
            changes.topics().stream()
                .filter(t -> t.equals("catalog") || t.equals("entities"))
                .toList(),
            PeerCatalogs.hash(catalog));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return new Response.Error("interrupted");
      }
    }
    if (request instanceof Request.PeerCall call) {
      return peerCalls == null ? new Response.Error("not ready") : peerCalls.call(peer, call);
    }
    return new Response.Error("a peer cannot do this");
  }

  /** A grant for a peer needs a peer of that name in {@code peers.toml}. */
  static List<Problem> peerGrantProblems(DaemonConfig config, Policy policy) {
    if (policy.peerGrants().isEmpty()) {
      return List.of();
    }
    try {
      java.util.Set<String> known = new java.util.HashSet<>();
      new Peers(config.home()).list().forEach(p -> known.add(p.name()));
      List<Problem> problems = new java.util.ArrayList<>();
      for (var g : policy.peerGrants()) {
        if (!known.contains(g.peer())) {
          problems.add(
              new Problem(
                  "policy.toml: peer_grant '" + g.id() + "'",
                  "no peer named '" + g.peer() + "' in peers.toml"));
        }
      }
      return problems;
    } catch (java.io.IOException e) {
      return List.of(new Problem("peers.toml", e.getMessage()));
    }
  }

  /** The grants in {@code policy.toml}, checked against the modules and layers in use. */
  static GrantPolicy accessPolicy(
      DaemonConfig config,
      ModuleCatalog catalog,
      LayerStack.Effective effective,
      EntityCatalog entities)
      throws DaemonException {
    try {
      Policy policy = EntitySets.policy(config, entities.allMembers());
      List<Problem> problems =
          policy.check(
              catalog, config.layerNames(), config.storageComponents(), entities.sets().keySet());
      problems = new java.util.ArrayList<>(problems);
      problems.addAll(peerGrantProblems(config, policy));
      problems.addAll(EntitySets.pinProblems(config, policy));
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

  /** A request of a web client: only what {@link WebGate} lets this token ask for. */
  private Response handleWeb(WebTokens.Token token, Request request) {
    return WebGate.decide(token, request, this::handle);
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
                store.pending().size(),
                it.uhde.praksis.protocol.Wire.PROTOCOL_VERSION,
                config.identity().orElse(null));
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
        case Request.Peers r ->
            RemoteOverview.build(
                config,
                name -> Optional.ofNullable(remoteFeeds.get(name)),
                (peer, capability) -> {
                  try {
                    return remoteAccepted(peer, capability);
                  } catch (IOException e) {
                    return false;
                  }
                });
        case Request.PinSet r -> new Response.Done(pinSet(r));
        case Request.RemoteRequests r -> remoteRequests.list();
        case Request.RemoteAnswer r ->
            new Response.Done(remoteRequests.answer(r.id(), r.decision()));
        case Request.Missed m -> new Response.Done(approvals.missed(m.rule(), m.action()));
        case Request.Grants g -> approvals.grants();
        case Request.Revoke r -> new Response.Done(approvals.revoke(r.id()));
        case Request.Reload r -> {
          Response done = new Response.Done(approvals.reload());
          topics.bump("catalog");
          yield done;
        }
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
        case Request.Help h -> help(h.topic());
        case Request.Views v -> views.list();
        case Request.ViewResolve v -> views.resolve(v.view(), v.params(), v.maxSchema());
        case Request.Lookup l -> views.lookup(l.view(), l.capability(), l.args());
        case Request.Dictate d -> views.dictate(d.view(), d.capability());
        case Request.Upload u -> Uploads.ticket(config.uploadsDirectory(), clock.instant());
        case Request.Fetch f ->
            views.fetch(
                f.view(), f.capability(), f.args(), f.filename(), config.downloadsDirectory());
        case Request.EntityTypes r -> entities.handle(r);
        case Request.EntitySets r -> entities.handle(r);
        case Request.EntityQuery r -> entities.handle(r);
        case Request.EntityRead r -> entities.handle(r);
        case Request.EntityCreate r -> entities.handle(r);
        case Request.EntityUpdate r -> entities.handle(r);
        case Request.EntityDelete r -> entities.handle(r);
        case Request.EntityExport r -> entities.handle(r);
        case Request.EntityImport r -> entities.handle(r);
        case Request.Stop s -> new Response.Stopped();
        case Request.PeerHello h -> new Response.Error("not a handshake connection");
        case Request.PeerProve p -> new Response.Error("not a handshake connection");
        case Request.PeerCatalog p -> new Response.Error("only a peer can ask for this");
        case Request.PeerCall p -> new Response.Error("only a peer can ask for this");
        case Request.PeerChanges p -> new Response.Error("only a peer can ask for this");
      };
    } catch (Injection.Invalid | Approvals.Failure | Shadows.Failure | DaemonException e) {
      return new Response.Error(e.getMessage());
    } catch (StoreException e) {
      return new Response.Error("store: " + e.getMessage());
    } catch (IOException e) {
      return new Response.Error(e.getMessage());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return new Response.Error("interrupted");
    }
  }

  /** The documentation that ships with the program: the list of pages, or one of them. */
  private static Response help(String topic) {
    if (topic == null) {
      return new Response.HelpTopics(
          Docs.topics().stream()
              .map(t -> new Response.HelpTopics.Topic(t.name(), t.title()))
              .toList());
    }
    return Docs.find(topic)
        .<Response>map(t -> new Response.HelpPage(t.name(), t.title(), Docs.read(t.name())))
        .orElseGet(() -> new Response.Error("There is no help topic '" + topic + "'."));
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
    feeds.forEach(Thread::interrupt);
    topics.close();
    if (modules != null) {
      modules.stopSources();
    }
    if (web != null) {
      web.close();
    }
    if (api != null) {
      api.close();
    }
    if (engine != null) {
      engine.close();
    }
    if (entityStorages != null) {
      entityStorages.close();
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
