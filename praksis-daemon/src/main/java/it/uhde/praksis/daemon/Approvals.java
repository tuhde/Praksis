// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.engine.Denials;
import it.uhde.praksis.engine.Engine;
import it.uhde.praksis.engine.GrantPolicy;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.PolicyFile;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.RulesetException;
import it.uhde.praksis.store.Approval;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.io.IOException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Requests for permissions and what answers do. A rule whose call no grant covers is disabled and a
 * request is opened. A person answers it: "allow once" lets the one denied event through, "allow
 * always" adds a grant for exactly what was seen to {@code policy.toml}, "deny" keeps the rule
 * disabled. The AI has no way to answer: only the CLI on the local socket does, and the grants are
 * in a file that is not part of any ruleset.
 */
final class Approvals implements Denials {
  private static final System.Logger LOG = System.getLogger(Approvals.class.getName());

  /** Something a person asked for cannot be done; the message says why. */
  static final class Failure extends Exception {
    private static final long serialVersionUID = 1L;

    Failure(String message) {
      super(message);
    }

    Failure(String message, Throwable cause) {
      super(message, cause);
    }
  }

  private final DaemonConfig config;
  private final Store store;
  private final GrantPolicy access;
  private final ModuleCatalog catalog;
  private final Clock clock;
  private Engine engine;

  Approvals(
      DaemonConfig config, Store store, GrantPolicy access, ModuleCatalog catalog, Clock clock) {
    this.config = config;
    this.store = store;
    this.access = access;
    this.catalog = catalog;
    this.clock = clock;
  }

  /** The engine is made with this as its {@link Denials}, so it is handed over afterwards. */
  void bind(Engine engine) {
    this.engine = engine;
  }

  @Override
  public void denied(
      String ruleId, CapabilityDescriptor capability, Map<String, Object> args, Event event) {
    try {
      Approval a =
          store.openApproval(
              clock.instant(),
              ruleId,
              access.layerOf(ruleId).orElse("?"),
              capability.name(),
              scoped(capability, args),
              event.id());
      audit("request #" + a.id(), ruleId, Map.of("capability", a.capability(), "args", a.args()));
      LOG.log(
          System.Logger.Level.WARNING,
          "rule " + ruleId + " is disabled: it needs a permission. See: praksis approvals");
    } catch (StoreException e) {
      System.err.println("praksis: cannot open a request for " + ruleId + ": " + e.getMessage());
    }
  }

  /** The arguments that a grant may restrict; the others never go into a grant. */
  private static Map<String, Object> scoped(CapabilityDescriptor c, Map<String, Object> args) {
    Map<String, Object> out = new LinkedHashMap<>();
    for (FieldDescriptor p : c.params()) {
      if (p.scopable() && args.containsKey(p.name())) {
        out.put(p.name(), args.get(p.name()));
      }
    }
    return out;
  }

  /**
   * Brings the disabled rules in line with the open requests, at start and after the grants
   * changed: a request whose rule is gone is stale, one that a grant covers is covered and its rule
   * runs again, the others keep their rule disabled.
   */
  void sync() throws StoreException {
    for (Approval a : store.approvals(EnumSet.of(Approval.State.PENDING, Approval.State.DENIED))) {
      if (!engine.hasRule(a.ruleId())) {
        store.answer(a.id(), Approval.State.STALE, clock.instant());
        continue;
      }
      String layer = access.layerOf(a.ruleId()).orElse(a.layer());
      if (access.policy().find(layer, a.capability(), a.args()).isPresent()) {
        store.answer(a.id(), Approval.State.COVERED, clock.instant());
        audit("covered #" + a.id(), a.ruleId(), Map.of("capability", a.capability()));
        engine.enable(a.ruleId());
      } else {
        engine.disable(
            a.ruleId(),
            a.state() == Approval.State.PENDING
                ? "waiting for the answer to request #" + a.id() + " (praksis approvals)"
                : "refused in request #" + a.id());
      }
    }
  }

  Response.ApprovalList list() throws StoreException {
    List<Response.ApprovalList.Item> items = new ArrayList<>();
    for (Approval a : store.approvals(EnumSet.allOf(Approval.State.class))) {
      items.add(
          new Response.ApprovalList.Item(
              a.id(),
              a.time().toString(),
              a.state().name(),
              a.ruleId(),
              a.layer(),
              a.capability(),
              a.args()));
    }
    return new Response.ApprovalList(
        items, store.missedCounts(), new TreeMap<>(engine.disabledRules()));
  }

