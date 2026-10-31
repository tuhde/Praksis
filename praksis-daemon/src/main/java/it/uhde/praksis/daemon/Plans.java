// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.engine.AuditLog;
import it.uhde.praksis.engine.GrantPolicy;
import it.uhde.praksis.engine.ModuleCalls;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Plans: what a person asks for in words, turned by the agent into literal capability calls that
 * the person looks at and approves, and that then run under the grants of one layer (the layer of
 * {@code [plan]} in {@code praksis.toml}), one after the other, exactly as a rule of that layer
 * would run them. The model never gets a right of its own:
 *
 * <ul>
 *   <li>a step is checked against the grants when the plan is made (the plan says which steps no
 *       grant covers, and those cannot run) and again, all of them before the first one, when the
 *       person has approved it;
 *   <li>only a person can run a plan (a request over the socket or the command line), never a rule
 *       and never the agent;
 *   <li>the capabilities of the daemon itself ({@code praksis.*}) cannot be steps of a plan;
 *   <li>every step that runs is an audit line, and so is the approval.
 * </ul>
 *
 * Before it proposes, the agent may read (read-only capabilities, under the same grants) to know
 * what it is talking about, for example which mails there are.
 */
final class Plans {
  private static final int MAX_READS = 5;
  private static final int TEXT = 3_000;

  private final DaemonConfig config;
  private final GrantPolicy access;
  private final ModuleCatalog catalog;
  private final Map<String, Capability> capabilities;
  private final AuditLog audit;
  private final Clock clock;
  private final Supplier<AgentStore> store;

  Plans(
      DaemonConfig config,
      GrantPolicy access,
      ModuleCatalog catalog,
      Map<String, Capability> capabilities,
      AuditLog audit,
      Clock clock,
      Supplier<AgentStore> store) {
    this.config = config;
    this.access = access;
    this.catalog = catalog;
    this.capabilities = capabilities;
    this.audit = audit;
    this.clock = clock;
    this.store = store;
  }

  Optional<String> layer() {
    return config.plan().layer();
  }

  /** The decision for a call of a plan: the grants of the plan layer, like a rule of that layer. */
  private AccessPolicy.Decision check(
      String layer, CapabilityDescriptor capability, Map<String, Object> args) {
    if (access.policy().find(layer, capability.name(), args).isPresent()) {
      return AccessPolicy.Decision.allow();
    }
    return AccessPolicy.Decision.deny(
        "layer '" + layer + "' has no grant for " + capability.name() + argsOf(args));
  }

  private static String argsOf(Map<String, Object> args) {
    return args.isEmpty() ? "" : " with " + Reports.shorten(args, 200);
  }

  private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();

  /**
   * A list given as such or as the text of a model ({@code <name>_json}): the first '[' to the last
   * ']' is read as JSON, so that a code fence or a word around it does not matter.
   */
  private static List<?> listArg(Map<String, Object> args, String name, boolean lenient)
      throws CapabilityException {
    if (args.get(name) instanceof List<?> list) {
      return list;
    }
    String text = args.get(name + "_json") instanceof String s ? s : "";
    int from = text.indexOf('[');
    int to = text.lastIndexOf(']');
    if (from < 0 || to < from) {
      if (lenient) {
        return List.of();
      }
      throw new CapabilityException(
          "'" + name + "' or '" + name + "_json' (a JSON list of {capability, args}) is required",
          false);
    }
    try {
      return JSON.readValue(text.substring(from, to + 1), List.class);
    } catch (java.io.IOException e) {
      if (lenient) {
        return List.of();
      }
      throw new CapabilityException(
          "'" + name + "_json' is not valid JSON: " + e.getMessage(), false);
    }
  }

  // ------------------------------------------------------------------------------- catalog

  /** What the plan layer may ask for at all: the capabilities that it has a grant for. */
  Map<String, Object> catalogOfGranted() throws CapabilityException {
    String layer = layerOrFail();
    List<Map<String, Object>> items = new ArrayList<>();
    StringBuilder report = new StringBuilder();
    report.append("What a plan may ask for (grants of layer ").append(layer).append("):\n");
    List<Grant> grants =
        access.policy().grants().stream().filter(g -> g.layer().equals(layer)).toList();
    for (CapabilityDescriptor c : sorted()) {
      List<Grant> mine = grants.stream().filter(g -> g.capability().equals(c.name())).toList();
      if (mine.isEmpty() || c.name().startsWith("praksis.")) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("capability", c.name());
      item.put("effect", c.effect().id());
      List<String> params = new ArrayList<>();
      for (FieldDescriptor p : c.params()) {
        params.add(p.name() + ":" + p.type().id() + (p.required() ? "*" : ""));
      }
      item.put("params", params);
      List<String> limits = new ArrayList<>();
      for (Grant g : mine) {
        if (g.args().isEmpty()) {
          limits.add("no limits");
        } else {
          List<String> parts = new ArrayList<>();
          g.args().forEach((k, m) -> parts.add(k + " " + m.describe()));
          limits.add(String.join(", ", parts));
        }
      }
      item.put("limits", limits);
      items.add(item);
      report
          .append("- ")
          .append(c.name())
          .append(" [")
          .append(c.effect().id())
          .append("] (")
          .append(String.join(", ", params))
          .append(") allowed with: ")
          .append(String.join(" | ", limits))
          .append('\n');
    }
    report.append("(a parameter with * is required)\n");
    return Map.of("items", items, "report", report.toString());
  }

