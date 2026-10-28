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
   * Asks the daemon to finish what it is doing and exit. Answered with {@link Response.Stopped}.
   */
  record Stop() implements Request {}
}
