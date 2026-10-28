// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.RulesetLoader;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Store;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EngineTest {
  @TempDir Path dir;

  private Store store;
  private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
  private final List<CapabilityContext> contexts = Collections.synchronizedList(new ArrayList<>());
  private final Map<String, Capability> capabilities = new HashMap<>();
  private int counter;

  private static final ModuleCatalog CATALOG =
      new ModuleCatalog(
          List.of(
              new ModuleDescriptor(
                  "test",
                  "0",
                  List.of(),
                  List.of(
                      capability("test.record", EffectClass.LOCAL_WRITE),
                      capability("test.fail", EffectClass.LOCAL_WRITE),
                      capability("test.slow", EffectClass.LOCAL_WRITE)),
                  List.of(
                      new EventTypeDescriptor(
                          "test.event",
                          List.of(new FieldDescriptor("name", ValueType.STRING, true, false)))))));

  private static CapabilityDescriptor capability(String name, EffectClass effect) {
    return new CapabilityDescriptor(
        name,
        effect,
        true,
        List.of(new FieldDescriptor("value", ValueType.STRING, true, false)),
        List.of(new FieldDescriptor("echo", ValueType.STRING, true, false)));
  }

  @FunctionalInterface
  private interface Body {
    Map<String, Object> run(CapabilityContext ctx, Map<String, Object> args)
        throws CapabilityException;
  }

  private static Capability cap(String name, Body body) {
    return new Capability() {
      @Override
      public String name() {
        return name;
      }

      @Override
      public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
          throws CapabilityException {
        return body.run(context, args);
      }
    };
  }

  @BeforeEach
  void setUp() throws Exception {
    store = Store.open(dir.resolve("db/praksis.db"));
    capabilities.put("test.record", cap("test.record", (ctx, args) -> record("record", ctx, args)));
    capabilities.put(
        "test.fail",
        cap(
            "test.fail",
            (ctx, args) -> {
              throw new CapabilityException("boom", false);
            }));
    capabilities.put("test.slow", cap("test.slow", (ctx, args) -> record("slow", ctx, args)));
  }

  @AfterEach
  void tearDown() throws Exception {
    store.close();
  }

  private Map<String, Object> record(String kind, CapabilityContext ctx, Map<String, Object> args) {
    calls.add(kind + ":" + args.get("value"));
    contexts.add(ctx);
    return Map.of("echo", "echo-" + args.get("value"));
  }

  private void rule(String id, String extra, String actions) throws IOException {
    Path rules = dir.resolve("ruleset/rules");
    Files.createDirectories(rules);
    Files.writeString(
        dir.resolve("ruleset/ruleset.toml"),
        "format_version = 1\nname = \"t\"\n[param.prefix]\ntype = \"string\"\ndefault = \"p-\"\n");
    Files.writeString(
        rules.resolve(id + ".toml"),
        "id = \""
            + id
            + "\"\neffect = \"local-write\"\nneeds = [\"test.record\", \"test.fail\", \"test.slow\"]\n"
            + extra
            + "\n[trigger]\nkind = \"event\"\ntype = \"test.event\"\n"
            + actions);
  }

  private static String call(String id, String capability, String valueExpr) {
    return "\n[[action]]\nid = \""
        + id
        + "\"\ncapability = \""
        + capability
        + "\"\nargs = { value = { cel = \""
        + valueExpr.replace("\"", "\\\"")
        + "\" } }\n";
  }

  private Engine engine() throws RulesetException {
    return engine(Engine.Options.defaults());
  }

  private Engine engine(Engine.Options options) throws RulesetException {
    return new Engine(
        RulesetLoader.load(dir.resolve("ruleset")),
        CATALOG,
        Expressions.compiler(),
        capabilities,
        store,
        options);
  }

  private Event event(String target, String name) {
    return new Event(
        "e" + (++counter),
        "test.event",
        "test",
        Instant.parse("2026-01-01T00:00:00Z"),
        target,
        Optional.empty(),
        Optional.empty(),
        0,
        Map.of("name", name));
  }

  private void run(Engine engine, Event... events) throws Exception {
    for (Event e : events) {
      engine.submit(e);
    }
    assertThat(engine.awaitIdle(Duration.ofSeconds(10))).isTrue();
  }

  @Test
  void firesMatchingRulesInIdOrderWithEvaluatedArguments() throws Exception {
    rule("b", "", call("x", "test.record", "params.prefix + event.data.name"));
    rule("a", "", call("x", "test.record", "'a-' + event.data.name"));
    try (Engine engine = engine()) {
      run(engine, event("t", "mail"));
    }
    assertThat(calls).containsExactly("record:a-mail", "record:p-mail");
    assertThat(store.pending()).isEmpty();
    assertThat(store.recentLog(10)).extracting(LogEntry::outcome).containsOnly(LogEntry.Outcome.OK);
  }

  @Test
  void laterActionsReadEarlierResults() throws Exception {
    rule(
        "r",
        "",
        call("one", "test.record", "'x'") + call("two", "test.record", "results.one.echo"));
    try (Engine engine = engine()) {
      run(engine, event("t", "m"));
    }
    assertThat(calls).containsExactly("record:x", "record:echo-x");
  }

  @Test
  void filterDecidesWhetherARuleFires() throws Exception {
    rule("r", "", call("x", "test.record", "'v'"));
    Path file = dir.resolve("ruleset/rules/r.toml");
    Files.writeString(
        file,
        Files.readString(file)
            .replace(
                "type = \"test.event\"",
                "type = \"test.event\"\nfilter = \"event.data.name == 'yes'\""));
    try (Engine engine = engine()) {
      run(engine, event("t", "no"), event("t", "yes"));
    }
    assertThat(calls).containsExactly("record:v");
  }

  @Test
  void stopEndsTheHandlingOfTheEvent() throws Exception {
    rule("a", "", call("x", "test.record", "'first'") + "\n[[action]]\nstop = true\n");
    rule("b", "", call("x", "test.record", "'second'"));
    try (Engine engine = engine()) {
      run(engine, event("t", "m"));
    }
    assertThat(calls).containsExactly("record:first");
  }

  @Test
  void failedActionSkipsRestOfRuleButNextRuleContinues() throws Exception {
    rule("a", "", call("x", "test.fail", "'v'") + call("y", "test.record", "'never'"));
    rule("b", "", call("x", "test.record", "'second'"));
    try (Engine engine = engine()) {
      run(engine, event("t", "m"));
    }
    assertThat(calls).containsExactly("record:second");
    assertThat(store.recentLog(10))
        .anySatisfy(
            l -> {
              assertThat(l.outcome()).isEqualTo(LogEntry.Outcome.FAILED);
              assertThat(l.ruleId()).contains("a");
              assertThat(l.message()).contains("boom");
            });
    assertThat(store.pending()).isEmpty();
  }

  @Test
  void failedRequiredRuleEndsTheEvent() throws Exception {
    rule("a", "required = true", call("x", "test.fail", "'v'"));
    rule("b", "", call("x", "test.record", "'second'"));
    try (Engine engine = engine()) {
      run(engine, event("t", "m"));
    }
    assertThat(calls).isEmpty();
    assertThat(store.pending()).isEmpty();
  }

  @Test
  void accessPolicyCanDenyACall() throws Exception {
    rule("a", "", call("x", "test.record", "'v'"));
    List<String> audited = Collections.synchronizedList(new ArrayList<>());
    Engine.Options options =
        new Engine.Options(
            (rule, cap, args) -> AccessPolicy.Decision.deny("no grant"),
            (time, rule, cap, args, allowed, error) ->
                audited.add(cap + allowed + error.orElse("")),
            Engine.Options.defaults().clock(),
            false);
    try (Engine engine = engine(options)) {
      run(engine, event("t", "m"));
    }
    assertThat(calls).isEmpty();
    assertThat(audited).containsExactly("test.recordfalseno grant");
    assertThat(store.recentLog(10))
        .anyMatch(
            l ->
                l.outcome() == LogEntry.Outcome.DENIED && l.message().contains("denied: no grant"));
  }

  @Test
  void aDeniedCallDisablesOnlyThatRuleAndItIsSkippedFromThenOn() throws Exception {
    rule("a", "", call("x", "test.record", "'one'"));
    rule("b", "", call("x", "test.slow", "'two'"));
    AccessPolicy noRecord =
        (rule, cap, args) ->
            cap.name().equals("test.record")
                ? AccessPolicy.Decision.deny("no grant")
                : AccessPolicy.Decision.allow();
    Engine.Options options =
        new Engine.Options(noRecord, AuditLog.none(), Engine.Options.defaults().clock(), false);
    try (Engine engine = engine(options)) {
      run(engine, event("t", "m1"));
      assertThat(engine.disabledRules()).containsOnlyKeys("a");
      run(engine, event("t", "m2"));
    }
    // rule b keeps running for both events, rule a never ran
    assertThat(calls).containsExactly("slow:two", "slow:two");
    assertThat(store.recentLog(50))
        .filteredOn(l -> l.ruleId().equals(Optional.of("a")))
        .extracting(LogEntry::outcome)
        .containsExactlyInAnyOrder(LogEntry.Outcome.DENIED, LogEntry.Outcome.SKIPPED);
    assertThat(store.pending()).isEmpty();
  }

  @Test
  void eventsADisabledRuleMissedAreRememberedAndCallsAreAudited() throws Exception {
    rule("a", "", call("x", "test.record", "'one'"));
    rule("b", "", call("x", "test.slow", "'two'"));
    Engine.Options options =
        new Engine.Options(
            (r, c, a) ->
                r.equals("a")
                    ? AccessPolicy.Decision.deny("no grant")
                    : AccessPolicy.Decision.allow(),
            new StoreAuditLog(store),
            Engine.Options.defaults().clock(),
            false);
    Event first = event("t", "m1");
    Event second = event("t", "m2");
    try (Engine engine = engine(options)) {
      run(engine, first);
      run(engine, second);
    }
    assertThat(store.missed("a")).containsExactly(first.id(), second.id());
    assertThat(store.missed("b")).isEmpty();
    assertThat(store.event(first.id())).isPresent();

    var audit = store.recentAudit(10);
    assertThat(audit)
        .extracting(e -> e.ruleId().orElse("") + " " + e.subject() + " " + e.allowed())
        .containsExactly("a test.record false", "b test.slow true", "b test.slow true");
    assertThat(audit.get(0).error()).contains("no grant");
    assertThat(audit.get(1).detail()).isEqualTo("{\"value\":\"two\"}");
  }

  @Test
  void aDisabledRequiredRuleEndsTheEvent() throws Exception {
    rule("a", "required = true\n", call("x", "test.record", "'one'"));
    rule("b", "", call("x", "test.slow", "'two'"));
    Engine.Options options =
        new Engine.Options(
            (r, c, a) ->
                r.equals("a")
                    ? AccessPolicy.Decision.deny("no grant")
                    : AccessPolicy.Decision.allow(),
            AuditLog.none(),
            Engine.Options.defaults().clock(),
            false);
    try (Engine engine = engine(options)) {
      run(engine, event("t", "m1"));
      run(engine, event("t", "m2"));
    }
    assertThat(calls).isEmpty();
    assertThat(store.pending()).isEmpty();
  }

  @Test
  void contextCarriesDryRunFlagAndIdempotencyKey() throws Exception {
    rule("a", "", call("x", "test.record", "'v'"));
    Engine.Options options =
        new Engine.Options(
            AccessPolicy.allowAll(), AuditLog.none(), Engine.Options.defaults().clock(), true);
    Event e = event("t", "m");
    try (Engine engine = engine(options)) {
      run(engine, e);
    }
    assertThat(contexts).hasSize(1);
    assertThat(contexts.get(0).dryRun()).isTrue();
    assertThat(contexts.get(0).idempotencyKey()).isEqualTo(e.id() + "/a/x");
    assertThat(contexts.get(0).event()).contains(e);
  }

  @Test
  void duplicateKeyIsHandledOnce() throws Exception {
    rule("a", "", call("x", "test.record", "'v'"));
    Event first = event("t", "m");
    Event second =
        new Event(
            "other",
            first.type(),
            first.source(),
            first.time(),
            "t",
            Optional.of("k"),
            Optional.empty(),
            0,
            first.data());
    Event third =
        new Event(
            "third",
            first.type(),
            first.source(),
            first.time(),
            "t",
            Optional.of("k"),
            Optional.empty(),
            0,
            first.data());
    try (Engine engine = engine()) {
      assertThat(engine.submit(second)).isTrue();
      assertThat(engine.submit(third)).isFalse();
      assertThat(engine.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }
    assertThat(calls).hasSize(1);
  }

  @Test
  void restartHandlesPendingEventsOnlyOnce() throws Exception {
    rule("a", "", call("x", "test.record", "event.data.name"));
    // Events stored by a daemon that died before handling them.
    store.append(event("t", "one"));
    store.append(event("t", "two"));
    try (Engine engine = engine()) {
      engine.recover();
      assertThat(engine.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }
    assertThat(calls).containsExactly("record:one", "record:two");
    try (Engine engine = engine()) {
      engine.recover();
      assertThat(engine.awaitIdle(Duration.ofSeconds(10))).isTrue();
    }
    assertThat(calls).hasSize(2);
  }

  @Test
  void sameTargetIsSerialDifferentTargetsAreParallel() throws Exception {
    rule("a", "", call("x", "test.slow", "event.data.name"));
    AtomicInteger running = new AtomicInteger();
    AtomicInteger maxSameTarget = new AtomicInteger();
    CountDownLatch parallel = new CountDownLatch(3);
    List<String> order = Collections.synchronizedList(new ArrayList<>());
    capabilities.put(
        "test.slow",
        cap(
            "test.slow",
            (ctx, args) -> {
              String name = args.get("value").toString();
              order.add(name);
              if (name.startsWith("same")) {
                maxSameTarget.accumulateAndGet(running.incrementAndGet(), Math::max);
                sleep(20);
                running.decrementAndGet();
              } else {
                parallel.countDown();
                try {
                  if (!parallel.await(10, TimeUnit.SECONDS)) {
                    throw new CapabilityException("targets did not run in parallel", false);
                  }
                } catch (InterruptedException e) {
                  throw new CapabilityException("interrupted", false);
                }
              }
              return Map.of("echo", name);
            }));
    try (Engine engine = engine()) {
      for (int i = 0; i < 5; i++) {
        engine.submit(event("one", "same" + i));
      }
      engine.submit(event("a", "para-a"));
      engine.submit(event("b", "para-b"));
      engine.submit(event("c", "para-c"));
      assertThat(engine.awaitIdle(Duration.ofSeconds(20))).isTrue();
    }
    assertThat(maxSameTarget.get()).isEqualTo(1);
    assertThat(order.stream().filter(n -> n.startsWith("same")).toList())
        .containsExactly("same0", "same1", "same2", "same3", "same4");
    assertThat(store.recentLog(100)).noneMatch(l -> l.outcome() == LogEntry.Outcome.FAILED);
  }

  private static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  @Test
  void constructorRejectsMissingImplementationAndInvalidRules() throws Exception {
    rule("a", "", call("x", "test.record", "'v'"));
    capabilities.remove("test.fail");
    assertThatThrownBy(this::engine)
        .isInstanceOf(RulesetException.class)
        .hasMessageContaining("capability 'test.fail' is not provided");

    capabilities.put("test.fail", cap("test.fail", (ctx, args) -> Map.of()));
    rule("a", "", call("x", "test.record", "event.data.nope"));
    assertThatThrownBy(this::engine).isInstanceOf(RulesetException.class);
  }
}
