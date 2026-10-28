// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.expression.Bindings;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.ruleset.CompiledRule;
import it.uhde.praksis.ruleset.CompiledRule.CompiledAction;
import it.uhde.praksis.ruleset.CompiledRule.CompiledArg;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.ParamDecl;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Rule;
import it.uhde.praksis.ruleset.RuleView;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetValidator;
import it.uhde.praksis.ruleset.Trigger;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.LogEntry.Outcome;
import it.uhde.praksis.store.ShadowRun;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Takes events and fires the matching rules.
 *
 * <p>For each event, every rule whose trigger matches fires, in rule order (sorted by id). Events
 * with the same target are handled one at a time in the order they arrived, events with different
 * targets in parallel. If an action fails, the rest of that rule is skipped and the next rule
 * continues, unless the rule is {@code required}, which ends the handling of the event. A {@code
 * stop} action ends the handling of the event without error. A call that the {@link AccessPolicy}
 * denies disables that rule only, see {@link #disabledRules()}.
 *
 * <p>Events are stored before they are handled and marked done afterwards, see {@link Store}.
 */
public final class Engine implements EventSink, AutoCloseable {
  /** How long the events that a disabled rule missed are kept. */
  static final Duration MISSED_KEEP = Duration.ofDays(7);

  /** How many missed events are kept per rule. */
  static final int MISSED_CAP = 1000;

  /**
   * Settings with defaults.
   *
   * @param accessPolicy checked before every capability call
   * @param auditLog told about every capability call
   * @param clock the source of {@code now} and of log times
   * @param dryRun whether capabilities are told to change nothing
   * @param denials told when a denied call disables a rule
   */
  public record Options(
      AccessPolicy accessPolicy, AuditLog auditLog, Clock clock, boolean dryRun, Denials denials) {
    public Options(AccessPolicy accessPolicy, AuditLog auditLog, Clock clock, boolean dryRun) {
      this(accessPolicy, auditLog, clock, dryRun, Denials.none());
    }

    public static Options defaults() {
      return new Options(AccessPolicy.allowAll(), AuditLog.none(), Clock.systemUTC(), false);
    }
  }

  /** A rule in firing order; a rule on trial is observed and does nothing. */
  private record Entry(CompiledRule rule, boolean shadow, String hash) {}

  /** How many observations of a rule on trial are kept. */
  public static final int SHADOW_KEEP = 1000;

  private volatile List<Entry> timeline;
  private final Map<String, Object> params;
  private final ModuleCatalog catalog;
  private final Map<String, Capability> capabilities;
  private final Store store;
  private final Options options;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

  private final Object lock = new Object();
  private final Map<String, Deque<Event>> queues = new HashMap<>();
  private int unfinished;
  private boolean closed;

  /** Rules that made a call no grant covers, with the reason; they do nothing until enabled. */
  private final Map<String, String> disabled = new ConcurrentHashMap<>();

  /**
   * Validates and compiles the ruleset and prepares the engine. Nothing runs until events arrive.
   *
   * @param capabilities the capability implementations by name
   * @throws RulesetException if the ruleset is invalid, a capability it needs has no
   *     implementation, or a parameter has no value
   */
  public Engine(
      Ruleset ruleset,
      ModuleCatalog catalog,
      ExpressionCompiler compiler,
      Map<String, Capability> capabilities,
      Store store,
      Options options)
      throws RulesetException {
    this(ruleset, List.of(), catalog, compiler, capabilities, store, options);
  }

  /**
   * As above, with rules on trial. They are observed in their place in the firing order, for the
   * events that reach them: what they would call is recorded, nothing is called, and their {@code
   * stop} does not end the event for the rules after them. A rule on trial may have the id of a
   * rule in {@code ruleset}, which is then the older version that stays live meanwhile.
   *
   * @param shadow the rules on trial
   */
  public Engine(
      Ruleset ruleset,
      List<Rule> shadow,
      ModuleCatalog catalog,
      ExpressionCompiler compiler,
      Map<String, Capability> capabilities,
      Store store,
      Options options)
      throws RulesetException {
    RulesetValidator.Result result = new RulesetValidator(compiler, catalog).compile(ruleset);
    List<Problem> problems = new ArrayList<>(result.problems());
    Map<String, Object> values = new LinkedHashMap<>();
    for (ParamDecl p : ruleset.params()) {
      p.defaultValue()
          .ifPresentOrElse(
              v -> values.put(p.name(), v),
              () ->
                  problems.add(
                      new Problem("ruleset.toml", "parameter '" + p.name() + "' has no value")));
    }
    for (CompiledRule rule : result.rules()) {
      for (String need : rule.rule().needs()) {
        if (!capabilities.containsKey(need)) {
          problems.add(
              new Problem(
                  "rules/" + rule.rule().id() + ".toml",
                  "capability '" + need + "' is not provided by any loaded module"));
        }
      }
    }
    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }
    RulesetValidator.Result trial =
        new RulesetValidator(compiler, catalog)
            .compile(
                new Ruleset(ruleset.formatVersion(), ruleset.name(), ruleset.params(), shadow));
    problems.addAll(trial.problems());
    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }
    List<Entry> entries = new ArrayList<>();
    result.rules().forEach(r -> entries.add(new Entry(r, false, RuleView.hash(r.rule()))));
    trial.rules().forEach(r -> entries.add(new Entry(r, true, RuleView.hash(r.rule()))));
    entries.sort(
        java.util.Comparator.comparing((Entry x) -> x.rule().rule().id())
            .thenComparing(x -> !x.shadow()));
    this.timeline = List.copyOf(entries);
    this.params = Map.copyOf(values);
    this.catalog = catalog;
    this.capabilities = Map.copyOf(capabilities);
    this.store = store;
    this.options = options;
  }

  /** The rules that were disabled because a call was denied, by rule id, with the reason. */
  public Map<String, String> disabledRules() {
    return Map.copyOf(disabled);
  }

  /** Queues the events that were stored but not finished, e.g. before a crash or restart. */
  public void recover() throws StoreException {
    for (Event event : store.pending()) {
      enqueue(event);
    }
  }

  /**
   * Stores an event and queues it for handling.
   *
   * @return false if an event with the same id or key was seen before, in which case nothing
   *     happens
   */
  public boolean submit(Event event) throws StoreException {
    if (!store.append(event)) {
      return false;
    }
    enqueue(event);
    return true;
  }

  @Override
  public void accept(Event event) {
    try {
      submit(event);
    } catch (StoreException e) {
      throw new IllegalStateException(e.getMessage(), e);
    }
  }

  /** Waits until every queued event is handled. Returns false if the timeout passed first. */
  public boolean awaitIdle(Duration timeout) throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    synchronized (lock) {
      while (unfinished > 0) {
        long left = deadline - System.nanoTime();
        if (left <= 0) {
          return false;
        }
        TimeUnit.NANOSECONDS.timedWait(lock, left);
      }
      return true;
    }
  }

  /** Finishes the events already queued, then stops. */
  @Override
  public void close() {
    synchronized (lock) {
      closed = true;
    }
    try {
      awaitIdle(Duration.ofDays(1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    executor.shutdown();
  }

  private void enqueue(Event event) {
    synchronized (lock) {
      if (closed) {
        return; // stays pending in the store and is picked up by recover() after a restart
      }
      unfinished++;
      Deque<Event> queue = queues.get(event.target());
      if (queue == null) {
        queue = new ArrayDeque<>();
        queues.put(event.target(), queue);
        queue.add(event);
        String target = event.target();
        executor.execute(() -> drain(target));
      } else {
        queue.add(event);
      }
    }
  }

  /** Handles the events of one target until its queue is empty. */
  private void drain(String target) {
    while (true) {
      Event event;
      synchronized (lock) {
        Deque<Event> queue = queues.get(target);
        event = queue.poll();
        if (event == null) {
          queues.remove(target);
          return;
        }
      }
      try {
        handle(event);
      } catch (RuntimeException | StoreException e) {
        System.err.println("praksis: cannot handle event " + event.id() + ": " + e);
      } finally {
        synchronized (lock) {
          unfinished--;
          lock.notifyAll();
        }
      }
    }
  }

  private void handle(Event event) throws StoreException {
    for (Entry entry : timeline) {
      CompiledRule rule = entry.rule();
      Trigger.OnEvent trigger = (Trigger.OnEvent) rule.rule().trigger();
      if (!trigger.type().equals(event.type())) {
        continue;
      }
      if (entry.shadow()) {
        observe(entry, event);
        continue;
      }
      if (fire(rule, event, false) == Flow.END) {
        break;
      }
    }
    store.markDone(event.id());
  }

  /** Whether the rules after this one still get the event. */
  private enum Flow {
    NEXT,
    END
  }

  /**
   * Fires one rule for an event if its filter matches, and logs what happens.
   *
   * @param allowOnce let the rule's calls through whatever the access policy says: the person
   *     answered "allow once" for this very event
   */
  private Flow fire(CompiledRule rule, Event event, boolean allowOnce) throws StoreException {
    String ruleId = rule.rule().id();
    try {
      if (rule.filter().isPresent()
          && !Boolean.TRUE.equals(rule.filter().get().evaluate(bindings(event, Map.of())))) {
        return Flow.NEXT;
      }
      String why = disabled.get(ruleId);
      if (why != null) {
        miss(ruleId, event);
        log(
            event,
            Optional.of(ruleId),
            Optional.empty(),
            rule.rule().required() ? Outcome.FAILED : Outcome.SKIPPED,
            "rule is disabled: " + why);
        return rule.rule().required() ? Flow.END : Flow.NEXT;
      }
      return run(rule, event, allowOnce) ? Flow.END : Flow.NEXT;
    } catch (Exception e) {
      if (e instanceof Denied d) {
        if (disabled.putIfAbsent(ruleId, d.reason) == null) {
          options.denials().denied(ruleId, d.capability, d.args, event);
        }
        miss(ruleId, event);
      }
      log(
          event,
          Optional.of(ruleId),
          Optional.empty(),
          e instanceof Denied ? Outcome.DENIED : Outcome.FAILED,
          message(e));
      if (rule.rule().required()) {
        log(
            event,
            Optional.of(ruleId),
            Optional.empty(),
            Outcome.FAILED,
            "required rule failed: no further rules for this event");
        return Flow.END;
      }
      return Flow.NEXT;
    }
  }

  /** The ids of the rules that are on trial. */
  public java.util.Set<String> shadowRules() {
    java.util.Set<String> out = new java.util.TreeSet<>();
    timeline.stream().filter(Entry::shadow).forEach(r -> out.add(r.rule().rule().id()));
    return out;
  }

  /**
   * Ends the trial of a rule: the version on trial runs live from now on, and the older version, if
   * there was one, is gone.
   *
   * @return false, changing nothing, if the rule is not on trial
   */
  public synchronized boolean promote(String ruleId) {
    if (timeline.stream().noneMatch(e -> e.shadow() && e.rule().rule().id().equals(ruleId))) {
      return false;
    }
    List<Entry> next = new ArrayList<>();
    for (Entry e : timeline) {
      if (!e.rule().rule().id().equals(ruleId)) {
        next.add(e);
      } else if (e.shadow()) {
        next.add(new Entry(e.rule(), false, e.hash()));
      }
    }
    timeline = List.copyOf(next);
    return true;
  }

  /**
   * Watches a rule on trial for an event that reaches it: if its filter matches, records the calls
   * it would make, with the arguments as evaluated and whether the access policy would allow them.
   * Nothing is called and nothing else is affected.
   */
  private void observe(Entry entry, Event event) throws StoreException {
    CompiledRule rule = entry.rule();
    String ruleId = rule.rule().id();
    List<Map<String, Object>> calls = new ArrayList<>();
    String note = "";
    try {
      if (rule.filter().isPresent()
          && !Boolean.TRUE.equals(rule.filter().get().evaluate(bindings(event, Map.of())))) {
        return;
      }
      Map<String, Map<String, Object>> results = new HashMap<>();
      for (CompiledAction action : rule.actions()) {
        if (action instanceof CompiledAction.Stop) {
          note = "stop";
          break;
        }
        CompiledAction.Call call = (CompiledAction.Call) action;
        Map<String, Object> args = new LinkedHashMap<>();
        for (var arg : call.args().entrySet()) {
          args.put(
              arg.getKey(),
              switch (arg.getValue()) {
                case CompiledArg.Value v -> v.value();
                case CompiledArg.Computed c -> c.expression().evaluate(bindings(event, results));
              });
        }
        CapabilityDescriptor descriptor = catalog.capability(call.capability()).orElseThrow();
        AccessPolicy.Decision decision = options.accessPolicy().check(ruleId, descriptor, args);
        Map<String, Object> seen = new LinkedHashMap<>();
        seen.put("capability", call.capability());
        seen.put("args", printable(args));
        seen.put("allowed", decision.allowed());
        seen.put("reason", decision.reason());
        calls.add(seen);
        results.put(call.id(), Defaults.results(descriptor));
      }
    } catch (RuntimeException e) {
      note = "would fail: " + message(e);
    } catch (Exception e) {
      note = "would fail: " + message(e);
    }
    String json;
    try {
      json = JSON.writeValueAsString(calls);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      json = "[]";
      note = "cannot record the calls: " + e.getOriginalMessage();
    }
    store.addShadowRun(
        new ShadowRun(options.clock().instant(), ruleId, entry.hash(), event.id(), json, note),
        SHADOW_KEEP);
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /** Values as JSON can hold them: what has no JSON form is written as text. */
  private static Object printable(Object value) {
    return switch (value) {
      case null -> null;
      case String s -> s;
      case Number n -> n;
      case Boolean b -> b;
      case Map<?, ?> m -> {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), printable(v)));
        yield out;
      }
      case List<?> l -> l.stream().map(Engine::printable).toList();
      case byte[] b -> "(" + b.length + " bytes)";
      default -> String.valueOf(value);
    };
  }

  /** Disables a rule, e.g. because a request for its permission has not been answered. */
  public void disable(String ruleId, String reason) {
    disabled.put(ruleId, reason);
  }

  /** Lets a disabled rule run again. */
  public void enable(String ruleId) {
    disabled.remove(ruleId);
  }

  /** Whether the ruleset has a rule with this id. */
  public boolean hasRule(String ruleId) {
    return timeline.stream().anyMatch(r -> !r.shadow() && r.rule().rule().id().equals(ruleId));
  }

  /**
   * Fires one rule for events it missed, in the order given, in the background. Each event that was
   * handled is forgotten as missed. It stops when the rule is disabled again, e.g. because another
   * call is denied. Events are not replayed in step with live events of the same target.
   *
   * @param allowOnce let the calls through whatever the access policy says
   * @throws IllegalArgumentException if there is no such rule
   */
  public void replay(String ruleId, List<Event> events, boolean allowOnce) {
    CompiledRule rule =
        timeline.stream()
            .filter(r -> !r.shadow() && r.rule().rule().id().equals(ruleId))
            .map(Entry::rule)
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("no rule '" + ruleId + "'"));
    synchronized (lock) {
      if (closed) {
        return;
      }
      unfinished++;
    }
    executor.execute(
        () -> {
          try {
            for (Event event : events) {
              if (disabled.containsKey(ruleId)) {
                break;
              }
              log(
                  event,
                  Optional.of(ruleId),
                  Optional.empty(),
                  Outcome.OK,
                  "replay after approval");
              fire(rule, event, allowOnce);
              if (disabled.containsKey(ruleId)) {
                break; // denied again: this event stays missed
              }
              store.removeMissed(ruleId, event.id());
            }
          } catch (StoreException | RuntimeException e) {
            System.err.println("praksis: cannot replay for " + ruleId + ": " + e);
          } finally {
            synchronized (lock) {
              unfinished--;
              lock.notifyAll();
            }
          }
        });
  }

  /** Remembers that a rule did not handle this event, so it can be replayed once it may run. */
  private void miss(String ruleId, Event event) throws StoreException {
    Instant now = options.clock().instant();
    store.addMissed(now, ruleId, event.id(), now.minus(MISSED_KEEP), MISSED_CAP);
  }

  /** Runs the actions of a matching rule. Returns true if the rule stopped the event. */
  private boolean run(CompiledRule rule, Event event, boolean allowOnce) throws Exception {
    String ruleId = rule.rule().id();
    Map<String, Map<String, Object>> results = new HashMap<>();
    for (CompiledAction action : rule.actions()) {
      switch (action) {
        case CompiledAction.Stop s -> {
          log(event, Optional.of(ruleId), Optional.empty(), Outcome.OK, "stop");
          return true;
        }
        case CompiledAction.Call call -> {
          Map<String, Object> out = call(rule, call, event, results, allowOnce);
          results.put(call.id(), out);
          log(event, Optional.of(ruleId), Optional.of(call.id()), Outcome.OK, call.capability());
        }
      }
    }
    return false;
  }

  private Map<String, Object> call(
      CompiledRule rule,
      CompiledAction.Call call,
      Event event,
      Map<String, Map<String, Object>> results,
      boolean allowOnce)
      throws Exception {
    String ruleId = rule.rule().id();
    Map<String, Object> args = new LinkedHashMap<>();
    for (var arg : call.args().entrySet()) {
      args.put(
          arg.getKey(),
          switch (arg.getValue()) {
            case CompiledArg.Value v -> v.value();
            case CompiledArg.Computed c -> c.expression().evaluate(bindings(event, results));
          });
    }
    CapabilityDescriptor descriptor = catalog.capability(call.capability()).orElseThrow();
    AccessPolicy.Decision decision =
        allowOnce
            ? AccessPolicy.Decision.allow()
            : options.accessPolicy().check(ruleId, descriptor, args);
    if (!decision.allowed()) {
      options
          .auditLog()
          .record(
              options.clock().instant(),
              ruleId,
              call.capability(),
              args,
              false,
              Optional.of(decision.reason()));
      throw new Denied(descriptor, args, decision.reason());
    }
    CapabilityContext context =
        new Context(options.dryRun(), event.id() + "/" + ruleId + "/" + call.id(), event);
    try {
      Map<String, Object> out = capabilities.get(call.capability()).invoke(context, args);
      options
          .auditLog()
          .record(
              options.clock().instant(), ruleId, call.capability(), args, true, Optional.empty());
      return out == null ? Map.of() : out;
    } catch (Exception e) {
      options
          .auditLog()
          .record(
              options.clock().instant(),
              ruleId,
              call.capability(),
              args,
              true,
              Optional.of(message(e)));
      throw new IllegalStateException("call of " + call.capability() + " failed: " + message(e), e);
    }
  }

  /** A capability call that the access policy refused. */
  private static final class Denied extends Exception {
    private static final long serialVersionUID = 1L;

    private final transient CapabilityDescriptor capability;
    private final transient Map<String, Object> args;
    private final String reason;

    Denied(CapabilityDescriptor capability, Map<String, Object> args, String reason) {
      super("call of " + capability.name() + " denied: " + reason);
      this.capability = capability;
      this.args = args;
      this.reason = reason;
    }
  }

  private Bindings bindings(Event event, Map<String, Map<String, Object>> results) {
    return new Bindings(Optional.of(event), params, results, options.clock().instant());
  }

  private void log(
      Event event,
      Optional<String> ruleId,
      Optional<String> actionId,
      Outcome outcome,
      String message)
      throws StoreException {
    store.log(
        new LogEntry(options.clock().instant(), event.id(), ruleId, actionId, outcome, message));
  }

  private static String message(Exception e) {
    return e.getMessage() == null ? e.toString() : e.getMessage();
  }

  private record Context(boolean dryRun, String idempotencyKey, Event handled)
      implements CapabilityContext {
    @Override
    public Optional<Event> event() {
      return Optional.of(handled);
    }
  }
}
