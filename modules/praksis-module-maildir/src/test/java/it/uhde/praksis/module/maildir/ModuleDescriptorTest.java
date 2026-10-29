// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.engine.LoadedModules;
import it.uhde.praksis.engine.ModuleLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks module.toml against the real classes, by loading the built classes like the daemon. */
class ModuleDescriptorTest {
  @TempDir Path dir;

  @Test
  void moduleTomlMatchesWhatTheModuleProvides() throws Exception {
    Path classes =
        Path.of(MailMove.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    Path modules = dir.resolve("modules");
    Path moduleDir = Files.createDirectories(modules.resolve("maildir"));
    Files.copy(Path.of("module.toml"), moduleDir.resolve("module.toml"));
    Jars.pack(classes, moduleDir.resolve("praksis-module-maildir.jar"));
    Path lock = dir.resolve("modules.lock");
    ModuleLoader.lock(modules, lock);

    Path root = Files.createDirectories(dir.resolve("mail"));
    try (LoadedModules loaded =
        ModuleLoader.load(modules, lock, Map.of("maildir", Map.of("root", root.toString())))) {
      assertThat(loaded.capabilities())
          .containsOnlyKeys(
              "mail.move",
              "mail.list",
              "mail.folders",
              "mail.get",
              "mail.search",
              "mail.mark",
              "mail.delete",
              "mail.attachment");
      assertThat(loaded.sources()).extracting(s -> s.name()).containsExactly("maildir.inbox");
      assertThat(loaded.catalog().eventType("mail.received")).isPresent();
    }
  }
}
