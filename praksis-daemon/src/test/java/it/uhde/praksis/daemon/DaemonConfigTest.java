// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DaemonConfigTest {
  @TempDir Path home;

  @Test
  void defaultsNeedNoFile() throws Exception {
    DaemonConfig c = DaemonConfig.load(home);
    assertThat(c.ruleset()).isEqualTo(home.resolve("ruleset"));
    assertThat(c.modules()).isEqualTo(home.resolve("modules"));
    assertThat(c.lockfile()).isEqualTo(home.resolve("modules.lock"));
    assertThat(c.database()).isEqualTo(home.resolve("praksis.db"));
    assertThat(c.socket()).isEqualTo(home.resolve("praksis.sock"));
    assertThat(c.expression()).isEmpty();
    assertThat(c.dryRun()).isFalse();
    assertThat(c.moduleSettings()).isEmpty();
  }

  @Test
  void readsPathsAndModuleSettingsWithLongIntegers() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"),
        """
        format_version = 1
        ruleset = "rules/mine"
        expression = "lib/expression"
        dry_run = true

        [module.maildir]
        root = "/m"
        poll_interval_ms = 500
        nested = { n = 1, list = [1, 2] }
        """);
    DaemonConfig c = DaemonConfig.load(home);
    assertThat(c.ruleset()).isEqualTo(home.resolve("rules/mine"));
    assertThat(c.expression()).contains(home.resolve("lib/expression"));
    assertThat(c.dryRun()).isTrue();
    assertThat(c.moduleSettings().get("maildir"))
        .containsEntry("root", "/m")
        .containsEntry("poll_interval_ms", 500L)
        .containsEntry("nested", java.util.Map.of("n", 1L, "list", List.of(1L, 2L)));
  }

  @Test
  void reportsAllProblems() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"), "format_version = 2\nrulset = \"x\"\nmodules = 3\n");
    assertThatThrownBy(() -> DaemonConfig.load(home))
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("'format_version' must be 1")
        .hasMessageContaining("unknown key 'rulset'")
        .hasMessageContaining("'modules' must be a string");
    assertThatThrownBy(() -> DaemonConfig.load(home.resolve("missing")))
        .hasMessageContaining("does not exist");
  }
}
