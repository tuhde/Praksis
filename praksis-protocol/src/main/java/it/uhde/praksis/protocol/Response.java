// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;
import java.util.Map;

/** What the daemon answers. On the wire it is one JSON object with a {@code type} field. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Response.StatusReport.class, name = "status"),
  @JsonSubTypes.Type(value = Response.Injected.class, name = "injected"),
  @JsonSubTypes.Type(value = Response.LogLines.class, name = "log"),
  @JsonSubTypes.Type(value = Response.AuditLines.class, name = "audit"),
  @JsonSubTypes.Type(value = Response.ApprovalList.class, name = "approvals"),
  @JsonSubTypes.Type(value = Response.GrantList.class, name = "grants"),
  @JsonSubTypes.Type(value = Response.ShadowList.class, name = "shadow"),
  @JsonSubTypes.Type(value = Response.Done.class, name = "done"),
  @JsonSubTypes.Type(value = Response.Stopped.class, name = "stopped"),
  @JsonSubTypes.Type(value = Response.Error.class, name = "error")
})
public sealed interface Response {
  /**
   * What was done, in a sentence for a person.
   *
   * @param message the sentence
   */
  record Done(String message) implements Response {}

  /**
   * The requests for permissions and the events that rules missed.
   *
   * @param approvals the requests, oldest first, with the answered ones
   * @param missed how many events each rule missed, for rules that missed any
   * @param disabled the rules that are disabled, by rule id, with the reason
   */
  record ApprovalList(
      List<Item> approvals, Map<String, Integer> missed, Map<String, String> disabled)
      implements Response {
    /**
     * One request.
     *
     * @param id the number to answer with
     * @param time when it was opened, ISO-8601
     * @param state {@code PENDING}, {@code DENIED}, {@code ALLOWED_ONCE}, {@code ALLOWED_ALWAYS},
     *     {@code COVERED} or {@code STALE}
     * @param ruleId the disabled rule
     * @param layer the layer that owns it
     * @param capability the capability it needs
     * @param args the arguments of the refused call that a grant could restrict
     */
    public record Item(
        long id,
        String time,
        String state,
        String ruleId,
        String layer,
        String capability,
        Map<String, Object> args) {}
  }

  /**
   * The grants and credential bindings in effect.
   *
   * @param grants the grants
   * @param credentials the credential bindings; never the secrets
   */
  record GrantList(List<Item> grants, List<Credential> credentials) implements Response {
    /**
     * One credential binding.
     *
     * @param name the name modules ask for
     * @param components the capabilities and event sources that may ask
     * @param executable the program that prints the secret, without its arguments
     * @param note what it is for, or null
     */
    public record Credential(
        String name, List<String> components, String executable, String note) {}

    /**
     * One grant.
     *
     * @param id the grant id
     * @param layer the layer it is for
     * @param capability the capability
     * @param args the restriction per argument, as written in {@code policy.toml}
     * @param note why it exists, or null
     */
    public record Item(
        String id, String layer, String capability, Map<String, String> args, String note) {}
  }

  /**
   * The latest lines of the audit log, newest first.
   *
   * @param lines the lines
   */
  record AuditLines(List<Line> lines) implements Response {
    /**
     * One audited step.
     *
     * @param time when it was decided, ISO-8601
     * @param kind {@code CALL}, {@code GRANT} or {@code POLICY}
     * @param ruleId the rule that made a call, or null
     * @param subject the capability, grant id or file
     * @param allowed whether a call was let through
     * @param detail JSON: the arguments of a call or what changed
     * @param error what went wrong, or null
     */
    public record Line(
        String time,
        String kind,
        String ruleId,
        String subject,
        boolean allowed,
        String detail,
        String error) {}
  }

  /**
   * How the daemon is doing.
   *
   * @param pid the process id of the daemon
   * @param startedAt when the daemon started, ISO-8601
   * @param ruleset the name of the loaded ruleset
   * @param rules the number of rules in it
   * @param modules the names of the loaded modules
   * @param pending the number of events stored but not yet handled
   */
  record StatusReport(
      long pid, String startedAt, String ruleset, int rules, List<String> modules, int pending)
      implements Response {}

  /**
   * An event was handed over.
   *
   * @param id the id given to the event
   * @param accepted false if an event with the same key was seen before and this one was dropped
   */
  record Injected(String id, boolean accepted) implements Response {}

  /**
   * Lines of the run log, newest first.
   *
   * @param lines the lines
   */
  record LogLines(List<Line> lines) implements Response {
    /**
     * One step of handling an event.
     *
     * @param time when it happened, ISO-8601
     * @param eventId the event
     * @param ruleId the rule, or null for a line about the event as a whole
     * @param actionId the action, or null
     * @param outcome {@code OK}, {@code FAILED} or {@code SKIPPED}
     * @param message a short description
     */
    public record Line(
        String time,
        String eventId,
        String ruleId,
        String actionId,
        String outcome,
        String message) {}
  }

  /**
   * The rules on trial.
   *
   * @param enabled whether shadow mode is turned on in {@code praksis.toml}
   * @param rules the rules on trial
   */
  record ShadowList(boolean enabled, List<Item> rules) implements Response {
    /**
     * A rule on trial.
     *
     * @param rule the rule id
     * @param hash the version on trial
     * @param since when the trial started, ISO-8601
     * @param replaces whether an older version runs live meanwhile
     * @param events the events it was seen with
     * @param calling how many of them it would have made calls for
     * @param denied how many would have had a call that no grant covers
     * @param failing how many it would have failed on
     * @param ready whether the trial period and the number of events are met
     * @param status what is still missing, or that it is ready
     * @param samples the latest events
     */
    public record Item(
        String rule,
        String hash,
        String since,
        boolean replaces,
        int events,
        int calling,
        int denied,
        int failing,
        boolean ready,
        String status,
        List<Sample> samples) {}

    /**
     * One observed event.
     *
     * @param event the event id
     * @param calls what the rule would have called, one line each
     * @param note a failure or {@code stop}, or empty
     * @param live the rules that really handled the event, with their outcome
     */
    public record Sample(String event, List<String> calls, String note, List<String> live) {}
  }

  /** The daemon is shutting down. */
  record Stopped() implements Response {}

  /**
   * The request could not be done.
   *
   * @param message why, possibly several lines
   */
  record Error(String message) implements Response {}
}
