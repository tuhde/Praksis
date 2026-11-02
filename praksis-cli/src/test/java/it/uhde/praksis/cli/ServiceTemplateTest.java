// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The systemd unit that starts one daemon per identity says what the command line does. */
class ServiceTemplateTest {
  private static final Path FILE =
      Path.of("..", "praksis-dist", "src", "service", "praksis@.service");

  @Test
  void theUnitStartsTheDaemonOfTheIdentityInTheHomeThatTheCommandLineWouldUse() throws IOException {
    List<String> lines = Files.readAllLines(FILE);
    String start = line(lines, "ExecStart=");
    // the same module and home as 'praksis start' (see DaemonCommand), with %i as the identity
    assertThat(start).contains("--module " + DaemonCommand.MAIN);
    assertThat(start).contains("--home %h/.praksis-%i");
    assertThat(start).contains("--enable-native-access=org.xerial.sqlitejdbc");
    assertThat(line(lines, "ExecStop=")).contains("--identity %i stop");
    assertThat(line(lines, "Environment=")).isEqualTo("Environment=PRAKSIS_IDENTITY=%i");
    assertThat(lines).contains("[Unit]", "[Service]", "[Install]");
    // the foreground process is the daemon: systemd supervises it, so no 'start' that detaches
    assertThat(lines).noneMatch(l -> l.startsWith("ExecStart=") && l.contains(" start"));
  }

  @Test
  void theHomeThatTheUnitUsesIsTheOneOfTheIdentityConvention() {
    // %i is an identity name, so ~/.praksis-%i is what Identities.home gives
    assertThat(Identities.home("private").getFileName().toString()).isEqualTo(".praksis-private");
  }

  private static String line(List<String> lines, String prefix) {
    return lines.stream().filter(l -> l.startsWith(prefix)).findFirst().orElseThrow();
  }
}
