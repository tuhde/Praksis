// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Client;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Showing a hostile HTML mail with the runtime image: what reaches the frontend is text in blocks,
 * remote images wait for the person, an attachment is fetched only on request and under the grants
 * of the view, and nothing is fetched that the view does not offer.
 */
class HtmlMailIT {
  private static final String VIEW =
      """
      title = "Reader"
      [param.message]
      type = "string"
      default = ""
      [param.images]
      type = "bool"
      default = false

      [source.current]
      capability = "mail.get"
      args = { message = { cel = "view.message" }, images = { cel = "view.images" } }
      when = { cel = "view.message != ''" }

      [[component]]
      type = "richtext"
      blocks = { cel = "source.current.blocks" }
      blocked = { cel = "source.current.blocked" }
      images_param = "images"
      visible = { cel = "view.message != ''" }

      [[component]]
      type = "table"
      visible = { cel = "view.message != ''" }
      rows = { cel = "source.current.attachments" }
      columns = [ { title = "Attachment", value = { cel = "item.name" } } ]
      [[component.row_action]]
      label = "Save"
      download = { capability = "mail.attachment", args = { message = { cel = "view.message" }, name = { cel = "item.name" } }, filename = { cel = "item.name" } }
      """;

  private static final String MAIL =
      """
      From: attacker@example.org
      Subject: Win a prize
      MIME-Version: 1.0
      Content-Type: multipart/mixed; boundary="b"

      --b
      Content-Type: text/html; charset=utf-8

      <html><head><script>steal()</script></head><body onload="steal()">
      <p>Hello <b>friend</b>,</p>
      <p><a href="javascript:steal()">click here</a> or <a href="https://ok.example/page">here</a></p>
      <img src="https://tracker.example/pixel.gif" alt="logo">
      <iframe src="https://evil.example/"></iframe>
      <form action="https://evil.example/"><input name="pw"></form>
      </body></html>
      --b
      Content-Type: application/octet-stream; name="data.bin"
      Content-Disposition: attachment; filename="data.bin"
      Content-Transfer-Encoding: base64

      AAECAwQF
      --b--
      """;

  private static String texts(List<Response.ViewComponent.Block> blocks) {
    StringBuilder out = new StringBuilder();
    for (var b : blocks) {
      out.append(
          b.runs().stream().map(Response.ViewComponent.Run::text).collect(Collectors.joining()));
      out.append('\n').append(texts(b.children()));
    }
    return out.toString();
  }

  private static Response.ViewComponent.Rich rich(Response.ResolvedView view) {
    return view.components().get(0).rich();
  }

