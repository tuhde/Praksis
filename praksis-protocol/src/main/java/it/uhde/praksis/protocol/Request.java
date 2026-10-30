// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.Map;

/** What a client asks the daemon. On the wire it is one JSON object with an {@code op} field. */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "op")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Request.Status.class, name = "status"),
  @JsonSubTypes.Type(value = Request.Inject.class, name = "inject"),
  @JsonSubTypes.Type(value = Request.Log.class, name = "log"),
  @JsonSubTypes.Type(value = Request.Audit.class, name = "audit"),
  @JsonSubTypes.Type(value = Request.Approvals.class, name = "approvals"),
  @JsonSubTypes.Type(value = Request.Answer.class, name = "answer"),
  @JsonSubTypes.Type(value = Request.Missed.class, name = "missed"),
  @JsonSubTypes.Type(value = Request.Grants.class, name = "grants"),
  @JsonSubTypes.Type(value = Request.Revoke.class, name = "revoke"),
  @JsonSubTypes.Type(value = Request.Reload.class, name = "reload"),
  @JsonSubTypes.Type(value = Request.Shadow.class, name = "shadow"),
  @JsonSubTypes.Type(value = Request.ShadowPromote.class, name = "shadow-promote"),
  @JsonSubTypes.Type(value = Request.ShadowReset.class, name = "shadow-reset"),
  @JsonSubTypes.Type(value = Request.Changes.class, name = "changes"),
  @JsonSubTypes.Type(value = Request.Effective.class, name = "effective"),
  @JsonSubTypes.Type(value = Request.Layers.class, name = "layers"),
  @JsonSubTypes.Type(value = Request.LayersUpdate.class, name = "layers-update"),
  @JsonSubTypes.Type(value = Request.Verify.class, name = "verify"),
  @JsonSubTypes.Type(value = Request.Proposals.class, name = "proposals"),
  @JsonSubTypes.Type(value = Request.ProposalShow.class, name = "proposal-show"),
  @JsonSubTypes.Type(value = Request.ProposalApprove.class, name = "proposal-approve"),
  @JsonSubTypes.Type(value = Request.ProposalReject.class, name = "proposal-reject"),
  @JsonSubTypes.Type(value = Request.Commands.class, name = "commands"),
  @JsonSubTypes.Type(value = Request.RunCommand.class, name = "run-command"),
  @JsonSubTypes.Type(value = Request.Outcome.class, name = "outcome"),
  @JsonSubTypes.Type(value = Request.Views.class, name = "views"),
  @JsonSubTypes.Type(value = Request.ViewResolve.class, name = "view"),
  @JsonSubTypes.Type(value = Request.Fetch.class, name = "fetch"),
  @JsonSubTypes.Type(value = Request.Upload.class, name = "upload"),
  @JsonSubTypes.Type(value = Request.Lookup.class, name = "lookup"),
  @JsonSubTypes.Type(value = Request.Dictate.class, name = "dictate"),
  @JsonSubTypes.Type(value = Request.Stop.class, name = "stop")
})
public sealed interface Request {
  /** Asks how the daemon is doing. Answered with {@link Response.StatusReport}. */
  record Status() implements Request {}

  /**
   * Hands the daemon an event, as if a source had produced it. Answered with {@link
   * Response.Injected}.
   *
   * @param type the event type, e.g. {@code mail.received}
   * @param target what the event is about; the daemon uses {@code cli} if this is null
   * @param key the idempotency key, or null for none
   * @param data the payload; timestamps are ISO-8601 strings and bytes are Base64 strings
   */
  record Inject(String type, String target, String key, Map<String, Object> data)
      implements Request {
    public Inject {
      data = data == null ? Map.of() : data;
    }
  }

  /**
   * Asks for the latest lines of the run log. Answered with {@link Response.LogLines}.
   *
   * @param limit the maximum number of lines, newest first
   */
  record Log(int limit) implements Request {}

  /**
   * Asks for the latest lines of the audit log. Answered with {@link Response.AuditLines}.
   *
   * @param limit the maximum number of lines, newest first
   */
  record Audit(int limit) implements Request {}

  /** Asks for the requests for permissions. Answered with {@link Response.ApprovalList}. */
  record Approvals() implements Request {}

