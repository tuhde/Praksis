// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.ruleset.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityTypesTest {
  @TempDir Path dir;

  private static final String CONTACT =
      """
      format_version = 1
      id = "contact"
      title = "Contact"

      [[field]]
      name = "name"
      type = "string"
      required = true

      [[field]]
      name = "email"
      type = "string"
      unique = true
      sensitive = true
      """;

  private static List<Problem> problemsOf(String toml, String fileId) {
    List<Problem> problems = new ArrayList<>();
    Optional<EntityType> type = EntityTypes.parse(toml, fileId, problems);
    assertThat(type.isPresent()).isEqualTo(problems.isEmpty());
    return problems;
  }

  @Test
  void readsAType() {
    List<Problem> problems = new ArrayList<>();
    EntityType t = EntityTypes.parse(CONTACT, "contact", problems).orElseThrow();
    assertThat(problems).isEmpty();
    assertThat(t.id()).isEqualTo("contact");
    assertThat(t.fields()).extracting(EntityType.Field::name).containsExactly("name", "email");
    assertThat(t.field("name").orElseThrow().required()).isTrue();
    assertThat(t.field("email").orElseThrow().unique()).isTrue();
    assertThat(t.field("email").orElseThrow().sensitive()).isTrue();
    assertThat(t.field("email").orElseThrow().type()).isEqualTo(ValueType.STRING);
    assertThat(t.field("nope")).isEmpty();
  }

  @Test
  void hashIgnoresBasedOnAndChangesWithContent() {
    List<Problem> p = new ArrayList<>();
    EntityType a = EntityTypes.parse(CONTACT, "contact", p).orElseThrow();
    EntityType b = EntityTypes.parse(CONTACT + "\n", "contact", p).orElseThrow();
    EntityType c =
        EntityTypes.parse(
                CONTACT.replace(
                    "format_version = 1", "format_version = 1\nbased_on = \"sha256:x\""),
                "contact",
                p)
            .orElseThrow();
    EntityType d =
        EntityTypes.parse(CONTACT.replace("required = true", ""), "contact", p).orElseThrow();
    assertThat(a.hash()).startsWith("sha256:").isEqualTo(b.hash()).isEqualTo(c.hash());
    assertThat(c.basedOn()).contains("sha256:x");
    assertThat(d.hash()).isNotEqualTo(a.hash());
  }

  @Test
  void reportsWhatIsWrong() {
    assertThat(problemsOf(CONTACT.replace("format_version = 1", "format_version = 2"), "contact"))
        .anyMatch(p -> p.message().contains("format_version"));
    assertThat(problemsOf(CONTACT, "person"))
        .anyMatch(p -> p.message().contains("must be the file name 'person'"));
    assertThat(problemsOf(CONTACT.replace("\"contact\"", "\"Contact\""), "Contact"))
        .anyMatch(p -> p.message().contains("lower case"));
    assertThat(problemsOf(CONTACT.replace("title = \"Contact\"", ""), "contact"))
        .anyMatch(p -> p.message().contains("missing 'title'"));
    assertThat(problemsOf(CONTACT + "\nnote = 1\n", "contact"))
        .anyMatch(p -> p.message().contains("unknown key 'note'"));
    assertThat(problemsOf("format_version = 1\nid = \"x\"\ntitle = \"X\"\n", "x"))
        .anyMatch(p -> p.message().contains("at least one [[field]]"));
    assertThat(problemsOf("this is = = not toml", "x"))
        .anyMatch(p -> p.message().contains("not valid TOML"));
  }

  @Test
  void reportsWhatIsWrongWithFields() {
    String head = "format_version = 1\nid = \"x\"\ntitle = \"X\"\n";
    assertThat(problemsOf(head + "[[field]]\nname = \"id\"\ntype = \"string\"\n", "x"))
        .anyMatch(p -> p.message().contains("set by Praksis"));
    assertThat(
            problemsOf(
                head
                    + "[[field]]\nname = \"a\"\ntype = \"string\"\n[[field]]\nname = \"a\"\ntype = \"int\"\n",
                "x"))
        .anyMatch(p -> p.message().contains("declared twice"));
    assertThat(problemsOf(head + "[[field]]\nname = \"a\"\ntype = \"text\"\n", "x"))
        .anyMatch(p -> p.message().contains("unknown type 'text'"));
    assertThat(problemsOf(head + "[[field]]\nname = \"a\"\ntype = \"list\"\nunique = true\n", "x"))
        .anyMatch(p -> p.message().contains("cannot be unique"));
    assertThat(problemsOf(head + "[[field]]\nname = \"A b\"\ntype = \"int\"\n", "x"))
        .anyMatch(p -> p.message().contains("field name"));
    assertThat(problemsOf(head + "[[field]]\ntype = \"int\"\n", "x"))
        .anyMatch(p -> p.message().contains("missing 'name'"));
  }

  @Test
  void loadsTheTypesOfADirectory() throws IOException {
    Path entities = Files.createDirectories(dir.resolve("entities"));
    Files.writeString(entities.resolve("contact.toml"), CONTACT);
    Files.writeString(entities.resolve("broken.toml"), "format_version = 1\n");
    Files.writeString(entities.resolve("notes.txt"), "x");
    EntityTypes.Loaded loaded = EntityTypes.load(dir);
    assertThat(loaded.types()).containsOnlyKeys("contact");
    assertThat(loaded.problems())
        .anyMatch(p -> p.location().contains("broken.toml"))
        .anyMatch(p -> p.location().equals("entities/notes.txt"));
  }

  @Test
  void aDirectoryWithoutEntitiesHasNone() throws IOException {
    EntityTypes.Loaded loaded = EntityTypes.load(dir);
    assertThat(loaded.types()).isEmpty();
    assertThat(loaded.problems()).isEmpty();
  }
}
