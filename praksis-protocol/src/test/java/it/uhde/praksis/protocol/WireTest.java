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
  void aStatusSaysWhichProtocolTheDaemonSpeaksAndAnOldOneSaysNothing() throws Exception {
    var status = new Response.StatusReport(1, "t", "mail", 2, List.of(), 0);
    assertThat(status.protocol()).isEqualTo(Wire.PROTOCOL_VERSION);
    assertThat(Wire.encode(status)).contains("\"protocol\":" + Wire.PROTOCOL_VERSION);
    var old =
        Wire.decodeResponse(
            "{\"type\":\"status\",\"pid\":1,\"startedAt\":\"t\",\"ruleset\":\"m\",\"rules\":0,"
                + "\"modules\":[],\"pending\":0}");
    assertThat(((Response.StatusReport) old).protocol()).isZero();
  }

  @Test
  void theMessagesOfTheScreensSurviveTheRoundTrip() throws Exception {
    for (Request r :
        List.of(
            new Request.Changes(7, 1000),
            new Request.Effective(),
            new Request.Layers(),
            new Request.LayersUpdate(true, false, List.of("mine")),
            new Request.LayersUpdate(false, true, null),
            new Request.Verify(true, 50, false, Map.of("mine", "@ai-change")),
            new Request.Verify(false, 0, true, null),
            new Request.Proposals(),
            new Request.ProposalShow("mine@ai-change"),
            new Request.ProposalApprove("mine@ai-change", "0123456", true),
            new Request.ProposalReject("mine@ai-change", "no"),
            new Request.Commands(),
            new Request.RunCommand(
                "mail.file", Map.of("folder", "Archive", "n", 3), "praksis-desktop"),
            new Request.RunCommand("mail.file", null, null),
            new Request.Outcome("e1"),
            new Request.Fetch("mail.reader", "mail.attachment", Map.of("name", "a.pdf"), "a.pdf"),
            new Request.Fetch("v", "c", null, null))) {
      assertThat(Wire.decodeRequest(Wire.encode(r))).isEqualTo(r);
    }
    var finding = new Response.Verification.Finding("ERROR", "test", "x", "y");
    var commit = new Response.ProposalList.Commit("c", "Test", "ai", false, "", "s", "why");
    var proposal = new Response.ProposalList.Proposal("mine@b", "mine", "b", "c", List.of(commit));
    for (Response r :
        List.of(
            new Response.ChangeList(3, List.of("log", "shadow")),
            new Response.EffectiveView(
                "t",
                true,
                List.of(new Response.EffectiveView.Param("p", "string", "v")),
                List.of(
                    new Response.EffectiveView.Rule(
                        "r",
                        "mail",
                        "mine",
                        "sha256:1",
                        List.of("mail: add"),
                        "ai",
                        "who",
                        "why",
                        false,
                        "event mail.received")),
                List.of(new Response.EffectiveView.Disabled("d", "mine", ""))),
            new Response.LayerList(
                List.of(
                    new Response.LayerList.Layer("mail", "g @ main", null, false, true, false))),
            new Response.Lines(false, List.of("a", "b")),
            new Response.CommandList(
                List.of(
                    new Response.CommandList.Command(
                        "mail.file",
                        "File a mail",
                        null,
                        "mine",
                        "local-write",
                        null,
                        List.of(
                            new Response.CommandList.Param(
                                "folder", "string", false, "Archive", null))))),
            new Response.CommandStarted("e1"),
            new Response.Outcome(
                true, List.of(new Response.LogLines.Line("t", "e", "r", "a", "OK", "m"))),
            new Response.Verification(
                true,
                "t",
                2,
                1,
                0,
                List.of(finding),
                "SHADOW",
                List.of(new Response.Verification.Decision("r", "new", "SHADOW", List.of("x"))),
                new Response.Verification.Replay(
                    3,
                    List.of(
                        new Response.Verification.Difference("e", "t", List.of(), List.of("c"))),
                    0)),
            new Response.Verification(false, "t", 0, 0, 0, List.of(), null, null, null),
            new Response.ProposalList(List.of(proposal), null),
            new Response.Fetched(
                "/home/me/.praksis/downloads/x.part", "a.pdf", "application/pdf", 12),
            new Response.ResolvedView(
                "id",
                "t",
                1,
                List.of(),
                List.of(
                    new Response.ViewComponent(
                        "richtext",
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        new Response.ViewComponent.Rich(
                            List.of(
                                new Response.ViewComponent.Block(
                                    "paragraph",
                                    0,
                                    false,
                                    List.of(
                                        new Response.ViewComponent.Run(
                                            "hi",
                                            true,
                                            false,
                                            false,
                                            false,
                                            "https://x.example/",
                                            null)),
                                    List.of())),
                            2,
                            "images")),
                    new Response.ViewComponent(
                        "table",
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(new Response.ViewComponent.Column("n", "text")),
                        List.of(
                            new Response.ViewComponent.Row(
                                List.of("a.pdf"),
                                null,
                                List.of(
                                    new Response.ViewComponent.Action(
                                        "Save",
                                        "",
                                        Map.of(),
                                        null,
                                        new Response.ViewComponent.Download(
                                            "mail.attachment",
                                            Map.of("name", "a.pdf"),
                                            "a.pdf"))))),
                        null,
                        null,
                        null,
                        null,
                        null)),
                List.of()),
            new Response.ResolvedView(
                "id",
                "t",
                1,
                List.of(),
                List.of(
                    new Response.ViewComponent(
                        "button",
                        null,
                        null,
                        "More",
                        true,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        new Response.ViewComponent.Select(
                            "limit",
                            200,
                            List.of("message"),
                            new Response.ViewComponent.Action(
                                "mail.read", "mail.read", Map.of("message", "m"), null)))),
                List.of()),
            new Response.ProposalDetail(
                proposal,
                new Response.Verification(
                    true, "t", 1, 0, 0, List.of(), "AUTOMATIC", List.of(), null)))) {
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
