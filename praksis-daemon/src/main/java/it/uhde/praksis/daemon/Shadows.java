// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import it.uhde.praksis.engine.Engine;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.Rule;
import it.uhde.praksis.ruleset.RuleView;
import it.uhde.praksis.ruleset.Ruleset;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.ruleset.Trigger;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Promoted;
import it.uhde.praksis.store.ShadowRule;
import it.uhde.praksis.store.ShadowRun;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Trial of new and changed rules. A version of a rule counts as live once it was promoted; a rule
 * that is not, is observed instead: the older promoted version of the rule (if there is one) keeps
 * running, and the new one only records what it would have done. The rules in use when shadow mode
 * is first turned on are taken as promoted. A person ends a trial with {@code praksis shadow
 * promote}; the daemon only says when the trial period is over.
 */
final class Shadows {
  /** A request that cannot be done. */
  static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;

    Failure(String message) {
      super(message);
    }
  }

  /**
   * What the engine is given.
   *
   * @param live the rules that run: promoted versions
   * @param trial the versions on trial
   */
  record Plan(Ruleset live, List<Rule> trial) {}

  private static final String BASELINE = "shadow.baseline";
  private static final ObjectMapper JSON = new ObjectMapper();

  private final DaemonConfig config;
  private final Store store;
  private final Clock clock;
  private final LayerStack.Effective effective;
  private Engine engine;

  Shadows(DaemonConfig config, Store store, Clock clock, LayerStack.Effective effective) {
    this.config = config;
    this.store = store;
    this.clock = clock;
    this.effective = effective;
  }

  void bind(Engine engine) {
    this.engine = engine;
  }

  /** Splits the rules into the ones that run and the ones on trial, and starts new trials. */
  Plan plan() throws StoreException, RulesetException {
    Ruleset all = effective.ruleset();
    if (!config.shadow().enabled()) {
      return new Plan(all, List.of());
    }
    Instant now = clock.instant();
    if (store.meta(BASELINE).isEmpty()) {
      for (Rule rule : all.rules()) {
        store.promote(new Promoted(rule.id(), RuleView.hash(rule), now, RuleView.toJson(rule)));
      }
      store.setMeta(BASELINE, now.toString());
    }
    List<Promoted> promoted = store.promoted();
    Set<String> promotedVersions =
        promoted.stream().map(p -> p.ruleId() + " " + p.hash()).collect(Collectors.toSet());
    List<Rule> live = new ArrayList<>();
    List<Rule> trial = new ArrayList<>();
    Set<String> trialVersions = new LinkedHashSet<>();
    for (Rule rule : all.rules()) {
      String hash = RuleView.hash(rule);
      // a command only runs when a person starts it, so there is nothing to observe
      if (rule.trigger() instanceof Trigger.OnCommand
          || promotedVersions.contains(rule.id() + " " + hash)) {
        live.add(rule);
        continue;
      }
      trial.add(rule);
      trialVersions.add(rule.id() + " " + hash);
      store.startShadow(new ShadowRule(rule.id(), hash, now));
      Optional<Promoted> before =
          promoted.stream().filter(p -> p.ruleId().equals(rule.id())).reduce((a, b) -> b);
      if (before.isPresent()) {
        live.add(RuleView.fromJson(before.get().json()));
      }
    }
    for (ShadowRule old : store.shadowRules()) {
      if (!trialVersions.contains(old.ruleId() + " " + old.hash())) {
        store.endShadow(old.ruleId(), old.hash()); // changed again, or gone
      }
    }
    return new Plan(new Ruleset(all.formatVersion(), all.name(), all.params(), live), trial);
  }

  /** The rules on trial, with what they were seen to do. */
  Response.ShadowList list() throws StoreException {
    List<Response.ShadowList.Item> items = new ArrayList<>();
    List<Promoted> promoted = store.promoted();
    for (ShadowRule trial : store.shadowRules()) {
      List<ShadowRun> runs = store.shadowRuns(trial.ruleId(), trial.hash(), Engine.SHADOW_KEEP);
      int calling = 0;
      int denied = 0;
      int failing = 0;
      for (ShadowRun run : runs) {
        JsonNode calls = parse(run.calls());
        if (calls.size() > 0) {
          calling++;
        }
        if (calls.findValues("allowed").stream().anyMatch(n -> !n.asBoolean(true))) {
          denied++;
        }
        if (run.note().startsWith("would fail")) {
          failing++;
        }
      }
      List<Response.ShadowList.Sample> samples = new ArrayList<>();
      for (ShadowRun run : runs.stream().limit(5).toList()) {
        samples.add(
            new Response.ShadowList.Sample(
                run.eventId(), callLines(parse(run.calls())), run.note(), live(run.eventId())));
      }
      Duration age = Duration.between(trial.since(), clock.instant());
      int days = config.shadow().trialDays();
      int needEvents = config.shadow().minEvents();
      List<String> missing = new ArrayList<>();
      if (age.compareTo(Duration.ofDays(days)) < 0) {
        long left = Duration.ofDays(days).minus(age).toHours() + 1;
        missing.add(left >= 48 ? (left / 24) + " more day(s)" : left + " more hour(s)");
      }
      if (runs.size() < needEvents) {
        missing.add((needEvents - runs.size()) + " more event(s)");
      }
      items.add(
          new Response.ShadowList.Item(
              trial.ruleId(),
              trial.hash(),
              trial.since().toString(),
              promoted.stream().anyMatch(p -> p.ruleId().equals(trial.ruleId())),
              runs.size(),
              calling,
              denied,
              failing,
              missing.isEmpty(),
              missing.isEmpty() ? "ready to promote" : "needs " + String.join(" and ", missing),
              samples));
    }
    return new Response.ShadowList(config.shadow().enabled(), items);
  }

  private static JsonNode parse(String json) {
    try {
      return JSON.readTree(json);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return JSON.createArrayNode();
    }
  }

  private static List<String> callLines(JsonNode calls) {
    List<String> out = new ArrayList<>();
    for (JsonNode call : calls) {
      StringBuilder line = new StringBuilder(call.path("capability").asText());
      java.util.TreeMap<String, String> args = new java.util.TreeMap<>();
      call.path("args").properties().forEach(e -> args.put(e.getKey(), e.getValue().asText()));
      args.forEach((k, v) -> line.append(' ').append(k).append('=').append(v));
      if (!call.path("allowed").asBoolean(true)) {
        line.append("   NOT ALLOWED: ").append(call.path("reason").asText());
      }
      out.add(line.toString());
    }
    return out;
  }

  /** The rules that really handled the event, with their outcome. */
  private List<String> live(String eventId) throws StoreException {
    Set<String> out = new LinkedHashSet<>();
    for (LogEntry entry : store.logOf(eventId)) {
      entry.ruleId().ifPresent(rule -> out.add(rule + ": " + entry.outcome()));
    }
    return List.copyOf(out);
  }

  /** Ends the trial of a rule: from now on its new version runs live. */
  String promote(String ruleId) throws Failure, StoreException {
    ShadowRule trial = onTrial(ruleId);
    Rule rule =
        effective.ruleset().rules().stream()
            .filter(r -> r.id().equals(ruleId) && RuleView.hash(r).equals(trial.hash()))
            .findFirst()
            .orElseThrow(() -> new Failure("the rule '" + ruleId + "' is not what was on trial"));
    store.promote(new Promoted(ruleId, trial.hash(), clock.instant(), RuleView.toJson(rule)));
    store.endShadow(ruleId, trial.hash());
    if (!engine.promote(ruleId)) {
      throw new Failure("the rule '" + ruleId + "' is not on trial in the running daemon");
    }
    return "Promoted " + ruleId + ": it runs live from now on.";
  }

  /** Forgets what a rule on trial has been seen to do and starts the trial again. */
  String reset(String ruleId) throws Failure, StoreException {
    ShadowRule trial = onTrial(ruleId);
    store.restartShadow(ruleId, trial.hash(), clock.instant());
    return "Started the trial of " + ruleId + " again.";
  }

  private ShadowRule onTrial(String ruleId) throws Failure, StoreException {
    if (!config.shadow().enabled()) {
      throw new Failure("shadow mode is not turned on: set enabled = true in [shadow]");
    }
    return store.shadowRules().stream()
        .filter(r -> r.ruleId().equals(ruleId))
        .findFirst()
        .orElseThrow(() -> new Failure("the rule '" + ruleId + "' is not on trial"));
  }
}