  private List<CapabilityDescriptor> sorted() {
    List<CapabilityDescriptor> all = new ArrayList<>(catalog.capabilities());
    all.sort(java.util.Comparator.comparing(CapabilityDescriptor::name));
    return all;
  }

  // ------------------------------------------------------------------------------- read

  /**
   * Reads for the agent: up to five calls of read-only capabilities, under the grants of the plan
   * layer, each audited. A call that cannot be made is a line in the report, not an error, so that
   * the agent can see why.
   */
  Map<String, Object> read(Map<String, Object> args) throws CapabilityException {
    String layer = layerOrFail();
    List<?> list = listArg(args, "reads", true);
    StringBuilder report = new StringBuilder();
    int done = 0;
    for (Object o : list.size() > MAX_READS ? list.subList(0, MAX_READS) : list) {
      if (!(o instanceof Map<?, ?> m) || !(m.get("capability") instanceof String name)) {
        report.append("- a read without a capability was skipped\n");
        continue;
      }
      Map<String, Object> given = m.get("args") instanceof Map<?, ?> a ? stringKeys(a) : Map.of();
      report
          .append("- ")
          .append(name)
          .append(' ')
          .append(Reports.shorten(given, 200))
          .append(":\n");
      CapabilityDescriptor c = catalog.capability(name).orElse(null);
      if (c == null || name.startsWith("praksis.")) {
        report.append("  refused: there is no such capability for a plan\n");
        continue;
      }
      if (c.effect() != EffectClass.READ_ONLY) {
        report.append("  refused: only capabilities that read can be used to look around\n");
        continue;
      }
      Map<String, Object> coerced;
      try {
        coerced = coerce(c, given);
      } catch (IllegalArgumentException e) {
        report.append("  refused: ").append(e.getMessage()).append('\n');
        continue;
      }
      AccessPolicy.Decision decision = check(layer, c, coerced);
      String component = "plan-read";
      if (!decision.allowed()) {
        audit.record(
            clock.instant(), component, name, coerced, false, Optional.of(decision.reason()));
        report.append("  refused: ").append(decision.reason()).append('\n');
        continue;
      }
      try {
        Map<String, Object> result =
            ModuleCalls.invoke(capabilities.get(name), new PlanContext(component), coerced);
        audit.record(clock.instant(), component, name, coerced, true, Optional.empty());
        report.append("  ").append(Reports.shorten(result, TEXT)).append('\n');
        done++;
      } catch (Exception e) {
        audit.record(
            clock.instant(),
            component,
            name,
            coerced,
            true,
            Optional.of(String.valueOf(e.getMessage())));
        report.append("  failed: ").append(e.getMessage()).append('\n');
      }
    }
    if (list.size() > MAX_READS) {
      report.append("(only the first ").append(MAX_READS).append(" reads were made)\n");
    }
    return Map.of("report", report.toString(), "count", (long) done);
  }

  // ------------------------------------------------------------------------------- propose

