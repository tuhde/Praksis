// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.engine.ModuleInstaller;
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
    String install = null;
    String remove = null;
    boolean list = false;
    boolean replace = false;
    java.util.List<String> admin = null;
    for (int i = 0; i < args.length; i++) {
      if (args[i].equals("--home") && i + 1 < args.length) {
        home = args[++i];
      } else if (args[i].equals("--lock-modules")) {
        lock = true;
      } else if (args[i].equals("--install-module") && i + 1 < args.length) {
        install = args[++i];
      } else if (args[i].equals("--remove-module") && i + 1 < args.length) {
        remove = args[++i];
      } else if (args[i].equals("--list-modules")) {
        list = true;
      } else if (args[i].equals("--replace")) {
        replace = true;
      } else if (args[i].equals("--admin")) {
        admin = java.util.List.of(args).subList(i + 1, args.length);
        break;
      } else {
        System.err.println(
            "usage: praksis-daemon [--home <dir>] [--lock-modules | --install-module <file> [--replace] |"
                + " --remove-module <name> | --list-modules]");
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
                config,
                new GitCli(config.gitCache()),
                Admin.validatorFor(config),
                Admin.accessFor(config),
                admin,
                out,
                err);
        System.exit(exit);
      } catch (DaemonException e) {
        System.err.println("praksis: " + e.getMessage());
        System.exit(1);
      }
    }
    if (install != null || remove != null || list) {
      System.exit(moduleCommand(home, install, remove, list, replace));
    }
    if (lock) {
      try {
        DaemonConfig config = DaemonConfig.load(Path.of(home));
        ModuleLoader.lock(config.moduleRoots(), config.lockfile());
        System.out.println(
            "Locked the modules in " + config.moduleRoots() + " in " + config.lockfile());
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

  /** Installs, removes or lists modules without the daemon; returns the exit code. */
  private static int moduleCommand(
      String home, String install, String remove, boolean list, boolean replace) {
    try {
      DaemonConfig config = DaemonConfig.load(Path.of(home));
      if (install != null) {
        var done =
            ModuleInstaller.install(Path.of(install), config.modules(), config.lockfile(), replace);
        System.out.println(
            "Installed module '"
                + done.name()
                + "' "
                + done.version()
                + " in "
                + done.directory()
                + " and recorded it in "
                + config.lockfile()
                + " (sha256 "
                + done.sha256().substring(0, 12)
                + "...).");
        System.out.println(
            "It runs as the daemon with its rights. Enable it with enabled_modules in"
                + " praksis.toml if you use that list, then restart the daemon.");
      } else if (remove != null) {
        ModuleInstaller.remove(remove, config.modules(), config.lockfile());
        System.out.println("Removed module '" + remove + "'. Restart the daemon.");
        // the lockfile has one entry per name: what was recorded for the removed module is gone,
        // and
        // a module of the same name that now shows (the one of the runtime image) has to be
        // accepted
        for (ModuleInstaller.Entry e :
            ModuleInstaller.list(config.moduleRoots(), config.lockfile())) {
          if (e.name().equals(remove) && !e.locked()) {
            System.out.println(
                "The module '"
                    + remove
                    + "' in "
                    + e.directory()
                    + " is used again; accept it with 'praksis modules lock'.");
          }
        }
      } else {
        for (ModuleInstaller.Entry e :
            ModuleInstaller.list(config.moduleRoots(), config.lockfile())) {
          System.out.println(
              String.format("%-12s %-10s %s", e.name(), e.version(), e.directory())
                  + (e.hidden() ? "  (hidden by a module of the same name above)" : "")
                  + (e.locked() ? "" : "  (not locked: 'praksis modules lock')"));
        }
      }
      return 0;
    } catch (DaemonException | ModuleLoadException e) {
      System.err.println("praksis: " + e.getMessage());
      return 1;
    }
  }
}
