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
    assertThat(c.enabledModules()).isEmpty();
  }

  @Test
  void withoutAListTheModulesWithSettingsAreTheEnabledOnes() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\n[module.imap]\nhost = \"h\"\n[module.other]\nx = 1\n");
    assertThat(DaemonConfig.load(home).enabledModules()).containsExactlyInAnyOrder("imap", "other");
    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\nenabled_modules = []\n[module.imap]\nhost = \"h\"\n");
    assertThat(DaemonConfig.load(home).enabledModules()).isEmpty();
  }

  @Test
  void readsTheEnabledModules() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"), "format_version = 1\nenabled_modules = [\"imap\"]\n");
    assertThat(DaemonConfig.load(home).enabledModules()).containsExactly("imap");
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

  @Test
  void readsLayersAndParameters() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"),
        """
        format_version = 1

        [[layer]]
        name = "mail"
        git = "https://example.org/Praksis-Mail"
        ref = "main"

        [[layer]]
        name = "local"
        path = "my-rules"

        [param]
        folder = "News"
        count = 3
        """);
    DaemonConfig c = DaemonConfig.load(home);
    assertThat(c.layers()).hasSize(2);
    assertThat(c.layers().get(0).isGit()).isTrue();
    assertThat(c.layers().get(0).git()).contains("https://example.org/Praksis-Mail");
    assertThat(c.layers().get(0).ref()).contains("main");
    assertThat(c.layers().get(1).isGit()).isFalse();
    assertThat(c.layers().get(1).path()).contains(home.resolve("my-rules"));
    assertThat(c.params()).containsEntry("folder", "News").containsEntry("count", 3L);
    assertThat(c.rulesetLock()).isEqualTo(home.resolve("rulesets.lock"));
    assertThat(c.snapshots()).isEqualTo(home.resolve("layers"));
  }

  @Test
  void aLayerIsMaintainedByAnAiOnlyIfItIsADirectoryOrSaysSo() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"),
        """
        format_version = 1

        [[layer]]
        name = "mail"
        git = "https://example.org/Praksis-Mail"
        ref = "main"

        [[layer]]
        name = "mine"
        git = "git@example.org:me/myPraksis.git"
        ref = "main"
        ai_maintained = true

        [[layer]]
        name = "local"
        path = "my-rules"

        [[layer]]
        name = "frozen"
        path = "frozen-rules"
        ai_maintained = false
        """);
    DaemonConfig c = DaemonConfig.load(home);
    assertThat(c.layers())
        .extracting(LayerConfig::aiMaintained)
        .containsExactly(false, true, true, false);
    assertThat(c.aiMaintainedLayers())
        .extracting(LayerConfig::name)
        .containsExactly("mine", "local");

    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\n[[layer]]\nname = \"a\"\npath = \"p\"\nai_maintained = \"yes\"\n");
    assertThatThrownBy(() -> DaemonConfig.load(home)).hasMessageContaining("ai_maintained");
  }

  @Test
  void reportsMistakesInLayers() throws Exception {
    Files.writeString(
        home.resolve("praksis.toml"),
        """
        format_version = 1
        ruleset = "x"

        [[layer]]
        name = "a"
        git = "g"

        [[layer]]
        name = "a"
        path = "p"
        ref = "main"

        [[layer]]
        name = "b c"
        git = "g"
        path = "p"
        ref = "main"

        [[layer]]
        git = "g"
        ref = "main"
        wrong = 1
        """);
    assertThatThrownBy(() -> DaemonConfig.load(home))
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("a git layer needs 'ref'")
        .hasMessageContaining("layer name 'a' is used twice")
        .hasMessageContaining("'ref' is only for git layers")
        .hasMessageContaining("must match")
        .hasMessageContaining("give exactly one of 'git' and 'path'")
        .hasMessageContaining("missing 'name'")
        .hasMessageContaining("unknown key 'wrong'")
        .hasMessageContaining("'ruleset' and [[layer]] cannot be used together");
  }

  @Test
  void theLayerOfPlansMustBeOneOfTheLayersAndStepsAreBounded() throws Exception {
    assertThat(DaemonConfig.load(home).plan().layer()).isEmpty();
    assertThat(DaemonConfig.load(home).plan().maxSteps()).isEqualTo(30);

    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\n[[layer]]\nname = \"mine\"\npath = \"p\"\n"
            + "[plan]\nlayer = \"mine\"\nmax_steps = 5\n");
    DaemonConfig c = DaemonConfig.load(home);
    assertThat(c.plan().layer()).contains("mine");
    assertThat(c.plan().maxSteps()).isEqualTo(5);

    Files.writeString(
        home.resolve("praksis.toml"),
        "format_version = 1\n[[layer]]\nname = \"mine\"\npath = \"p\"\n"
            + "[plan]\nlayer = \"other\"\nmax_steps = 500\n");
    assertThatThrownBy(() -> DaemonConfig.load(home))
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("other")
        .hasMessageContaining("max_steps");
  }
}
