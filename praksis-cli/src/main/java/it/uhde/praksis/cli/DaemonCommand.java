// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** The command line that starts the daemon, using the same Java runtime as the CLI. */
final class DaemonCommand {
  static final String MAIN = "it.uhde.praksis.daemon/it.uhde.praksis.daemon.Main";

  private DaemonCommand() {}

  static List<String> build(Path home) {
    return build(home, System.getProperty("java.home"), System.getProperty("jdk.module.path"));
  }

  /** The same, with extra arguments for the daemon's main class. */
  static List<String> buildWith(Path home, String... extra) {
    List<String> command = build(home);
    command.addAll(List.of(extra));
    return command;
  }

  /**
   * @param modulePath the module path of this process, or null if it runs from a runtime image that
   *     contains the daemon
   */
  static List<String> build(Path home, String javaHome, String modulePath) {
    List<String> command = new ArrayList<>();
    command.add(Path.of(javaHome, "bin", "java").toString());
    if (modulePath != null && !modulePath.isEmpty()) {
      command.add("--module-path");
      command.add(modulePath);
    }
    command.add("--enable-native-access=org.xerial.sqlitejdbc");
    command.add("--module");
    command.add(MAIN);
    command.add("--home");
    command.add(home.toString());
    return command;
  }
}
