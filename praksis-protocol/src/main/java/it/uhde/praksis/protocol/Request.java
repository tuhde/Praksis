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
   * Asks the daemon to finish what it is doing and exit. Answered with {@link Response.Stopped}.
   */
  record Stop() implements Request {}
}
