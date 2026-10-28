// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WireTest {
  @TempDir Path dir;

  @Test
  void requestsSurviveTheRoundTrip() throws Exception {
    for (Request r :
        List.of(
            new Request.Status(),
            new Request.Stop(),
            new Request.Log(5),
            new Request.Inject("mail.received", "t", "k", Map.of("size", 3, "s", "x")),
            new Request.Inject("mail.received", null, null, null))) {
      String json = Wire.encode(r);
      assertThat(json).doesNotContain("\n");
      Request back = Wire.decodeRequest(json);
      assertThat(back).isEqualTo(r);
    }
    assertThat(Wire.encode(new Request.Status())).isEqualTo("{\"op\":\"status\"}");
    assertThat(Wire.encode(new Request.Inject("t", null, null, null)))
        .isEqualTo("{\"op\":\"inject\",\"type\":\"t\",\"data\":{}}");
  }

  @Test
  void responsesSurviveTheRoundTrip() throws Exception {
    for (Response r :
        List.of(
            new Response.StatusReport(1, "2026-01-01T00:00:00Z", "mail", 2, List.of("maildir"), 0),
            new Response.Injected("id", true),
            new Response.Stopped(),
            new Response.Error("bad\nthing"),
            new Response.LogLines(
                List.of(
                    new Response.LogLines.Line("t", "e", null, null, "OK", "m"),
                    new Response.LogLines.Line("t", "e", "r", "a", "FAILED", "m"))))) {
      assertThat(Wire.decodeResponse(Wire.encode(r))).isEqualTo(r);
    }
  }

  @Test
  void rejectsWhatItDoesNotUnderstand() {
    for (String bad :
        new String[] {
          "",
          "not json",
          "{}",
          "{\"op\":\"nope\"}",
          "{\"op\":\"status\",\"extra\":1}",
          "{\"op\":\"status\"} {\"op\":\"stop\"}"
        }) {
      assertThatThrownBy(() -> Wire.decodeRequest(bad))
          .as(bad)
          .isInstanceOf(ProtocolException.class)
          .hasMessageStartingWith("invalid message");
    }
  }

  @Test
  void parsesJsonObjects() throws Exception {
    assertThat(Wire.parseObject("{\"a\":1,\"b\":[true],\"c\":{\"d\":\"x\"}}"))
        .containsEntry("a", 1)
        .containsEntry("b", List.of(true))
        .containsEntry("c", Map.of("d", "x"));
    for (String bad : new String[] {"[1]", "nope", "{\"a\":1} x", ""}) {
      assertThatThrownBy(() -> Wire.parseObject(bad)).as(bad).isInstanceOf(ProtocolException.class);
    }
  }

  @Test
  void readsLinesAndBoundsTheirLength() throws Exception {
    var in = new ByteArrayInputStream("a\nüb\nlast".getBytes(StandardCharsets.UTF_8));
    assertThat(Wire.readLine(in)).isEqualTo("a");
    assertThat(Wire.readLine(in)).isEqualTo("üb");
    assertThat(Wire.readLine(in)).isEqualTo("last");
    assertThat(Wire.readLine(in)).isNull();
    var tooLong = new ByteArrayInputStream(new byte[Wire.MAX_LINE_BYTES + 1]);
    assertThatThrownBy(() -> Wire.readLine(tooLong)).isInstanceOf(ProtocolException.class);
  }

  @Test
  void aClientTalksToAServerOverAUnixSocket() throws Exception {
    Path socket = dir.resolve("s.sock");
    try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
      server.bind(UnixDomainSocketAddress.of(socket));
      Thread t =
          Thread.ofVirtual()
              .start(
                  () -> {
                    try (SocketChannel c = server.accept()) {
                      var in = Channels.newInputStream(c);
                      var out = Channels.newOutputStream(c);
                      Request r = Wire.decodeRequest(Wire.readLine(in));
                      Response answer =
                          r instanceof Request.Log log
                              ? new Response.Error("limit " + log.limit())
                              : new Response.Stopped();
                      out.write((Wire.encode(answer) + "\n").getBytes(StandardCharsets.UTF_8));
                    } catch (IOException e) {
                      throw new AssertionError(e);
                    }
                  });
      try (Client client = Client.connect(socket)) {
        assertThat(client.send(new Request.Log(7))).isEqualTo(new Response.Error("limit 7"));
        // the server hung up after one answer
        assertThatThrownBy(() -> client.send(new Request.Status())).isInstanceOf(IOException.class);
      }
      t.join();
    }
    assertThatThrownBy(() -> Client.connect(dir.resolve("none.sock")))
        .isInstanceOf(IOException.class);
  }
}
