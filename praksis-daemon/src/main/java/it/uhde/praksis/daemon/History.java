// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * What happened, for questions such as "why was this mail moved?" and "what did the rules do this
 * week?": the stored events, the run log and the audit log, read through bounded, read-only
 * queries. Nothing here is written or changed. The answers are plain data (with long texts cut), so
 * that a person, a rule or a model can read them; what a model does with them is up to the rules
 * that call these capabilities, under their grants.
 */
final class History {
  private static final int TEXT = 200;
  private static final int SCAN = 5_000;

  private final Store store;
  private final Supplier<LayerStack.Effective> effective;

  /**
   * @param effective the ruleset in use, to say which layer owns a rule; may give null
   */
  History(Store store, Supplier<LayerStack.Effective> effective) {
    this.store = store;
    this.effective = effective;
  }

  // ------------------------------------------------------------------------------- find

  /**
   * The events that match: of a type, with a text in any of their fields, in the last hours, newest
   * first, each with what the rules did with it in a line or two.
   */
  Map<String, Object> find(Map<String, Object> args) throws CapabilityException, StoreException {
    String text = string(args, "text");
    String type = string(args, "type");
    Instant since = since(args, 168);
    int limit = number(args, "limit", 10, 1, 50);
    String needle = text == null ? null : text.toLowerCase(java.util.Locale.ROOT);
    List<Event> events = store.recentEvents(SCAN);
    List<Map<String, Object>> items = new ArrayList<>();
    for (int i = events.size() - 1; i >= 0 && items.size() < limit; i--) {
      Event e = events.get(i);
      // an event that is still being handled is not history yet (it is the question being asked,
      // for
      // one)
      if (e.time().isBefore(since)
          || !store.isDone(e.id())
          || (type != null && !e.type().equals(type))
          || (needle != null && !mentions(e, needle))) {
        continue;
      }
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("event", e.id());
      item.put("type", e.type());
      item.put("time", e.time().toString());
      item.put("data", data(e));
      item.put("what_happened", happened(e));
      items.add(item);
    }
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("items", items);
    out.put("searched", (long) events.size());
    out.put("report", reportFind(items, events.size()));
    return out;
  }

  private static boolean mentions(Event e, String needle) {
    for (Object v : e.data().values()) {
      if (v instanceof String s && s.toLowerCase(java.util.Locale.ROOT).contains(needle)) {
        return true;
      }
    }
    return false;
  }

