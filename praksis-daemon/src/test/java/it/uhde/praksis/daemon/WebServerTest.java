// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Wire;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebServerTest {
  @TempDir Path dir;
  private WebTokens tokens;
  private WebServer server;
  private String secret;
  private final AtomicReference<WebTokens.Token> seen = new AtomicReference<>();
  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void start() throws Exception {
    tokens = new WebTokens(dir);
    secret = tokens.create("phone", false, Instant.now());
    server =
        new WebServer(
            new WebConfig.Listen("127.0.0.1", 0),
            List.of("http://localhost:8080"),
            tokens,
            (t, r) -> {
              seen.set(t);
              return new Response.Error("echo " + r.getClass().getSimpleName());
            },
            Clock.systemUTC());
    server.start();
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private HttpResponse<String> post(String auth, String origin, String type, String body)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api"))
            .POST(HttpRequest.BodyPublishers.ofString(body));
    if (auth != null) {
      b.header("Authorization", "Bearer " + auth);
    }
    if (origin != null) {
      b.header("Origin", origin);
    }
    if (type != null) {
      b.header("Content-Type", type);
    }
    return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
  }

  private static String status() throws Exception {
    return Wire.encode(new Request.Status());
  }

  @Test
  void aTokenReachesTheGate() throws Exception {
    var r = post(secret, null, "application/json", status());
    assertEquals(200, r.statusCode());
    assertTrue(r.body().contains("echo Status"), r.body());
    assertEquals("phone", seen.get().name());
    assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
  }

  @Test
  void withoutAValidTokenNothingReachesTheGate() throws Exception {
    assertEquals(401, post(null, null, "application/json", status()).statusCode());
    assertEquals(401, post("pwt_wrong", null, "application/json", status()).statusCode());
    tokens.revoke("phone");
    assertEquals(401, post(secret, null, "application/json", status()).statusCode());
    assertEquals(null, seen.get());
  }

  @Test
  void aPageOfAnotherOriginIsRefusedEvenWithAToken() throws Exception {
    assertEquals(
        403, post(secret, "http://evil.example", "application/json", status()).statusCode());
    assertEquals(null, seen.get());
    var ok = post(secret, "http://localhost:8080", "application/json", status());
    assertEquals(200, ok.statusCode());
    assertEquals(
        "http://localhost:8080", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(""));
  }

  @Test
  void theBodyMustBeJsonAndSmallAndWellFormed() throws Exception {
    assertEquals(415, post(secret, null, "text/plain", status()).statusCode());
    assertEquals(415, post(secret, null, null, status()).statusCode());
    assertEquals(
        413,
        post(secret, null, "application/json", "x".repeat(WebServer.MAX_BODY + 1)).statusCode());
    assertEquals(400, post(secret, null, "application/json", "{not json").statusCode());
  }

  @Test
  void onlyPostIsAnswered() throws Exception {
    var r =
        http.send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/api"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());
    assertEquals(405, r.statusCode());
  }

  @Test
  void manyFailedLoginsAreSlowedDown() throws Exception {
    for (int i = 0; i < WebServer.MAX_FAILED_LOGINS; i++) {
      assertEquals(401, post("pwt_bad" + i, null, "application/json", status()).statusCode());
    }
    // even the right token waits: the address is blocked, not the token
    assertEquals(429, post(secret, null, "application/json", status()).statusCode());
  }

  @Test
  void aTokenCannotHoldMoreLongPollsThanTheLimit() throws Exception {
    server.close();
    var release = new java.util.concurrent.CountDownLatch(1);
    var inside = new java.util.concurrent.CountDownLatch(WebServer.MAX_CONCURRENT);
    server =
        new WebServer(
            new WebConfig.Listen("127.0.0.1", 0),
            List.of(),
            tokens,
            (t, r) -> {
              inside.countDown();
              try {
                release.await();
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              return new Response.Error("late");
            },
            Clock.systemUTC());
    server.start();
    var waiting = new ArrayList<java.util.concurrent.CompletableFuture<HttpResponse<String>>>();
    for (int i = 0; i < WebServer.MAX_CONCURRENT; i++) {
      waiting.add(
          java.util.concurrent.CompletableFuture.supplyAsync(
              () -> {
                try {
                  return post(secret, null, "application/json", status());
                } catch (Exception e) {
                  throw new IllegalStateException(e);
                }
              }));
    }
    assertTrue(inside.await(10, java.util.concurrent.TimeUnit.SECONDS));
    assertEquals(429, post(secret, null, "application/json", status()).statusCode());
    release.countDown();
    for (var w : waiting) {
      assertEquals(200, w.get().statusCode());
    }
    // the places are free again
    assertEquals(200, post(secret, null, "application/json", status()).statusCode());
  }
}
