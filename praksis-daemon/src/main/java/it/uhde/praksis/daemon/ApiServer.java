// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.ProtocolException;
import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Wire;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Serves the local API: JSON lines on a Unix domain socket that only the owner may use. Each
 * connection is served by a virtual thread and may send any number of requests.
 */
final class ApiServer implements AutoCloseable {
  private static final System.Logger LOG = System.getLogger(ApiServer.class.getName());

  private final Path socket;
  private final Function<Request, Response> handler;
  private final Runnable afterStop;
  private final Set<SocketChannel> connections = ConcurrentHashMap.newKeySet();
  private ServerSocketChannel server;

  /**
   * @param handler answers a request
   * @param afterStop run after the answer to a {@link Request.Stop} has been sent
   */
  ApiServer(Path socket, Function<Request, Response> handler, Runnable afterStop) {
    this.socket = socket;
    this.handler = handler;
    this.afterStop = afterStop;
  }

  void start() throws IOException {
    server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      server.bind(UnixDomainSocketAddress.of(socket));
      Files.setPosixFilePermissions(socket, PosixFilePermissions.fromString("rw-------"));
    } catch (IOException | RuntimeException e) {
      server.close();
      throw e;
    }
    Thread.ofPlatform().name("praksis-api").daemon().start(this::acceptLoop);
  }

  private void acceptLoop() {
    while (true) {
      SocketChannel channel;
      try {
        channel = server.accept();
      } catch (ClosedChannelException e) {
        return;
      } catch (IOException e) {
        LOG.log(System.Logger.Level.ERROR, "api accept failed: " + e);
        return;
      }
      connections.add(channel);
      Thread.ofVirtual().name("praksis-api-connection").start(() -> serve(channel));
    }
  }

  private void serve(SocketChannel channel) {
    try (channel) {
      InputStream in = Channels.newInputStream(channel);
      OutputStream out = Channels.newOutputStream(channel);
      String line;
      while ((line = Wire.readLine(in)) != null) {
        Request request = null;
        Response response;
        try {
          request = Wire.decodeRequest(line);
          response = handler.apply(request);
        } catch (ProtocolException e) {
          response = new Response.Error(e.getMessage());
        } catch (RuntimeException e) {
          LOG.log(System.Logger.Level.ERROR, "request failed", e);
          response = new Response.Error("internal error: " + e);
        }
        out.write((Wire.encode(response) + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        if (request instanceof Request.Stop) {
          afterStop.run();
        }
      }
    } catch (IOException e) {
      // the peer left or sent something we cannot read; either way this connection is over
      LOG.log(System.Logger.Level.DEBUG, "connection ended: " + e);
    } finally {
      connections.remove(channel);
    }
  }

  @Override
  public void close() {
    try {
      if (server != null) {
        server.close();
      }
      for (SocketChannel c : connections) {
        c.close();
      }
      Files.deleteIfExists(socket);
    } catch (IOException e) {
      LOG.log(System.Logger.Level.WARNING, "cannot close the api cleanly: " + e);
    }
  }
}
