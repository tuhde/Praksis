// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** A connection to the daemon's Unix domain socket. Requests are answered in order. */
public final class Client implements AutoCloseable {
  private final SocketChannel channel;
  private final InputStream in;
  private final OutputStream out;

  private Client(SocketChannel channel) {
    this.channel = channel;
    this.in = Channels.newInputStream(channel);
    this.out = Channels.newOutputStream(channel);
  }

  /**
   * Connects to the daemon.
   *
   * @throws IOException if nothing is listening on the socket
   */
  public static Client connect(Path socket) throws IOException {
    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    try {
      channel.connect(UnixDomainSocketAddress.of(socket));
    } catch (IOException e) {
      channel.close();
      throw e;
    }
    return new Client(channel);
  }

  /** Sends a request and waits for the answer. */
  public synchronized Response send(Request request) throws IOException {
    out.write((Wire.encode(request) + "\n").getBytes(StandardCharsets.UTF_8));
    out.flush();
    String line = Wire.readLine(in);
    if (line == null) {
      throw new ProtocolException("the daemon closed the connection");
    }
    return Wire.decodeResponse(line);
  }

  @Override
  public void close() throws IOException {
    channel.close();
  }
}
