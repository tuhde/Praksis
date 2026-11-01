// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.engine.ModuleDocs;
import it.uhde.praksis.engine.ModuleLoader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * The module pages under {@code docs/modules/} are generated. This fails when one differs from what
 * the module declares; run with {@code -Dpraksis.docs.update=true} to write them again.
 */
class ModuleReferenceTest {
  private static final Path DOCS = Path.of("..", "docs", "modules");

  private static void check(String page, String generated) throws IOException {
    Path file = DOCS.resolve(page);
    if (Boolean.getBoolean("praksis.docs.update")) {
      Files.writeString(file, generated);
    }
    assertThat(file)
        .as("%s is out of date; run the build with -Dpraksis.docs.update=true", file)
        .hasContent(generated);
  }

  @Test
  void theBuiltInModulePageIsCurrent() throws IOException {
    check("praksis.md", ModuleDocs.page(Builtins.descriptor()));
  }

  @Test
  void theEntityPageIsCurrent() throws IOException {
    check("entity.md", ModuleDocs.page(it.uhde.praksis.entity.EntityCapabilities.descriptor()));
  }

  @Test
  void theMaildirPageIsCurrent() throws Exception {
    var descriptor =
        ModuleLoader.readDescriptor(
            Path.of("..", "modules", "praksis-module-maildir", "module.toml"));
    check("maildir.md", ModuleDocs.page(descriptor));
  }
}
