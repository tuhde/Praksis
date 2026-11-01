// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** Types that refer to each other, against the runtime image: the checks and the three deletes. */
class EntityRelationsIT {
  private static void write(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static String id(String output) {
    Matcher m = Pattern.compile("(?m)^id: (\\S+)$").matcher(output);
    assertThat(m.find()).as(output).isTrue();
    return m.group(1);
  }

  @Test
  void referencesAreCheckedAndADeleteDoesWhatTheFieldsSay() throws Exception {
    try (Harness h = new Harness()) {
      Path types = h.home.resolve("ruleset/entities");
      write(
          types.resolve("author.toml"),
          "format_version = 1\nid = \"author\"\ntitle = \"Author\"\n[[field]]\nname = \"name\"\ntype = \"string\"\n");
      write(
          types.resolve("tag.toml"),
          "format_version = 1\nid = \"tag\"\ntitle = \"Tag\"\n[[field]]\nname = \"name\"\ntype = \"string\"\n");
      write(
          types.resolve("book.toml"),
          """
          format_version = 1
          id = "book"
          title = "Book"

          [[field]]
          name = "title"
          type = "string"

          [[field]]
          name = "author"
          type = "string"
          ref = "author"

          [[field]]
          name = "tags"
          type = "list"
          ref = "tag"
          on_delete = "set_null"
          """);
      write(
          types.resolve("note.toml"),
          """
          format_version = 1
          id = "note"
          title = "Note"

          [[field]]
          name = "text"
          type = "string"

          [[field]]
          name = "book"
          type = "string"
          ref = "book"
          on_delete = "cascade"
          required = true
          """);
      Process daemon = h.startDaemon();

      Harness.CliResult listed = h.cli("entities", "types");
      assertThat(listed.output())
          .contains("author (string), refers to author (on delete: restrict)")
          .contains("tags (list), refers to tag (on delete: set_null)")
          .contains("book (string), required, refers to book (on delete: cascade)");

      String author = id(h.cli("entities", "create", "author", "--set", "name=Ada").output());
      String tag = id(h.cli("entities", "create", "tag", "--set", "name=x").output());
      Harness.CliResult missing =
          h.cli("entities", "create", "book", "--set", "title=T", "--set", "author=nobody");
      assertThat(missing.exit()).isEqualTo(1);
      assertThat(missing.output())
          .contains("field 'author' refers to author 'nobody', which does not exist");

      String book =
          id(
              h.cli(
                      "entities",
                      "create",
                      "book",
                      "--set",
                      "title=T",
                      "--set",
                      "author=" + author,
                      "--json",
                      "{\"tags\": [\"" + tag + "\"]}")
                  .output());
      String note =
          id(
              h.cli("entities", "create", "note", "--set", "text=n", "--set", "book=" + book)
                  .output());

      // restrict: the author cannot go while the book has it
      Harness.CliResult restricted = h.cli("entities", "delete", "author", author);
      assertThat(restricted.exit()).isEqualTo(1);
      assertThat(restricted.output())
          .contains(
              "cannot delete author "
                  + author
                  + ": book "
                  + book
                  + " refers to it in the field 'author'");

      // set_null: the tag leaves the list of the book
      assertThat(h.cli("entities", "delete", "tag", tag).exit()).isZero();
      assertThat(h.cli("entities", "show", "book", book).output()).doesNotContain(tag);

      // cascade: the notes of the book go with it
      assertThat(h.cli("entities", "delete", "book", book).exit()).isZero();
      assertThat(h.cli("entities", "show", "note", note).exit()).isEqualTo(1);
      assertThat(h.cli("entities", "delete", "author", author).exit()).isZero();
      h.stop(daemon);
    }
  }

  @Test
  void aReferenceToATypeThatDoesNotExistStopsTheStartAndAVerifyReportsIt() throws Exception {
    try (Harness h = new Harness()) {
      write(
          h.home.resolve("ruleset/entities/book.toml"),
          "format_version = 1\nid = \"book\"\ntitle = \"Book\"\n[[field]]\nname = \"author\"\ntype = \"string\"\nref = \"author\"\n");
      Harness.CliResult verify = h.cli("verify");
      assertThat(verify.exit()).isEqualTo(1);
      assertThat(verify.output())
          .contains("field 'author' refers to the type 'author', which does not exist");
      Harness.CliResult start = h.cli("start");
      assertThat(start.exit()).isNotZero();
      assertThat(start.output()).contains("which does not exist");
    }
  }
}
