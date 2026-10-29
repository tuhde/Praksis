// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Store;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The real engine with a temporary store and a recorder in place of every capability: events go in,
 * and what the rules would have called comes out. Nothing outside the process is touched. A
 * recorder returns the mocked results of its capability, or a default value for each declared
 * result so that rules that read {@code results} still evaluate.
 */
final class Simulator implements AutoCloseable {
  /** A call that a rule made. */
  record Call(String rule, String capability, Map<String, Object> args) {
    String describe() {
      return capability + " " + shown(args);
    }
  }

  /**
   * Bytes have no TOML form: a test writes them as Base64 text, which is also how they are shown.
   */
  static Object shown(Object value) {
    if (value instanceof byte[] bytes) {
      return java.util.Base64.getEncoder().encodeToString(bytes);
    }
    if (value instanceof Map<?, ?> map) {
      Map<Object, Object> out = new java.util.LinkedHashMap<>();
      map.forEach((k, v) -> out.put(k, shown(v)));
      return out;
    }
    if (value instanceof List<?> list) {
      return list.stream().map(Simulator::shown).toList();
    }
    return value;
  }

  /** A mocked result of a bytes field is written as Base64 text; the capability returns bytes. */
  private static Map<String, Object> withBytes(CapabilityDescriptor c, Map<String, Object> mock) {
    Map<String, Object> out = new java.util.LinkedHashMap<>(mock);
    for (var field : c.results()) {
      if (field.type() == it.uhde.praksis.capability.ValueType.BYTES
          && out.get(field.name()) instanceof String text) {
        try {
          out.put(field.name(), java.util.Base64.getDecoder().decode(text));
        } catch (IllegalArgumentException e) {
          throw new IllegalArgumentException(
              "the mocked result '" + field.name() + "' of " + c.name() + " is not Base64", e);
        }
      }
    }
    return out;
  }

  /**
   * What happened to one event.
   *
   * @param calls the calls, in order
   * @param fired the rules that matched the event and ran
   * @param failures the problems, as {@code rule: message}
   */
  record Run(List<Call> calls, Set<String> fired, List<String> failures) {}

  private final Path temp;
  private final Store store;
  private final Engine engine;
  private final List<Call> calls = Collections.synchronizedList(new ArrayList<>());

  Simulator(
      Ruleset ruleset,
      ModuleCatalog catalog,
      ExpressionCompiler compiler,
      Map<String, Map<String, Object>> mocks)
      throws RulesetException, IOException, it.uhde.praksis.store.StoreException {
    temp = Files.createTempDirectory("praksis-sim");
    Store opened = null;
    try {
      Map<String, Capability> recorders = new HashMap<>();
      for (CapabilityDescriptor c : catalog.capabilities()) {
        recorders.put(
            c.name(),
            new Recorder(
                c.name(), withBytes(c, mocks.getOrDefault(c.name(), Defaults.results(c)))));
      }
      opened = Store.open(temp.resolve("sim.db"));
      engine =
          new Engine(
              ruleset,
              catalog,
              compiler,
              recorders,
              opened,
              new Engine.Options(
                  AccessPolicy.allowAll(),
                  (time, rule, capability, args, allowed, error) ->
                      calls.add(new Call(rule, capability, args)),
                  Clock.systemUTC(),
                  false));
      store = opened;
    } catch (RulesetException | RuntimeException | it.uhde.praksis.store.StoreException e) {
      closeQuietly(opened);
      delete(temp);
      throw e;
    }
  }

  /** Hands the event to the rules and waits until it is handled. */
  Run run(Event event) throws Exception {
    calls.clear();
    engine.submit(event);
    if (!engine.awaitIdle(Duration.ofSeconds(30))) {
      throw new IllegalStateException("the event was not handled within 30 seconds");
    }
    Set<String> fired = new LinkedHashSet<>();
    List<String> failures = new ArrayList<>();
    for (LogEntry entry : store.recentLog(1000)) {
      if (!entry.eventId().equals(event.id()) || entry.ruleId().isEmpty()) {
        continue;
      }
      fired.add(entry.ruleId().get());
      if (entry.outcome() == LogEntry.Outcome.FAILED
          || entry.outcome() == LogEntry.Outcome.DENIED) {
        failures.add(entry.ruleId().get() + ": " + entry.message());
      }
    }
    return new Run(List.copyOf(calls), fired, failures);
  }

  @Override
  public void close() {
    engine.close();
    closeQuietly(store);
    delete(temp);
  }

  private static void closeQuietly(Store store) {
    if (store != null) {
      try {
        store.close();
      } catch (it.uhde.praksis.store.StoreException e) {
        // nothing is kept
      }
    }
  }

  private static void delete(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    } catch (IOException e) {
      // a temporary directory that stays behind does no harm
    }
  }

  /** Stands in for a capability: does nothing and returns the given results. */
  private record Recorder(String name, Map<String, Object> results) implements Capability {
    @Override
    public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args) {
      return results;
    }
  }
}
