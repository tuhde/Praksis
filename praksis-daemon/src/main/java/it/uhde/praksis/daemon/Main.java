// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import java.nio.file.Path;

/** Starts the daemon: {@code java -m it.uhde.praksis.daemon [--home <dir>]}. */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws InterruptedException {
    String home = System.getenv("PRAKSIS_HOME");
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--home") && i + 1 < args.length) {
        home = args[++i];
      } else {
        System.err.println("usage: praksis-daemon [--home <dir>]");
        System.exit(2);
      }
    }
    if (home == null) {
      home = Path.of(System.getProperty("user.home"), ".praksis").toString();
    }
    Daemon daemon;
    try {
      daemon = Daemon.start(DaemonConfig.load(Path.of(home)));
    } catch (DaemonException e) {
      System.err.println("praksis daemon: " + e.getMessage());
      System.exit(1);
      return;
    }
    Runtime.getRuntime().addShutdownHook(new Thread(daemon::close, "praksis-shutdown"));
    daemon.awaitStop();
    daemon.close();
  }
}