  String answer(long id, String decision, String missed) throws Failure, StoreException {
    Approval a = store.approval(id).orElseThrow(() -> new Failure("there is no request #" + id));
    if (!a.state().disables()) {
      throw new Failure("request #" + id + " was answered already (" + a.state() + ")");
    }
    if (missed != null && !missed.equals("replay") && !missed.equals("discard")) {
      throw new Failure("what to do with the missed events must be 'replay' or 'discard'");
    }
    int count = store.missed(a.ruleId()).size();
    return switch (decision) {
      case "once" -> allowOnce(a, missed);
      case "always" -> allowAlways(a, missed, count);
      case "deny" -> deny(a, missed, count);
      default -> throw new Failure("the decision must be 'once', 'always' or 'deny'");
    };
  }

  private String allowOnce(Approval a, String missed) throws Failure, StoreException {
    if (missed != null) {
      throw new Failure(
          "allow once covers the one denied event only; do not say replay or discard");
    }
    Event event =
        store
            .event(a.eventId())
            .orElseThrow(() -> new Failure("the denied event " + a.eventId() + " is gone"));
    store.answer(a.id(), Approval.State.ALLOWED_ONCE, clock.instant());
    audit(
        "allowed once #" + a.id(),
        a.ruleId(),
        Map.of("capability", a.capability(), "args", a.args()));
    engine.enable(a.ruleId());
    engine.replay(a.ruleId(), List.of(event), true);
    return "Allowed once: the denied event is handled now. The rule is enabled; its next call"
        + " without a grant opens a new request.";
  }

  private String allowAlways(Approval a, String missed, int count) throws Failure, StoreException {
    if (missed == null) {
      throw new Failure(
          "allow always needs to know what to do with the "
              + count
              + " missed event(s): say replay or discard");
    }
    String layer = access.layerOf(a.ruleId()).orElse(a.layer());
    Map<String, ArgMatcher> matchers = new TreeMap<>();
    for (var arg : a.args().entrySet()) {
      Object v = arg.getValue();
      if (v instanceof String
          || v instanceof Boolean
          || v instanceof Long
          || v instanceof Integer) {
        matchers.put(arg.getKey(), new ArgMatcher.Equals(v));
      } else {
        throw new Failure(
            "cannot make a grant from argument '"
                + arg.getKey()
                + "'; add one to policy.toml by hand and use 'praksis grants reload'");
      }
    }
    Grant grant =
        new Grant(
            freshId(a.id()),
            layer,
            a.capability(),
            matchers,
            Optional.of("approved in request #" + a.id() + " for rule " + a.ruleId()));
    try {
      PolicyFile.append(config.policy(), grant);
    } catch (IOException | RulesetException e) {
      throw new Failure("cannot add the grant to policy.toml: " + e.getMessage(), e);
    }
    applyPolicy();
    store.answer(a.id(), Approval.State.ALLOWED_ALWAYS, clock.instant());
    Map<String, Object> described = new LinkedHashMap<>();
    matchers.forEach((k, m) -> described.put(k, m.describe()));
    store.audit(
        AuditEntry.of(
            clock.instant(),
            AuditEntry.Kind.GRANT,
            Optional.empty(),
            grant.id(),
            Map.of(
                "action",
                "added",
                "layer",
                layer,
                "capability",
                a.capability(),
                "args",
                described,
                "request",
                a.id())));
    audit("allowed always #" + a.id(), a.ruleId(), Map.of("grant", grant.id()));
    engine.enable(a.ruleId());
    String what = replayOrDiscard(a.ruleId(), missed);
    return "Granted '" + grant.id() + "' in policy.toml and enabled " + a.ruleId() + ". " + what;
  }

