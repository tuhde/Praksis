// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.ruleset.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityCatalogTest {
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
      name = "iban"
      type = "string"
      sensitive = true
      """;

  private Path layer(String name, String... files) throws IOException {
    Path entities = Files.createDirectories(dir.resolve(name).resolve("entities"));
    for (int i = 0; i < files.length; i += 2) {
      Files.writeString(entities.resolve(files[i] + ".toml"), files[i + 1]);
    }
    return dir.resolve(name);
  }

  private static EntityType parse(String toml) {
    return EntityTypes.parse(toml, "contact", new ArrayList<>()).orElseThrow();
  }

  private static EntityCatalog.Loaded load(Path... layers) throws IOException {
    List<EntityCatalog.Source> sources = new ArrayList<>();
    for (int i = 0; i < layers.length; i++) {
      sources.add(new EntityCatalog.Source("L" + i, layers[i]));
    }
    return EntityCatalog.load(sources);
  }

  private String replacing(String body, String below) {
    return body.replace(
        "format_version = 1", "format_version = 1\nbased_on = \"" + parse(below).hash() + "\"");
  }

  @Test
  void aLayerAddsTypesAndKnowsItsOwner() throws IOException {
    EntityCatalog.Loaded loaded = load(layer("a", "contact", CONTACT));
    assertThat(loaded.problems()).isEmpty();
    assertThat(loaded.catalog().get("contact")).isPresent();
    assertThat(loaded.catalog().owner("contact")).contains("L0");
    assertThat(loaded.catalog().owner("nope")).isEmpty();
  }

  @Test
  void aReplacementNeedsTheHashOfTheTypeBelow() throws IOException {
    String wider = CONTACT + "\n[[field]]\nname = \"phone\"\ntype = \"string\"\n";
    // without based_on
    EntityCatalog.Loaded plain = load(layer("a", "contact", CONTACT), layer("b", "contact", wider));
    assertThat(plain.problems())
        .anyMatch(p -> p.message().contains("already exists (added by 'L0')"));
    assertThat(plain.problems().get(0).location()).isEqualTo("layer 'L1': entities/contact.toml");
    // with the right hash
    EntityCatalog.Loaded ok =
        load(layer("c", "contact", CONTACT), layer("d", "contact", replacing(wider, CONTACT)));
    assertThat(ok.problems()).isEmpty();
    assertThat(ok.catalog().get("contact").orElseThrow().field("phone")).isPresent();
    assertThat(ok.catalog().owner("contact")).contains("L1");
    // with a stale hash
    EntityCatalog.Loaded stale =
        load(
            layer("e", "contact", CONTACT),
            layer(
                "f",
                "contact",
                wider.replace(
                    "format_version = 1", "format_version = 1\nbased_on = \"sha256:old\"")));
    assertThat(stale.problems()).anyMatch(p -> p.message().contains("has changed since"));
    assertThat(stale.catalog().get("contact").orElseThrow().field("phone")).isEmpty();
    // based_on without a type below
    EntityCatalog.Loaded none = load(layer("g", "contact", replacing(wider, CONTACT)));
    assertThat(none.problems())
        .anyMatch(p -> p.message().contains("there is no type 'contact' below"));
  }

  @Test
  void aReplacementMayNotBreakStoredEntities() throws IOException {
    record Case(String name, String replacement, String message) {}
    String head = "format_version = 1\nid = \"contact\"\ntitle = \"Contact\"\n";
    List<Case> cases =
        List.of(
            new Case(
                "removes",
                head + "[[field]]\nname = \"name\"\ntype = \"string\"\nrequired = true\n",
                "field 'iban' is removed"),
            new Case(
                "retypes",
                head
                    + "[[field]]\nname = \"name\"\ntype = \"int\"\n"
                    + "[[field]]\nname = \"iban\"\ntype = \"string\"\nsensitive = true\n",
                "changes its type from string to int"),
            new Case(
                "new required",
                CONTACT + "[[field]]\nname = \"x\"\ntype = \"string\"\nrequired = true\n",
                "the new field 'x' is required"),
            new Case(
                "becomes required",
                CONTACT.replace(
                    "name = \"iban\"\ntype = \"string\"",
                    "name = \"iban\"\ntype = \"string\"\nrequired = true"),
                "field 'iban' becomes required"),
            new Case(
                "becomes unique",
                CONTACT.replace(
                    "name = \"name\"\ntype = \"string\"",
                    "name = \"name\"\ntype = \"string\"\nunique = true"),
                "field 'name' becomes unique"),
            new Case(
                "loses sensitive",
                CONTACT.replace("sensitive = true", ""),
                "field 'iban' is no longer sensitive"));
    int i = 0;
    for (Case c : cases) {
      String below = "base" + i;
      String above = "top" + i++;
      EntityCatalog.Loaded loaded =
          load(
              layer(below, "contact", CONTACT),
              layer(above, "contact", replacing(c.replacement(), CONTACT)));
      assertThat(loaded.problems())
          .as(c.name())
          .anyMatch(
              p ->
                  p.message().contains("breaks stored entities")
                      && p.message().contains(c.message()));
      assertThat(loaded.catalog().get("contact").orElseThrow().hash())
          .as(c.name())
          .isEqualTo(parse(CONTACT).hash());
    }
    // what is fine: more optional fields, less strict, more sensitive
    String relaxed =
        CONTACT.replace("required = true", "")
            + "\n[[field]]\nname = \"note\"\ntype = \"string\"\nunique = true\n";
    assertThat(
            load(layer("x", "contact", CONTACT), layer("y", "contact", replacing(relaxed, CONTACT)))
                .problems())
        .isEmpty();
  }

  @Test
  void problemsOfFilesAreNamedByLayer() throws IOException {
    EntityCatalog.Loaded loaded = load(layer("a", "contact", "format_version = 1\n"));
    List<Problem> problems = loaded.problems();
    assertThat(problems).isNotEmpty();
    assertThat(problems)
        .allMatch(p -> p.location().startsWith("layer 'L0': entities/contact.toml"));
    assertThat(loaded.catalog().types()).isEmpty();
  }
}