  /**
   * Answers a request for a permission. Answered with {@link Response.Done}.
   *
   * @param id the number of the request
   * @param decision {@code once}, {@code always} or {@code deny}
   * @param missed what to do with the events the rule missed: {@code replay} or {@code discard};
   *     required for {@code always}, optional for {@code deny} (default: keep them), not used for
   *     {@code once}
   */
  record Answer(long id, String decision, String missed) implements Request {}

  /**
   * Replays or discards the events a rule missed. Answered with {@link Response.Done}.
   *
   * @param rule the rule id
   * @param action {@code replay} or {@code discard}
   */
  record Missed(String rule, String action) implements Request {}

  /** Asks for the grants in effect. Answered with {@link Response.GrantList}. */
  record Grants() implements Request {}

  /**
   * Removes a grant from {@code policy.toml}. Answered with {@link Response.Done}.
   *
   * @param id the grant id
   */
  record Revoke(String id) implements Request {}

  /**
   * Reads {@code policy.toml} again, e.g. after a person edited it. Answered with {@link
   * Response.Done}.
   */
  record Reload() implements Request {}

  /**
   * Asks for the rules on trial and what they would have done. Answered with {@link
   * Response.ShadowList}.
   */
  record Shadow() implements Request {}

  /**
   * Ends the trial of a rule: it runs live from now on. Answered with {@link Response.Done}.
   *
   * @param rule the rule id
   */
  record ShadowPromote(String rule) implements Request {}

  /**
   * Forgets what a rule on trial has been seen to do and starts the trial again. Answered with
   * {@link Response.Done}.
   *
   * @param rule the rule id
   */
  record ShadowReset(String rule) implements Request {}

  /**
   * Asks what has changed since a counter, and waits for a change if nothing has. A client that
   * keeps its screens up to date sends the counter of the last answer in a loop; the first request
   * uses 0 and gets every topic. Answered with {@link Response.ChangeList}.
   *
   * @param since the counter of the last answer
   * @param waitMillis how long to wait for a change if there is none yet (at most 30 seconds)
   */
  record Changes(long since, int waitMillis) implements Request {}

  /**
   * Asks for the ruleset in use with where each rule comes from. Answered with {@link
   * Response.EffectiveView}.
   */
  record Effective() implements Request {}

  /** Asks for the layers and what they are pinned to. Answered with {@link Response.LayerList}. */
  record Layers() implements Request {}

  /**
   * Moves the git layers to the latest commit, as {@code praksis layers update}. Answered with
   * {@link Response.Lines}; the daemon uses the new pins after a restart.
   *
   * @param dryRun whether to only report
   * @param acceptNewAccess whether to go on although rules get access that no grant covers
   * @param names the layers to move; empty means all
   */
  record LayersUpdate(boolean dryRun, boolean acceptNewAccess, java.util.List<String> names)
      implements Request {
    public LayersUpdate {
      names = names == null ? java.util.List.of() : names;
    }
  }

  /**
   * Runs the checks on the ruleset in use or on a candidate, as {@code praksis verify}. Answered
   * with {@link Response.Verification}.
   *
   * @param replay whether to replay stored events through the candidate
   * @param count how many events to replay
   * @param update whether the candidate has the layers moved to their latest commits
   * @param replacements layer name to a directory, or to {@code @<ref>}, for the candidate
   */
  record Verify(
      boolean replay, int count, boolean update, java.util.Map<String, String> replacements)
      implements Request {
    public Verify {
      replacements = replacements == null ? java.util.Map.of() : replacements;
    }
  }

  /**
   * Asks for the branches of AI-maintained layers that are not decided. Answered with {@link
   * Response.ProposalList}.
   */
  record Proposals() implements Request {}

  /**
   * Asks for one proposal with the checks on it. Answered with {@link Response.ProposalDetail}.
   *
   * @param name {@code <layer>@<branch>}
   */
  record ProposalShow(String name) implements Request {}

  /**
   * Approves a proposal at the commit that was looked at. Answered with {@link Response.Lines}.
   *
   * @param name {@code <layer>@<branch>}
   * @param commit the commit that was looked at (7 characters at least)
   * @param withoutShadow whether to approve although shadow mode is off
   */
  record ProposalApprove(String name, String commit, boolean withoutShadow) implements Request {}

