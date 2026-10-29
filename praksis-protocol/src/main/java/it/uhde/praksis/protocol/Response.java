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
  @JsonSubTypes.Type(value = Response.ChangeList.class, name = "changes"),
  @JsonSubTypes.Type(value = Response.EffectiveView.class, name = "effective"),
  @JsonSubTypes.Type(value = Response.LayerList.class, name = "layers"),
  @JsonSubTypes.Type(value = Response.Lines.class, name = "lines"),
  @JsonSubTypes.Type(value = Response.Verification.class, name = "verification"),
  @JsonSubTypes.Type(value = Response.ProposalList.class, name = "proposals"),
  @JsonSubTypes.Type(value = Response.ProposalDetail.class, name = "proposal"),
  @JsonSubTypes.Type(value = Response.CommandList.class, name = "commands"),
  @JsonSubTypes.Type(value = Response.CommandStarted.class, name = "command-started"),
  @JsonSubTypes.Type(value = Response.Outcome.class, name = "outcome"),
  @JsonSubTypes.Type(value = Response.ViewList.class, name = "views"),
  @JsonSubTypes.Type(value = Response.ResolvedView.class, name = "view"),
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

  /**
   * What changed.
   *
   * @param counter to send with the next {@link Request.Changes}
   * @param topics the topics that changed since the counter of the request: {@code events}, {@code
   *     log}, {@code audit}, {@code approvals} and {@code shadow}
   */
  record ChangeList(long counter, List<String> topics) implements Response {}

  /**
   * The ruleset in use.
   *
   * @param ruleset its name
   * @param pinsChanged whether the pins now give another ruleset (a restart would use it)
   * @param params the parameters and their values
   * @param rules the rules in firing order
   * @param disabled the rules that a layer turned off
   */
  record EffectiveView(
      String ruleset,
      boolean pinsChanged,
      List<Param> params,
      List<Rule> rules,
      List<Disabled> disabled)
      implements Response {
    /** A parameter. */
    public record Param(String name, String type, String value) {}

    /**
     * A rule and where it comes from.
     *
     * @param id the rule id
     * @param layer the layer that added it
     * @param owner the layer whose grants decide
     * @param hash its content hash, for {@code based_on}
     * @param history what each layer did to it
     * @param author {@code human}, {@code ai}, {@code unverified} or {@code unknown}
     * @param who one line: who changed it last, in which commit, and the subject
     * @param reasoning why, from the commit message
     * @param onTrial whether a version of it is on trial in shadow mode
     * @param trigger what makes it fire: {@code event <type>} or {@code command <title>}
     */
    public record Rule(
        String id,
        String layer,
        String owner,
        String hash,
        List<String> history,
        String author,
        String who,
        String reasoning,
        boolean onTrial,
        String trigger) {}

    /** A rule that a layer turned off. */
    public record Disabled(String rule, String layer, String reason) {}
  }

  /**
   * The ruleset layers.
   *
   * @param layers bottom to top
   */
  record LayerList(List<Layer> layers) implements Response {
    /**
     * A layer.
     *
     * @param name the layer name
     * @param source the repository and ref, or the directory
     * @param pinned the commit it is pinned to, or null
     * @param stale whether the configuration differs from the pin
     * @param present whether the files of the pinned commit are on disk
     * @param aiMaintained whether an AI may propose changes to it
     */
    public record Layer(
        String name,
        String source,
        String pinned,
        boolean stale,
        boolean present,
        boolean aiMaintained) {}
  }

  /**
   * The report of an operation, as text.
   *
   * @param ok whether it was done
   * @param lines what it says
   */
  record Lines(boolean ok, List<String> lines) implements Response {}

  /**
   * The result of the checks on a ruleset or a candidate.
   *
   * @param candidate whether it is a candidate and not the ruleset in use
   * @param ruleset its name
   * @param rules how many rules it has
   * @param errors the number of errors
   * @param warnings the number of warnings
   * @param findings what was found, errors first
   * @param tier for a candidate: {@code APPROVAL}, {@code SHADOW} or {@code AUTOMATIC}, else null
   * @param decisions for a candidate: one per rule that is new, changed or removed
   * @param replay the replay, or null
   */
  record Verification(
      boolean candidate,
      String ruleset,
      int rules,
      long errors,
      long warnings,
      List<Finding> findings,
      String tier,
      List<Decision> decisions,
      Replay replay)
      implements Response {
    /** One thing the checks found. */
    public record Finding(String severity, String check, String where, String message) {}

    /** What a change needs for one rule. */
    public record Decision(String rule, String change, String tier, List<String> reasons) {}

    /**
     * The replay of stored events.
     *
     * @param events how many were replayed
     * @param differences the events that are treated differently, at most 20
     * @param more how many more there are
     */
    public record Replay(int events, List<Difference> differences, int more) {}

    /** An event that the two rulesets treat differently. */
    public record Difference(String event, String type, List<String> now, List<String> candidate) {}
  }

  /**
   * The branches of AI-maintained layers that are not merged or decided.
   *
   * @param proposals the proposals
   * @param note a hint if there are none because no layer is AI-maintained, else null
   */
  record ProposalList(List<Proposal> proposals, String note) implements Response {
    /**
     * A proposal.
     *
     * @param name {@code <layer>@<branch>}
     * @param layer the layer
     * @param branch the branch
     * @param commit the commit the branch leads to
     * @param commits the commits that the layer does not have, newest first
     */
    public record Proposal(
        String name, String layer, String branch, String commit, List<Commit> commits) {}

    /**
     * A commit of a proposal.
     *
     * @param commit the commit id
     * @param author the author's name
     * @param authorKind {@code human}, {@code ai}, {@code unverified}
     * @param signed whether git found the signature good
     * @param signer the signing key, or empty
     * @param subject the first line of the message
     * @param reasoning the rest of the message without trailers
     */
    public record Commit(
        String commit,
        String author,
        String authorKind,
        boolean signed,
        String signer,
        String subject,
        String reasoning) {}
  }

  /**
   * One proposal with the checks on it.
   *
   * @param proposal the proposal
   * @param verification the checks, the tier and the replay
   */
  record ProposalDetail(ProposalList.Proposal proposal, Verification verification)
      implements Response {}

  /**
   * The commands that a person can start.
   *
   * @param commands the commands, by rule id
   */
  record CommandList(List<Command> commands) implements Response {
    /**
     * A command.
     *
     * @param id the rule id, which starts it
     * @param title what a person sees
     * @param description what it does, or null
     * @param owner the layer whose grants decide what it may do
     * @param effect the strongest effect class of its actions
     * @param disabled why it cannot run now (a call was denied), or null
     * @param params the arguments, from which a form can be made
     */
    public record Command(
        String id,
        String title,
        String description,
        String owner,
        String effect,
        String disabled,
        List<Param> params) {}

    /**
     * An argument of a command.
     *
     * @param name the name
     * @param type {@code string}, {@code int}, {@code double}, {@code bool}, {@code timestamp},
     *     {@code bytes}, {@code list} or {@code map}
     * @param required whether it must be given
     * @param defaultValue the default as text, or null
     * @param label what to show next to the field, or null
     */
    public record Param(
        String name, String type, boolean required, String defaultValue, String label) {}
  }

  /**
   * A command was started.
   *
   * @param event the id of the event that runs it; ask {@link Request.Outcome} for the result
   */
  record CommandStarted(String event) implements Response {}

  /**
   * What became of an event.
   *
   * @param done whether it has been handled
   * @param lines the run log of the event, oldest first
   */
  record Outcome(boolean done, List<LogLines.Line> lines) implements Response {}

  /**
   * The views of the ruleset in use.
   *
   * @param views the views, by id
   */
  record ViewList(List<ViewInfo> views) implements Response {
    /**
     * A view.
     *
     * @param id the id
     * @param title what a person sees
     * @param description what it is for, or null
     * @param schema the UI schema it is written in
     * @param owner the layer whose grants decide what its sources may read
     * @param params its state, with defaults
     */
    public record ViewInfo(
        String id,
        String title,
        String description,
        int schema,
        String owner,
        List<Param> params) {}

    /** A parameter of a view. */
    public record Param(String name, String type, Object value) {}
  }

  /**
   * A view with its data.
   *
   * @param id the view id
   * @param title what a person sees
   * @param schema the UI schema of the description
   * @param params the state of the view as used
   * @param components what to show, top to bottom
   * @param warnings what went wrong in the sources (a source that could not be read, a call that no
   *     grant covers); the components that need them say so themselves
   */
  record ResolvedView(
      String id,
      String title,
      int schema,
      List<ViewList.Param> params,
      List<ViewComponent> components,
      List<String> warnings)
      implements Response {}

  /**
   * One part of a resolved view. Which fields are set depends on {@code type}: {@code text} ({@code
   * text}, {@code style}), {@code table} ({@code columns}, {@code rows}, {@code empty}), {@code
   * list} (rows of two cells: title and subtitle), {@code detail} ({@code fields}), {@code button}
   * ({@code label}, {@code enabled}, {@code actions}), {@code form} ({@code form}), {@code row} and
   * {@code column} ({@code children}). A part that could not be worked out has {@code error}
   * instead.
   *
   * @param type the component type
   * @param text the text of a text
   * @param style {@code normal}, {@code title}, {@code muted} or {@code code}
   * @param label the label of a button
   * @param enabled whether a button can be used
   * @param empty what to show when there are no rows
   * @param columns the columns of a table
   * @param rows the rows of a table or list
   * @param fields the fields of a detail
   * @param actions what can be started
   * @param form the form of a command
   * @param children the parts of a container
   * @param error why this part is not shown, or null
   */
  record ViewComponent(
      String type,
      String text,
      String style,
      String label,
      Boolean enabled,
      String empty,
      List<Column> columns,
      List<Row> rows,
      List<Field> fields,
      List<Action> actions,
      Form form,
      List<ViewComponent> children,
      String error) {
    /** A column of a table. {@code format}: text, datetime, date, number or bytes. */
    public record Column(String title, String format) {}

    /**
     * A row.
     *
     * @param cells the values, in the order of the columns
     * @param select what a click sets: the view parameter and its value, or null
     * @param actions what can be started for this row
     */
    public record Row(List<Object> cells, Select select, List<Action> actions) {}

    /** Clicking sets the view parameter to the value and asks for the view again. */
    public record Select(String param, Object value) {}

    /** A labelled value of a detail. */
    public record Field(String label, String format, Object value) {}

    /**
     * A command that a person can start.
     *
     * @param label what the button says
     * @param command the command id
     * @param args the arguments, with values
     * @param confirm a question to ask first, or null
     */
    public record Action(
        String label, String command, java.util.Map<String, Object> args, String confirm) {}

    /**
     * A form for a command.
     *
     * @param command the command id
     * @param title the title of the command
     * @param submit what the button says
     * @param fields the arguments with the given defaults
     */
    public record Form(String command, String title, String submit, List<FormField> fields) {}

    /** An argument of a form. */
    public record FormField(
        String name, String type, boolean required, String label, Object value) {}
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
