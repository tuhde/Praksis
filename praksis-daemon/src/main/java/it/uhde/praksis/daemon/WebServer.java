// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import it.uhde.praksis.protocol.ProtocolException;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;

/**
 * The HTTP side of the API, for web clients. {@code POST /api} takes one request of the protocol as
 * JSON and answers with one response. It is a second door to the same daemon, so it is narrow:
 *
 * <ul>
 *   <li>it listens on a loopback address only, and only if {@code [web]} says so;
 *   <li>every request needs {@code Authorization: Bearer <token>} (see {@link WebTokens}); there
 *       are no cookies, so a page in the browser cannot be made to send a request by another site;
 *   <li>a request from a browser page of another origin than the daemon's own or a listed one is
 *       refused, and the body must be {@code application/json}, which a form cannot send;
 *   <li>body size, the rate of requests per token and failed logins per address are limited;
 *   <li>what a token may ask for is decided by the {@code gate}, not here.
 * </ul>
 */
final class WebServer implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(WebServer.class.getName());
  static final int MAX_BODY = 1 << 20;
  static final int MAX_FAILED_LOGINS = 10;
  static final int MAX_REQUESTS_PER_MINUTE = 600;

  private final WebConfig.Listen listen;
  private final List<String> origins;
  private final WebTokens tokens;
  private final BiFunction<WebTokens.Token, Request, Response> gate;
  private final RateLimiter failedLogins;
  private final RateLimiter requests;
  private HttpServer server;

  /**
   * @param gate answers a request of an authenticated token, or refuses it
   */
  WebServer(
      WebConfig.Listen listen,
      List<String> origins,
      WebTokens tokens,
      BiFunction<WebTokens.Token, Request, Response> gate,
      Clock clock) {
    this.listen = listen;
    this.origins = origins;
    this.tokens = tokens;
    this.gate = gate;
    this.failedLogins = new RateLimiter(MAX_FAILED_LOGINS, Duration.ofMinutes(1), clock);
    this.requests = new RateLimiter(MAX_REQUESTS_PER_MINUTE, Duration.ofMinutes(1), clock);
  }

  void start() throws IOException {
    String host = listen.host().equals("[::1]") ? "::1" : listen.host();
    InetAddress address = InetAddress.getByName(host);
    if (!address.isLoopbackAddress()) {
      throw new IOException("the web listener only binds to a loopback address");
    }
    server = HttpServer.create(new InetSocketAddress(address, listen.port()), 0);
    server.createContext("/api", this::handle);
    server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
    server.start();
  }

  /** The port actually bound (for port 0 in tests). */
  int port() {
    return server.getAddress().getPort();
  }

  private void handle(HttpExchange ex) throws IOException {
    try (ex) {
      String origin = ex.getRequestHeaders().getFirst("Origin");
      if (origin != null && !originAllowed(origin, ex.getRequestHeaders().getFirst("Host"))) {
        send(ex, 403, null, new Response.Error("this origin is not allowed"));
        return;
      }
      if (ex.getRequestMethod().equals("OPTIONS")) {
        // the answer to a browser's question before a call from a listed origin
        if (origin != null) {
          ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
          ex.getResponseHeaders()
              .add("Access-Control-Allow-Headers", "Authorization, Content-Type");
          ex.getResponseHeaders().add("Access-Control-Allow-Methods", "POST");
          ex.getResponseHeaders().add("Access-Control-Max-Age", "600");
          ex.getResponseHeaders().add("Vary", "Origin");
        }
        ex.sendResponseHeaders(204, -1);
        return;
      }
      if (!ex.getRequestMethod().equals("POST")) {
        ex.getResponseHeaders().add("Allow", "POST");
        send(ex, 405, origin, new Response.Error("use POST"));
        return;
      }
      String remote = ex.getRemoteAddress().getAddress().getHostAddress();
      if (failedLogins.blocked(remote)) {
        send(ex, 429, origin, new Response.Error("too many failed logins; wait a minute"));
        return;
      }
      Optional<WebTokens.Token> token = tokens.authenticate(bearer(ex));
      if (token.isEmpty()) {
        failedLogins.allow(remote);
        ex.getResponseHeaders().add("WWW-Authenticate", "Bearer");
        send(ex, 401, origin, new Response.Error("a valid token is needed"));
        return;
      }
      if (!requests.allow(token.get().name())) {
        send(ex, 429, origin, new Response.Error("too many requests; slow down"));
        return;
      }
      String type = ex.getRequestHeaders().getFirst("Content-Type");
      if (type == null || !type.toLowerCase(java.util.Locale.ROOT).startsWith("application/json")) {
        send(ex, 415, origin, new Response.Error("the body must be application/json"));
        return;
      }
      String body = readBody(ex);
      if (body == null) {
        send(ex, 413, origin, new Response.Error("the request is too large"));
        return;
      }
      Response response;
      try {
        response = gate.apply(token.get(), Wire.decodeRequest(body));
      } catch (ProtocolException e) {
        send(ex, 400, origin, new Response.Error(e.getMessage()));
        return;
      } catch (RuntimeException e) {
        LOG.log(System.Logger.Level.ERROR, "web request failed", e);
        send(ex, 500, origin, new Response.Error("internal error"));
        return;
      }
      send(ex, 200, origin, response);
    }
  }

  private boolean originAllowed(String origin, String host) {
    if (origins.contains(origin)) {
      return true;
    }
    // the page was served by this very listener
    return host != null && (origin.equals("http://" + host) || origin.equals("https://" + host));
  }

  private static String bearer(HttpExchange ex) {
    String h = ex.getRequestHeaders().getFirst("Authorization");
    return h != null && h.regionMatches(true, 0, "Bearer ", 0, 7) ? h.substring(7).trim() : null;
  }

  private static String readBody(HttpExchange ex) throws IOException {
    try (InputStream in = ex.getRequestBody()) {
      byte[] data = in.readNBytes(MAX_BODY + 1);
      return data.length > MAX_BODY ? null : new String(data, StandardCharsets.UTF_8);
    }
  }

  private static void send(HttpExchange ex, int status, String origin, Response response)
      throws IOException {
    byte[] data = Wire.encode(response).getBytes(StandardCharsets.UTF_8);
    ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
    ex.getResponseHeaders().add("Cache-Control", "no-store");
    ex.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
    if (origin != null) {
      ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
      ex.getResponseHeaders().add("Vary", "Origin");
    }
    ex.sendResponseHeaders(status, data.length);
    ex.getResponseBody().write(data);
  }

  @Override
  public void close() {
    if (server != null) {
      server.stop(0);
    }
  }
}