  /** The text fields of an event, cut: all that a question about it needs. */
  private static Map<String, Object> data(Event e) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (var entry : new TreeMap<>(e.data()).entrySet()) {
      Object v = entry.getValue();
      if (v instanceof String s) {
        out.put(entry.getKey(), cut(s));
      } else if (v instanceof Number || v instanceof Boolean) {
        out.put(entry.getKey(), v);
      }
    }
    return out;
  }

  private String happened(Event e) throws StoreException {
    List<LogEntry> log = store.logOf(e.id());
    if (log.isEmpty()) {
      return store.isDone(e.id()) ? "no rule handled it" : "waiting to be handled";
    }
    List<String> parts = new ArrayList<>();
    for (LogEntry l : log) {
      parts.add(
          l.ruleId().orElse("(no rule)")
              + l.actionId().map(a -> "/" + a).orElse("")
              + " "
              + l.outcome()
              + ": "
              + cut(l.message()));
    }
    return String.join("; ", parts.size() > 6 ? parts.subList(0, 6) : parts)
        + (parts.size() > 6 ? "; ... (" + (parts.size() - 6) + " more)" : "");
  }

  // ------------------------------------------------------------------------------- trace

  /**
   * Everything that is known about one event: what it was, each step that handled it with the
   * outcome, the capability calls that were made for it (the arguments, whether they were allowed)
   * and which layer owns each rule that handled it.
   */
  Map<String, Object> trace(Map<String, Object> args) throws CapabilityException, StoreException {
    String id = string(args, "event");
    if (id == null || id.isBlank()) {
      throw new CapabilityException("'event' is required", false);
    }
    Event e =
        store
            .event(id)
            .orElseThrow(() -> new CapabilityException("there is no event '" + id + "'", false));
    List<LogEntry> log = store.logOf(id);
    Map<String, Object> out = new LinkedHashMap<>();
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("id", e.id());
    event.put("type", e.type());
    event.put("source", e.source());
    event.put("time", e.time().toString());
    event.put("data", data(e));
    out.put("event", event);

    List<Map<String, Object>> steps = new ArrayList<>();
    java.util.Set<String> rules = new java.util.LinkedHashSet<>();
    Instant last = e.time();
    for (LogEntry l : log) {
      Map<String, Object> step = new LinkedHashMap<>();
      step.put("time", l.time().toString());
      step.put("rule", l.ruleId().orElse(""));
      step.put("step", l.actionId().orElse(""));
      step.put("outcome", l.outcome().name());
      step.put("message", cut(l.message()));
      steps.add(step);
      l.ruleId().ifPresent(rules::add);
      if (l.time().isAfter(last)) {
        last = l.time();
      }
    }
    out.put("steps", steps);

    // the capability calls of those rules while the event was handled (the audit log names the rule
    // and the time, not the event)
    List<Map<String, Object>> calls = new ArrayList<>();
    Instant from = e.time().minusSeconds(1);
    Instant to = last.plusSeconds(2);
    for (AuditEntry a : store.recentAudit(SCAN)) {
      if (a.kind() == AuditEntry.Kind.CALL
          && a.ruleId().isPresent()
          && rules.contains(a.ruleId().get())
          && !a.time().isBefore(from)
          && !a.time().isAfter(to)) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("time", a.time().toString());
        call.put("rule", a.ruleId().get());
        call.put("capability", a.subject());
        call.put("allowed", a.allowed());
        call.put("arguments", cut(a.detail()));
        call.put("error", a.error().map(History::cut).orElse(""));
        calls.add(call);
      }
    }
    out.put("calls", calls);

    List<Map<String, Object>> owners = new ArrayList<>();
    LayerStack.Effective now = effective == null ? null : effective.get();
    for (String rule : rules) {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("rule", rule);
      Optional<LayerStack.Origin> origin =
          now == null
              ? Optional.empty()
              : now.origins().stream().filter(x -> x.ruleId().equals(rule)).findFirst();
      o.put("layer", origin.map(LayerStack.Origin::owner).orElse(""));
      o.put("history", origin.map(x -> String.join(", ", x.history())).orElse(""));
      o.put(
          "fired_by",
          now == null
              ? ""
              : now.ruleset().rules().stream()
                  .filter(r -> r.id().equals(rule))
                  .findFirst()
                  .map(History::firedBy)
                  .orElse(""));
      owners.add(o);
    }
    out.put("rules", owners);
    out.put("report", reportTrace(out));
    return out;
  }

  /** What makes a rule fire, in words: the event and its filter, or the command. */
  private static String firedBy(it.uhde.praksis.ruleset.Rule rule) {
    return switch (rule.trigger()) {
      case it.uhde.praksis.ruleset.Trigger.OnEvent e ->
          "the event " + e.type() + e.filter().map(f -> " when " + cut(f)).orElse("");
      case it.uhde.praksis.ruleset.Trigger.OnCommand c -> "the command \"" + cut(c.title()) + "\"";
    };
  }

  // ------------------------------------------------------------------------------- summary

  /** What the rules did in the last hours: events, outcomes per rule, calls, refusals. */
  Map<String, Object> summary(Map<String, Object> args) throws CapabilityException, StoreException {
    Instant since = since(args, 168);
    Map<String, Long> events = new TreeMap<>();
    for (Event e : store.recentEvents(SCAN)) {
      if (!e.time().isBefore(since)) {
        events.merge(e.type(), 1L, Long::sum);
      }
    }
    Map<String, Map<String, Long>> perRule = new TreeMap<>();
    for (LogEntry l : store.recentLog(SCAN)) {
      if (!l.time().isBefore(since) && l.ruleId().isPresent()) {
        perRule
            .computeIfAbsent(l.ruleId().get(), k -> new TreeMap<>())
            .merge(l.outcome().name(), 1L, Long::sum);
      }
    }
    Map<String, Long> calls = new TreeMap<>();
    List<Map<String, Object>> refused = new ArrayList<>();
    for (AuditEntry a : store.recentAudit(SCAN)) {
      if (a.kind() != AuditEntry.Kind.CALL || a.time().isBefore(since)) {
        continue;
      }
      if (a.allowed()) {
        calls.merge(a.subject(), 1L, Long::sum);
      } else if (refused.size() < 10) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("time", a.time().toString());
        r.put("rule", a.ruleId().orElse(""));
        r.put("capability", a.subject());
        r.put("reason", a.error().map(History::cut).orElse(""));
        refused.add(r);
      }
    }
    List<Map<String, Object>> rules = new ArrayList<>();
    perRule.forEach(
        (rule, outcomes) -> {
          Map<String, Object> r = new LinkedHashMap<>();
          r.put("rule", rule);
          r.put("ok", outcomes.getOrDefault("OK", 0L));
          r.put("failed", outcomes.getOrDefault("FAILED", 0L));
          r.put("skipped", outcomes.getOrDefault("SKIPPED", 0L));
          r.put("denied", outcomes.getOrDefault("DENIED", 0L));
          rules.add(r);
        });
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("since", since.toString());
    out.put("events", new LinkedHashMap<String, Object>(events));
    out.put("rules", rules);
    out.put("calls", new LinkedHashMap<String, Object>(calls));
    out.put("refused", refused);
    out.put("report", reportSummary(out));
    return out;
  }

  // ------------------------------------------------------------------------------- reports

  /** The same as the lists, as text that a person or a model reads from top to bottom. */
  @SuppressWarnings("unchecked")
  private static String reportFind(List<Map<String, Object>> items, int searched) {
    StringBuilder b = new StringBuilder();
    b.append(items.size()).append(" event(s) found among the last ").append(searched).append('\n');
    for (Map<String, Object> item : items) {
      b.append("- event ").append(item.get("event")).append(' ').append(item.get("type"));
      b.append(" at ").append(item.get("time")).append(": ");
      b.append(fields((Map<String, Object>) item.get("data")));
      b.append(" => ").append(item.get("what_happened")).append('\n');
    }
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static String reportTrace(Map<String, Object> trace) {
    StringBuilder b = new StringBuilder();
    Map<String, Object> e = (Map<String, Object>) trace.get("event");
    b.append("Event ").append(e.get("id")).append(' ').append(e.get("type"));
    b.append(" from ").append(e.get("source")).append(" at ").append(e.get("time")).append('\n');
    b.append("Data: ").append(fields((Map<String, Object>) e.get("data"))).append('\n');
    b.append("Steps:\n");
    for (Map<String, Object> s : (List<Map<String, Object>>) trace.get("steps")) {
      b.append("- ").append(s.get("rule"));
      if (!s.get("step").toString().isEmpty()) {
        b.append('/').append(s.get("step"));
      }
      b.append(' ').append(s.get("outcome")).append(": ").append(s.get("message")).append('\n');
    }
    b.append("Capability calls:\n");
    for (Map<String, Object> c : (List<Map<String, Object>>) trace.get("calls")) {
      b.append("- ").append(c.get("rule")).append(" called ").append(c.get("capability"));
      b.append(" with ").append(c.get("arguments"));
      b.append(
          Boolean.TRUE.equals(c.get("allowed"))
              ? " (allowed)"
              : " (refused: " + c.get("error") + ")");
      b.append('\n');
    }
    b.append("Rules:\n");
    for (Map<String, Object> r : (List<Map<String, Object>>) trace.get("rules")) {
      b.append("- ").append(r.get("rule")).append(" of layer ").append(r.get("layer"));
      if (!r.get("history").toString().isEmpty()) {
        b.append(" (").append(r.get("history")).append(')');
      }
      if (!r.get("fired_by").toString().isEmpty()) {
        b.append(", fired by ").append(r.get("fired_by"));
      }
      b.append('\n');
    }
    return b.toString();
  }

  @SuppressWarnings("unchecked")
  private static String reportSummary(Map<String, Object> summary) {
    StringBuilder b = new StringBuilder("Since " + summary.get("since") + ":\n");
    b.append("Events: ").append(counts((Map<String, Object>) summary.get("events"))).append('\n');
    b.append("Rules:\n");
    for (Map<String, Object> r : (List<Map<String, Object>>) summary.get("rules")) {
      b.append("- ").append(r.get("rule")).append(": ok ").append(r.get("ok"));
      b.append(", failed ").append(r.get("failed")).append(", skipped ").append(r.get("skipped"));
      b.append(", denied ").append(r.get("denied")).append('\n');
    }
    b.append("Capability calls: ")
        .append(counts((Map<String, Object>) summary.get("calls")))
        .append('\n');
    b.append("Refused calls:\n");
    for (Map<String, Object> r : (List<Map<String, Object>>) summary.get("refused")) {
      b.append("- ").append(r.get("rule")).append(" asked for ").append(r.get("capability"));
      b.append(": ").append(r.get("reason")).append('\n');
    }
    return b.toString();
  }

  private static String fields(Map<String, Object> data) {
    StringBuilder b = new StringBuilder();
    data.forEach((k, v) -> b.append(b.length() == 0 ? "" : "; ").append(k).append('=').append(v));
    return b.toString();
  }

  private static String counts(Map<String, Object> counts) {
    if (counts.isEmpty()) {
      return "none";
    }
    StringBuilder b = new StringBuilder();
    counts.forEach((k, v) -> b.append(b.length() == 0 ? "" : ", ").append(k).append(' ').append(v));
    return b.toString();
  }

  // ------------------------------------------------------------------------------- helpers

  private static Instant since(Map<String, Object> args, int defaultHours)
      throws CapabilityException {
    int hours = number(args, "since_hours", defaultHours, 1, 24 * 90);
    return Instant.now().minus(Duration.ofHours(hours));
  }

  private static String string(Map<String, Object> args, String name) throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      return null;
    }
    if (!(v instanceof String s)) {
      throw new CapabilityException("'" + name + "' must be a text", false);
    }
    return s.isBlank() ? null : s;
  }

  private static int number(Map<String, Object> args, String name, int fallback, int min, int max)
      throws CapabilityException {
    Object v = args.get(name);
    if (v == null) {
      return fallback;
    }
    if (!(v instanceof Number n) || n.longValue() < min || n.longValue() > max) {
      throw new CapabilityException(
          "'" + name + "' must be a whole number from " + min + " to " + max, false);
    }
    return n.intValue();
  }

  private static String cut(String text) {
    if (text == null) {
      return "";
    }
    String one = text.replaceAll("\\p{Cntrl}+", " ").strip();
    return one.length() > TEXT ? one.substring(0, TEXT) + "..." : one;
  }
}