  /**
   * Rejects a proposal. Answered with {@link Response.Lines}.
   *
   * @param name {@code <layer>@<branch>}
   * @param reason why, for the audit log
   */
  record ProposalReject(String name, String reason) implements Request {}

  /** Asks for the commands that a person can start. Answered with {@link Response.CommandList}. */
  record Commands() implements Request {}

  /**
   * Starts a command. The arguments are checked against the declared parameters (defaults fill what
   * is missing; timestamps are ISO-8601 strings and bytes Base64 strings, as in {@link Inject}).
   * Answered with {@link Response.CommandStarted}, or an error that says what is wrong. The start
   * is an audit line with the client name.
   *
   * @param command the rule id of the command
   * @param args the arguments by parameter name
   * @param client who asks, e.g. {@code praksis-desktop}, for the audit log; may be null
   */
  record RunCommand(String command, java.util.Map<String, Object> args, String client)
      implements Request {
    public RunCommand {
      args = args == null ? java.util.Map.of() : args;
    }
  }

  /**
   * Asks what became of an event, e.g. of a command that was started. Answered with {@link
   * Response.Outcome}.
   *
   * @param event the event id
   */
  record Outcome(String event) implements Request {}

  /** Asks for the views of the ruleset in use. Answered with {@link Response.ViewList}. */
  record Views() implements Request {}

  /**
   * Asks for a view with its data. The daemon runs the sources of the view under the grants of the
   * layer that owns it, evaluates the expressions and answers with a description that contains only
   * values. Answered with {@link Response.ResolvedView}, or with an error, e.g. if the view needs a
   * newer UI schema than the client understands.
   *
   * @param view the view id
   * @param params the state of the view, by parameter name; missing ones have their default
   * @param maxSchema the newest UI schema that the client understands; 0 means any
   */
  record ViewResolve(String view, java.util.Map<String, Object> params, int maxSchema)
      implements Request {
    public ViewResolve {
      params = params == null ? java.util.Map.of() : params;
    }
  }

  /**
   * Asks for a place to put a file that a person chose to attach to a mail. Answered with {@link
   * Response.Uploaded}: an empty file that only this user can read, and its token. The client
   * copies the file into it and gives the token (never the path) to the command; a module reads
   * exactly the files whose tokens it is given. What waits longer than a day is removed.
   */
  record Upload() implements Request {}

  /**
   * Asks for suggestions for what a person is typing into a field of a form of a view, such as the
   * addresses that match the start of a name. The view must have a lookup with this capability; the
   * daemon reads it under the grants of the layer that owns the view. Answered with {@link
   * Response.Suggestions} (at most 20), or with an error.
   *
   * @param view the view id
   * @param capability the capability of the lookup
   * @param args its arguments, as the description of the view gave them plus the typed text
   */
  record Lookup(String view, String capability, java.util.Map<String, Object> args)
      implements Request {
    public Lookup {
      args = args == null ? java.util.Map.of() : args;
    }
  }

  /**
   * Listens for what a person says into the microphone button of an input of a view. The view must
   * have an input with a microphone that names this capability; the daemon reads it with the
   * arguments that the view gives (never the ones the caller sends), under the grants of the layer
   * that owns the view, and audits the call. Answered with {@link Response.Dictated}, or with an
   * error.
   *
   * @param view the view id
   * @param capability the capability of the microphone
   */
  record Dictate(String view, String capability) implements Request {}

  /**
   * Fetches a file that a view offers for download, because a person asked for it. The daemon
   * checks that the view has such a download, reads the capability under the grants of the layer
   * that owns the view (the call is audited) and answers with {@link Response.Fetched}, or with an
   * error if there is no such download, the grants do not cover the call or the capability fails.
   *
   * @param view the view id
   * @param capability the capability of the download
   * @param args its arguments, as the description of the view gave them
   * @param filename the name to suggest
   */
  record Fetch(String view, String capability, java.util.Map<String, Object> args, String filename)
      implements Request {
    public Fetch {
      args = args == null ? java.util.Map.of() : args;
    }
  }

  /**
   * Asks the daemon to finish what it is doing and exit. Answered with {@link Response.Stopped}.
   */
  record Stop() implements Request {}
}
