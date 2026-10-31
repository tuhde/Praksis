// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * {@code docs/reference/cli.md} is generated. This fails when it differs from the command
 * definitions; run with {@code -Dpraksis.docs.update=true} to write it again.
 */
class CliReferenceTest {
  @Test
  void theCommandReferenceIsCurrent() throws IOException {
    Path file = Path.of("..", "docs", "reference", "cli.md");
    String generated = CliDocs.page();
    if (Boolean.getBoolean("praksis.docs.update")) {
      Files.writeString(file, generated);
    }
    assertThat(file)
        .as("%s is out of date; run the build with -Dpraksis.docs.update=true", file)
        .hasContent(generated);
  }
}
