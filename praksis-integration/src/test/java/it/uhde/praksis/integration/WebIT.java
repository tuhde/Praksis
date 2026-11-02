// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;

/**
 * The HTTP door of a real daemon from the runtime image: off unless configured, a token is needed
 * for everything, a token can look but not answer an approval, a read-only token cannot start a
 * command, a page of a foreign origin is refused, and a revoked token stops working at once.
 */
class WebIT {
  private final HttpClient http = HttpClient.newHttpClient();

  private HttpResponse<String> post(int port, String token, String origin, String body)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (token != null) {
      b.header("Authorization", "Bearer " + token);
    }
    if (origin != null) {
      b.header("Origin", origin);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String secret(Harness.CliResult made) {
    assertThat(made.exit()).as(made.output()).isZero();
    return made.output().lines().findFirst().orElseThrow();
  }

  @Test
  void aWebClientWithATokenCanLookButNotApproveOrCommand() throws Exception {
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (Harness h = new Harness()) {
      Files.writeString(
          h.home.resolve("praksis.toml"),
          Files.readString(h.home.resolve("praksis.toml"))
              + "\n[web]\nlisten = \"127.0.0.1:"
              + port
              + "\"\norigins = [\"http://localhost:8080\"]\n");
      String full = secret(h.cli("web", "token", "create", "laptop"));
      String look = secret(h.cli("web", "token", "create", "wall", "--read-only"));
      assertThat(h.cli("web", "token", "list").output())
          .contains("laptop")
          .contains("wall (read only)")
          .doesNotContain(full);
      Process daemon = h.startDaemon();

      // looking
      HttpResponse<String> status = post(port, full, null, "{\"op\":\"status\"}");
      assertThat(status.statusCode()).isEqualTo(200);
      assertThat(status.body()).contains("\"type\":\"status\"");
      assertThat(post(port, look, null, "{\"op\":\"views\"}").body())
          .contains("\"type\":\"views\"");
      HttpResponse<String> changes =
          post(port, look, null, "{\"op\":\"changes\",\"since\":0,\"waitMillis\":0}");
      assertThat(changes.body()).contains("counter");

      // no token, a wrong token, a foreign page
      assertThat(post(port, null, null, "{\"op\":\"status\"}").statusCode()).isEqualTo(401);
      assertThat(post(port, "pwt_wrong", null, "{\"op\":\"status\"}").statusCode()).isEqualTo(401);
      assertThat(post(port, full, "http://evil.example", "{\"op\":\"status\"}").statusCode())
          .isEqualTo(403);
      assertThat(post(port, full, "http://localhost:8080", "{\"op\":\"status\"}").statusCode())
          .isEqualTo(200);

      // what a token cannot do, whatever its kind
      for (String token : new String[] {full, look}) {
        for (String body :
            new String[] {
              "{\"op\":\"answer\",\"id\":1,\"decision\":\"always\",\"missed\":\"replay\"}",
              "{\"op\":\"reload\"}",
              "{\"op\":\"stop\"}",
              "{\"op\":\"revoke\",\"id\":\"g\"}"
            }) {
          HttpResponse<String> r = post(port, token, null, body);
          assertThat(r.body())
              .as(body)
              .contains("\"type\":\"error\"")
              .contains("at the daemon itself");
        }
      }
      assertThat(post(port, look, null, "{\"op\":\"run-command\",\"command\":\"x\"}").body())
          .contains("read only");
      // the daemon is still there after the attempt to stop it
      assertThat(h.status()).isNotNull();

      // a revoked token stops working at once
      assertThat(h.cli("web", "token", "revoke", "laptop").exit()).isZero();
      assertThat(post(port, full, null, "{\"op\":\"status\"}").statusCode()).isEqualTo(401);
      assertThat(post(port, look, null, "{\"op\":\"status\"}").statusCode()).isEqualTo(200);
      h.stop(daemon);
    }
  }

  @Test
  void withoutTheSectionNothingListens() throws Exception {
    try (Harness h = new Harness()) {
      Process daemon = h.startDaemon();
      assertThat(h.cli("web", "token", "list").output()).contains("no web tokens");
      h.stop(daemon);
    }
  }

  /**
   * The code of the web page against a real daemon, in Node with a DOM. It runs when {@code
   * PRAKSIS_WEB_DIR} names a built checkout of Praksis-Web (with {@code npm run build} done) and
   * {@code node} is there; otherwise it is skipped.
   */
  @Test
  void thePagesCodeShowsAndOperatesTheViewsOfARealDaemon() throws Exception {
    String dir = System.getenv("PRAKSIS_WEB_DIR");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        dir != null && Files.exists(java.nio.file.Path.of(dir, "dist", "view.js")),
        "PRAKSIS_WEB_DIR is not set to a built Praksis-Web");
    int port;
    try (ServerSocket s = new ServerSocket(0)) {
      port = s.getLocalPort();
    }
    try (Harness h = new Harness()) {
      Files.writeString(h.home.resolve("ruleset/rules/mail.file.toml"), ViewsIT.COMMAND);
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(h.home.resolve("ruleset/views/mail.inbox.toml"), ViewsIT.VIEW);
      Files.writeString(
          h.home.resolve("praksis.toml"),
          Files.readString(h.home.resolve("praksis.toml"))
              + "\n[web]\nlisten = \"127.0.0.1:"
              + port
              + "\"\n");
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "ruleset-list"
              layer = "ruleset"
              capability = "mail.list"
              [grant.args]
              folder = { one_of = ["INBOX", "Archive"] }
              """);
      ViewsIT.mail(h, "1.a:2,", "ann@example.org", "Lunch");
      ViewsIT.mail(h, "2.b:2,", "bob@example.org", "Minutes");
      String token = secret(h.cli("web", "token", "create", "page"));
      Process daemon = h.startDaemon();
      Process node =
          new ProcessBuilder("node", "scripts/live-check.mjs", "http://127.0.0.1:" + port, token)
              .directory(new java.io.File(dir))
              .redirectErrorStream(true)
              .start();
      String output = new String(node.getInputStream().readAllBytes());
      assertThat(node.waitFor()).as(output).isZero();
      assertThat(output)
          .contains("views: mail.inbox")
          .contains("view mail.inbox: 3 table rows")
          .contains("INBOX: 2 mail(s)")
          .contains("selected message=")
          .contains("command mail.file:");
      h.stop(daemon);
    }
  }
}