  private String deny(Approval a, String missed, int count) throws Failure, StoreException {
    store.answer(a.id(), Approval.State.DENIED, clock.instant());
    audit("denied #" + a.id(), a.ruleId(), Map.of("capability", a.capability(), "args", a.args()));
    engine.disable(a.ruleId(), "refused in request #" + a.id());
    if ("replay".equals(missed)) {
      throw new Failure("a refused rule cannot replay its missed events; say discard or leave it");
    }
    if ("discard".equals(missed)) {
      store.clearMissed(a.ruleId());
      return "Refused. "
          + a.ruleId()
          + " stays disabled; its "
          + count
          + " missed event(s) are discarded.";
    }
    return "Refused. "
        + a.ruleId()
        + " stays disabled; its "
        + count
        + " missed event(s) are kept.";
  }

  private String replayOrDiscard(String ruleId, String action) throws StoreException {
    List<Event> events = new ArrayList<>();
    for (String id : store.missed(ruleId)) {
      store.event(id).ifPresent(events::add);
    }
    if (action.equals("replay")) {
      engine.replay(ruleId, events, false);
      return events.size() + " missed event(s) are being replayed in order.";
    }
    int count = store.missed(ruleId).size();
    store.clearMissed(ruleId);
    return count + " missed event(s) discarded.";
  }

  String missed(String rule, String action) throws Failure, StoreException {
    if (!engine.hasRule(rule)) {
      throw new Failure("there is no rule '" + rule + "'");
    }
    if (engine.disabledRules().containsKey(rule)) {
      throw new Failure(rule + " is disabled; answer its request first (praksis approvals)");
    }
    if (!action.equals("replay") && !action.equals("discard")) {
      throw new Failure("the action must be 'replay' or 'discard'");
    }
    return replayOrDiscard(rule, action);
  }

  private String freshId(long request) {
    Set<String> used = new java.util.HashSet<>();
    access.policy().grants().forEach(g -> used.add(g.id()));
    String id = "approval-" + request;
    for (int n = 2; used.contains(id); n++) {
      id = "approval-" + request + "-" + n;
    }
    return id;
  }

  Response.GrantList grants() {
    List<Response.GrantList.Item> items = new ArrayList<>();
    for (Grant g : access.policy().grants()) {
      Map<String, String> args = new TreeMap<>();
      g.args().forEach((k, m) -> args.put(k, m.describe()));
      items.add(
          new Response.GrantList.Item(
              g.id(), g.layer(), g.capability(), args, g.note().orElse(null)));
    }
    List<Response.GrantList.Credential> credentials = new ArrayList<>();
    for (var b : access.policy().credentials()) {
      credentials.add(
          new Response.GrantList.Credential(
              b.name(), b.components(), b.command().get(0), b.note().orElse(null)));
    }
    return new Response.GrantList(items, credentials);
  }

  String revoke(String id) throws Failure, StoreException {
    boolean removed;
    try {
      removed = PolicyFile.remove(config.policy(), id);
    } catch (IOException | RulesetException e) {
      throw new Failure("cannot change policy.toml: " + e.getMessage(), e);
    }
    if (!removed) {
      throw new Failure("policy.toml has no grant '" + id + "'");
    }
    applyPolicy();
    store.audit(
        AuditEntry.of(
            clock.instant(),
            AuditEntry.Kind.GRANT,
            Optional.empty(),
            id,
            Map.of("action", "revoked")));
    return "Revoked '" + id + "'. Rules that relied on it are disabled by their next denied call.";
  }

  String reload() throws Failure, StoreException {
    applyPolicy();
    sync();
    return "policy.toml was read again: " + access.policy().grants().size() + " grant(s).";
  }

  /** Reads {@code policy.toml}, checks it, and uses it from now on. */
  private void applyPolicy() throws Failure, StoreException {
    try {
      Policy policy = Policy.load(config.policy());
      List<Problem> problems = policy.check(catalog, config.layerNames());
      if (!problems.isEmpty()) {
        throw new RulesetException(problems);
      }
      access.replace(policy);
    } catch (RulesetException e) {
      throw new Failure(
          "policy.toml is not valid, the old grants stay in use:\n  " + e.getMessage(), e);
    }
    try {
      Daemon.auditPolicy(config, store);
    } catch (DaemonException e) {
      throw new Failure(e.getMessage(), e);
    }
  }

  private void audit(String subject, String ruleId, Map<String, Object> detail)
      throws StoreException {
    store.audit(
        AuditEntry.of(
            clock.instant(), AuditEntry.Kind.APPROVAL, Optional.of(ruleId), subject, detail));
  }
}