  @Test
  void aHostileMailReachesTheFrontendAsTextAndAnAttachmentIsOnlyFetchedOnRequest()
      throws Exception {
    try (Harness h = new Harness()) {
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(h.home.resolve("ruleset/views/reader.toml"), VIEW);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "get"
              layer = "ruleset"
              capability = "mail.get"

              [[grant]]
              id = "attachment"
              layer = "ruleset"
              capability = "mail.attachment"
              """);
      Path mail = h.mail.resolve("cur").resolve("1.a:2,S");
      Files.writeString(mail, MAIL);
      Process daemon = h.startDaemon();

      try (Client client = Client.connect(h.home.resolve("praksis.sock"))) {
        Response.ResolvedView view =
            (Response.ResolvedView)
                client.send(
                    new Request.ViewResolve("reader", Map.of("message", mail.toString()), 1));
        assertThat(view.warnings()).isEmpty();
        Response.ViewComponent.Rich rich = rich(view);
        String shown = texts(rich.blocks());
        assertThat(shown).contains("Hello friend,").contains("click here").contains("here");
        for (String gone : List.of("steal", "evil.example", "iframe", "onload", "script")) {
          assertThat(shown).as(gone).doesNotContain(gone);
        }
        // the only link is the good one; the image is not loaded but could be
        List<Response.ViewComponent.Run> runs =
            rich.blocks().stream().flatMap(b -> b.runs().stream()).toList();
        assertThat(runs.stream().map(Response.ViewComponent.Run::link).filter(l -> l != null))
            .containsExactly("https://ok.example/page");
        assertThat(runs.stream().map(Response.ViewComponent.Run::image).filter(i -> i != null))
            .isEmpty();
        assertThat(shown).contains("[image: logo]");
        assertThat(rich.blocked()).isEqualTo(1);
        assertThat(rich.imagesParam()).isEqualTo("images");

        // the person allows the images of this mail: now the picture is described
        Response.ResolvedView allowed =
            (Response.ResolvedView)
                client.send(
                    new Request.ViewResolve(
                        "reader", Map.of("message", mail.toString(), "images", true), 1));
        assertThat(
                rich(allowed).blocks().stream()
                    .flatMap(b -> b.runs().stream())
                    .map(Response.ViewComponent.Run::image)
                    .filter(i -> i != null))
            .containsExactly("https://tracker.example/pixel.gif");
        assertThat(rich(allowed).blocked()).isZero();

        // nothing was fetched yet; the description only says what a download would be
        var download = view.components().get(1).rows().get(0).actions().get(0).download();
        assertThat(download.capability()).isEqualTo("mail.attachment");
        assertThat(Files.exists(h.home.resolve("downloads"))).isFalse();

        // a person asks: the file is in the private directory, for this user only
        Response.Fetched fetched =
            (Response.Fetched)
                client.send(
                    new Request.Fetch(
                        "reader", download.capability(), download.args(), download.filename()));
        assertThat(fetched.name()).isEqualTo("data.bin");
        assertThat(fetched.size()).isEqualTo(6);
        Path file = Path.of(fetched.path());
        assertThat(file.getParent()).isEqualTo(h.home.resolve("downloads"));
        assertThat(Files.readAllBytes(file)).containsExactly(0, 1, 2, 3, 4, 5);
        assertThat(Files.getPosixFilePermissions(file))
            .containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        assertThat(Files.getPosixFilePermissions(h.home.resolve("downloads")))
            .containsExactlyInAnyOrder(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE);

        // the view cannot be used to read anything it does not offer
        assertThat(
                client.send(
                    new Request.Fetch(
                        "reader", "mail.get", Map.of("message", mail.toString()), "x")))
            .isEqualTo(new Response.Error("view 'reader' has no download of 'mail.get'"));
        assertThat(
                client.send(
                    new Request.Fetch(
                        "reader",
                        "mail.attachment",
                        Map.of("message", mail.toString(), "name", "nope"),
                        "x")))
            .isInstanceOf(Response.Error.class);
      }
      // the fetch is on the record
      assertThat(h.cli("audit", "-n", "50").output())
          .contains("view:reader")
          .contains("mail.attachment");
      h.stop(daemon);
    }
  }

  @Test
  void withoutAGrantNothingIsFetched() throws Exception {
    try (Harness h = new Harness()) {
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(h.home.resolve("ruleset/views/reader.toml"), VIEW);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "get"
              layer = "ruleset"
              capability = "mail.get"
              """);
      Path mail = h.mail.resolve("cur").resolve("1.a:2,S");
      Files.writeString(mail, MAIL);
      Process daemon = h.startDaemon();

      try (Client client = Client.connect(h.home.resolve("praksis.sock"))) {
        Response answer =
            client.send(
                new Request.Fetch(
                    "reader",
                    "mail.attachment",
                    Map.of("message", mail.toString(), "name", "data.bin"),
                    "data.bin"));
        assertThat(answer).isInstanceOf(Response.Error.class);
        assertThat(((Response.Error) answer).message()).contains("grant");
      }
      assertThat(Files.exists(h.home.resolve("downloads"))).isFalse();
      h.stop(daemon);
    }
  }
}
