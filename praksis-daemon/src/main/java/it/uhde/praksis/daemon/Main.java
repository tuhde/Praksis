// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.ModuleLoadException;
import it.uhde.praksis.engine.ModuleLoader;
import it.uhde.praksis.git.GitCli;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * Starts the daemon: {@code java -m it.uhde.praksis.daemon [--home <dir>]}. With {@code
 * --lock-modules} it instead accepts the modules as they are now by writing the lockfile, and with
 * {@code --admin <command>} it runs a command of {@link Admin}.
 */
public final class Main {
  private Main() {}

  public static void main(String[] args) throws InterruptedException {
    String home = System.getenv("PRAKSIS_HOME");
    boolean lock = false;
    java.util.List<String> admin = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--home") && i + 1 < args.length) {
        home = args[++i];
      } else if (args[i].equals("--lock-modules")) {
        lock = true;
      } else if (args[i].equals("--admin")) {
        admin = java.util.List.of(args).subList(i + 1, args.length);
        break;
      } else {
        System.err.println("usage: praksis-daemon [--home <dir>] [--lock-modules]");
        System.exit(2);
      }
    }
    if (home == null) {
      home = Path.of(System.getProperty("user.home"), ".praksis").toString();
    }
    if (admin != null) {
      try {
        DaemonConfig config = DaemonConfig.load(Path.of(home));
        PrintWriter out = new PrintWriter(System.out, true);
        PrintWriter err = new PrintWriter(System.err, true);
        int exit =
            Admin.run(
                config, new GitCli(config.gitCache()), Admin.validatorFor(config), admin, out, err);
        System.exit(exit);
      } catch (DaemonException e) {
        System.err.println("praksis: " + e.getMessage());
        System.exit(1);
      }
    }
    if (lock) {
      try {
        DaemonConfig config = DaemonConfig.load(Path.of(home));
        ModuleLoader.lock(config.modules(), config.lockfile());
        System.out.println(
            "Locked the modules in " + config.modules() + " in " + config.lockfile());
        return;
      } catch (DaemonException | ModuleLoadException e) {
        System.err.println("praksis: " + e.getMessage());
        System.exit(1);
      }
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
    // Everything is closed and stored. A thread that a module forgot must not keep the process.
    System.exit(0);
  }
}
