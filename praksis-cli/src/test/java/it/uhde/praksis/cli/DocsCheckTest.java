// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DocsCheckTest {
  @TempDir Path dir;

  private void write(String name, String text) throws IOException {
    Path file = dir.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void theDocumentationOfTheRepositoryIsInOrder() throws IOException {
    assertThat(DocsCheck.problems(Path.of("..", "docs"), Path.of("..", "modules"))).isEmpty();
  }

  @Test
  void findsWhatIsWrong() throws IOException {
    write("index.md", "# Docs\n\n- [A](a.md)\n- [Gone](gone.md)\n");
    write("a.md", "no title\n\n[ok](b.md#x) [bad](nope.md) [web](https://example.org)\n");
    write("b.md", "# B\n\n```\n[in code](missing.md)\n```\n");
    assertThat(DocsCheck.problems(dir, null))
        .containsExactlyInAnyOrder(
            "index.md lists gone.md, which does not exist",
            "b.md is not listed in index.md",
            "a.md does not start with a title (# ...)",
            "a.md links to nope.md, which does not exist");
  }

  @Test
  void findsWhatTheHelpCannotShow() throws IOException {
    write("index.md", "# Docs\n\n- [A](a.md)\n");
    write("p.png", "");
    write(
        "a.md",
        "# A\n\n<!-- fine -->\nText with `<code>` and <b>bold</b>.\n\n![pic](p.png)\n\n"
            + "- item\n  - nested\n\n    <div>in code</div>\n");
    assertThat(DocsCheck.problems(dir, null))
        .containsExactlyInAnyOrder(
            "a.md has HTML, which the help does not show: Text with `<code>` and <b>bold</b>.",
            "a.md has an image, which the help does not show: ![pic](p.png)",
            "a.md has a nested list, which the help does not show: - nested");
  }

  @Test
  void aModuleWithoutAPageIsReported() throws IOException {
    write("index.md", "# Docs\n");
    write("mods/mail/module.toml", "format_version = 1\nname = \"mail\"\n");
    assertThat(DocsCheck.problems(dir, dir.resolve("mods")))
        .containsExactlyInAnyOrder(
            "module praksis has no page modules/praksis.md in index.md",
            "module mail has no page modules/mail.md in index.md");
  }
}