  /** Keeps a plan that the agent made, after checking every step; says what can and cannot run. */
  Map<String, Object> propose(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException, IOException {
    String layer = layerOrFail();
    String request = text(args, "request", true);
    String reason = text(args, "reason", false);
    String seen = args.get("context") instanceof String s ? s : "";
    List<?> list = listArg(args, "steps", false);
    if (list.isEmpty()) {
      throw new CapabilityException("'steps' needs at least one {capability, args}", false);
    }
    if (list.size() > config.plan().maxSteps()) {
      throw new CapabilityException(
          "a plan has at most " + config.plan().maxSteps() + " steps, this one has " + list.size(),
          false);
    }
    List<AgentStore.Step> steps = new ArrayList<>();
    int n = 0;
    for (Object o : list) {
      n++;
      if (!(o instanceof Map<?, ?> m) || !(m.get("capability") instanceof String name)) {
        throw new CapabilityException("step " + n + " has no capability", false);
      }
      CapabilityDescriptor c = catalog.capability(name).orElse(null);
      if (c == null || name.startsWith("praksis.")) {
        throw new CapabilityException(
            "step " + n + ": there is no capability '" + name + "' for a plan", false);
      }
      Map<String, Object> given = m.get("args") instanceof Map<?, ?> a ? stringKeys(a) : Map.of();
      Map<String, Object> coerced;
      try {
        coerced = coerce(c, given);
      } catch (IllegalArgumentException e) {
        throw new CapabilityException("step " + n + " (" + name + "): " + e.getMessage(), false);
      }
      AccessPolicy.Decision decision = check(layer, c, coerced);
      steps.add(
          new AgentStore.Step(
              name,
              coerced,
              c.effect().id(),
              decision.allowed(),
              decision.allowed() ? "" : decision.reason(),
              "",
              ungrounded(coerced, request + "\n" + seen)));
    }
    long refused = steps.stream().filter(s -> !s.allowed()).count();
    String id = "";
    if (!context.dryRun()) {
      id =
          store
              .get()
              .savePlan(
                  request.length() > 500 ? request.substring(0, 500) : request,
                  reason == null ? "" : reason.length() > 500 ? reason.substring(0, 500) : reason,
                  layer,
                  steps)
              .id();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("id", id);
    out.put("steps", (long) steps.size());
    out.put("refused", refused);
    out.put("runnable", refused == 0);
    return out;
  }

  /**
   * Which values of a step the person did not write and the agent did not read: a text that a model
   * took from a mail can lead it to name a folder, an address or a file that nobody asked for.
   * Every text of the arguments should be found in the request or in what was read; the others are
   * said so in the plan, for the person to look at before approving.
   */
  static String ungrounded(Object args, String seen) {
    List<String> odd = new ArrayList<>();
    collect("", args, seen.toLowerCase(java.util.Locale.ROOT), odd);
    return odd.isEmpty()
        ? ""
        : "not in your request or in what was read: " + String.join(", ", odd);
  }

  private static void collect(String key, Object value, String seen, List<String> odd) {
    if (value instanceof Map<?, ?> m) {
      m.forEach((k, v) -> collect(String.valueOf(k), v, seen, odd));
    } else if (value instanceof List<?> l) {
      l.forEach(v -> collect(key, v, seen, odd));
    } else if (value instanceof String s
        && !s.isBlank()
        && !seen.contains(s.toLowerCase(java.util.Locale.ROOT))) {
      odd.add(key + " = " + Reports.shorten(s, 60));
    }
  }

  // ------------------------------------------------------------------------------- show

  /** The plan with its steps checked against the grants as they are now. */
  AgentStore.Plan current(AgentStore.Plan plan) {
    String layer = layer().orElse(plan.layer());
    List<AgentStore.Step> steps = new ArrayList<>();
    for (AgentStore.Step st : plan.steps()) {
      CapabilityDescriptor c = catalog.capability(st.capability()).orElse(null);
      if (c == null || plan.status().equals("DONE") || plan.status().equals("FAILED")) {
        steps.add(st);
        continue;
      }
      Map<String, Object> coerced;
      try {
        coerced = coerce(c, st.args());
      } catch (IllegalArgumentException e) {
        steps.add(
            new AgentStore.Step(
                st.capability(),
                st.args(),
                st.effect(),
                false,
                e.getMessage(),
                st.outcome(),
                st.warning()));
        continue;
      }
      AccessPolicy.Decision d = check(layer, c, coerced);
      steps.add(
          new AgentStore.Step(
              st.capability(),
              coerced,
              c.effect().id(),
              d.allowed(),
              d.allowed() ? "" : d.reason(),
              st.outcome(),
              st.warning()));
    }
    return plan.with(plan.status(), steps);
  }

  /** Whether a person can approve the plan now, and if not, what stands in the way. */
  Optional<String> obstacle(AgentStore.Plan current) {
    if (layer().isEmpty()) {
      return Optional.of("no layer is set for plans: add [plan] layer = \"...\" to praksis.toml");
    }
    if (!current.status().equals("PROPOSED")) {
      return Optional.of("the plan is " + current.status());
    }
    int n = 0;
    for (AgentStore.Step st : current.steps()) {
      n++;
      if (!st.allowed()) {
        return Optional.of("step " + n + " is not covered: " + st.refusal());
      }
    }
    return Optional.empty();
  }

  // ------------------------------------------------------------------------------- run

  /**
   * Runs an approved plan: every step is checked again before the first one is done, and a step
   * that fails ends the plan. The calls and their outcomes are audit lines and stay in the plan.
   */
  AgentStore.Plan run(String id) throws IOException, CapabilityException {
    AgentStore agent = store.get();
    AgentStore.Plan plan =
        agent
            .plan(id)
            .orElseThrow(() -> new CapabilityException("there is no plan '" + id + "'", false));
    String layer = layerOrFail();
    if (!plan.status().equals("PROPOSED")) {
      throw new CapabilityException(
          "plan " + id + " is " + plan.status() + " and is not run again", false);
    }
    // all the checks first: a plan that cannot run completely does not start
    List<AgentStore.Step> now = new ArrayList<>();
    int n = 0;
    for (AgentStore.Step st : plan.steps()) {
      n++;
      CapabilityDescriptor c = catalog.capability(st.capability()).orElse(null);
      if (c == null || capabilities.get(st.capability()) == null) {
        throw new CapabilityException(
            "step " + n + ": '" + st.capability() + "' is not available", false);
      }
      Map<String, Object> coerced;
      try {
        coerced = coerce(c, st.args());
      } catch (IllegalArgumentException e) {
        throw new CapabilityException(
            "step " + n + " (" + st.capability() + "): " + e.getMessage(), false);
      }
      AccessPolicy.Decision decision = check(layer, c, coerced);
      if (!decision.allowed()) {
        audit.record(
            clock.instant(),
            "plan:" + id,
            st.capability(),
            coerced,
            false,
            Optional.of(decision.reason()));
        throw new CapabilityException(
            "step " + n + " is refused and nothing was run: " + decision.reason(), false);
      }
      now.add(
          new AgentStore.Step(
              st.capability(), coerced, c.effect().id(), true, "", "", st.warning()));
    }
    agent.updatePlan(plan.with("RUNNING", now));
    audit.record(
        clock.instant(),
        "plan:" + id,
        "plan.approved",
        Map.of("request", plan.request(), "steps", (long) now.size(), "layer", layer),
        true,
        Optional.empty());
    List<AgentStore.Step> done = new ArrayList<>();
    boolean failed = false;
    for (AgentStore.Step st : now) {
      if (failed) {
        done.add(st.withOutcome("not run: an earlier step failed"));
        continue;
      }
      try {
        Map<String, Object> result =
            ModuleCalls.invoke(
                capabilities.get(st.capability()),
                new PlanContext("plan:" + id + "/" + (done.size() + 1)),
                st.args());
        audit.record(
            clock.instant(), "plan:" + id, st.capability(), st.args(), true, Optional.empty());
        done.add(st.withOutcome("ok: " + Reports.shorten(result, 300)));
      } catch (Exception e) {
        audit.record(
            clock.instant(),
            "plan:" + id,
            st.capability(),
            st.args(),
            true,
            Optional.of(String.valueOf(e.getMessage())));
        done.add(st.withOutcome("failed: " + e.getMessage()));
        failed = true;
      }
    }
    AgentStore.Plan result = plan.with(failed ? "FAILED" : "DONE", done);
    agent.updatePlan(result);
    return result;
  }

  // ------------------------------------------------------------------------------- helpers

  private String layerOrFail() throws CapabilityException {
    return layer()
        .orElseThrow(
            () ->
                new CapabilityException(
                    "plans need a layer whose grants they run under: add [plan] layer = \"...\" to praksis.toml",
                    false));
  }

  private static Map<String, Object> stringKeys(Map<?, ?> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }

  /** The arguments as the capability wants them, or what is wrong with them. */
  private static Map<String, Object> coerce(CapabilityDescriptor c, Map<String, Object> given) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var e : given.entrySet()) {
      FieldDescriptor field =
          c.params().stream()
              .filter(p -> p.name().equals(e.getKey()))
              .findFirst()
              .orElseThrow(
                  () ->
                      new IllegalArgumentException(
                          "'" + c.name() + "' has no argument '" + e.getKey() + "'"));
      out.put(e.getKey(), Injection.coerce(field, e.getValue()));
    }
    for (FieldDescriptor p : c.params()) {
      if (p.required() && !out.containsKey(p.name())) {
        throw new IllegalArgumentException(
            "'" + c.name() + "' needs the argument '" + p.name() + "'");
      }
    }
    return out;
  }

  private static String text(Map<String, Object> args, String name, boolean required)
      throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      if (required) {
        throw new CapabilityException("'" + name + "' is required", false);
      }
      return null;
    }
    if (!(v instanceof String s) || (required && s.isBlank())) {
      throw new CapabilityException("'" + name + "' must be a text", false);
    }
    return s;
  }

  /** The context of a call that a plan makes. */
  private record PlanContext(String key) implements CapabilityContext {
    @Override
    public boolean dryRun() {
      return false;
    }

    @Override
    public String idempotencyKey() {
      return key;
    }

    @Override
    public Optional<Event> event() {
      return Optional.empty();
    }
  }
}
