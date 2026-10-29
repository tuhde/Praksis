// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The encoding of messages: one JSON object per line, UTF-8. Unknown fields are errors, so a client
 * and a daemon that disagree about the protocol fail loudly.
 */
public final class Wire {
  /**
   * The version of the protocol: the requests and responses and what they mean. The daemon says it
   * in its status answer, so that a client can tell that it talks to a daemon it does not
   * understand. While Praksis is below 1.0.0 any change that an old client or daemon cannot cope
   * with raises it, and nothing promises compatibility between two versions.
   */
  public static final int PROTOCOL_VERSION = 1;

  /** The longest line accepted, to bound what one peer can make the other buffer. */
  public static final int MAX_LINE_BYTES = 1 << 20;

  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .defaultPropertyInclusion(
              JsonInclude.Value.construct(
                  JsonInclude.Include.NON_NULL, JsonInclude.Include.USE_DEFAULTS))
          .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private Wire() {}

  /** The JSON text of a request, without a line break. */
  public static String encode(Request request) throws ProtocolException {
    return write(request);
  }

  /** The JSON text of a response, without a line break. */
  public static String encode(Response response) throws ProtocolException {
    return write(response);
  }

  public static Request decodeRequest(String json) throws ProtocolException {
    return read(json, Request.class);
  }

  public static Response decodeResponse(String json) throws ProtocolException {
    return read(json, Response.class);
  }

  /** Reads a JSON object, e.g. the payload of an event typed on the command line. */
  public static Map<String, Object> parseObject(String json) throws ProtocolException {
    try {
      return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new ProtocolException("not a JSON object: " + e.getOriginalMessage(), e);
    }
  }

  /**
   * Reads one line.
   *
   * @return the line without its line break, or null at the end of the stream
   * @throws ProtocolException if the line is longer than {@link #MAX_LINE_BYTES}
   */
  public static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) >= 0) {
      if (b == '\n') {
        return line.toString(StandardCharsets.UTF_8);
      }
      if (line.size() >= MAX_LINE_BYTES) {
        throw new ProtocolException("message longer than " + MAX_LINE_BYTES + " bytes");
      }
      line.write(b);
    }
    return line.size() == 0 ? null : line.toString(StandardCharsets.UTF_8);
  }

  private static String write(Object message) throws ProtocolException {
    try {
      return MAPPER.writeValueAsString(message);
    } catch (JsonProcessingException e) {
      throw new ProtocolException("cannot encode message: " + e.getOriginalMessage(), e);
    }
  }

  private static <T> T read(String json, Class<T> type) throws ProtocolException {
    try {
      return MAPPER.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new ProtocolException("invalid message: " + e.getOriginalMessage(), e);
    }
  }
}
