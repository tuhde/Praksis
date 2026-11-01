// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.ruleset.Problem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

  // --- sets ---

  private static String type(String id) {
    return "format_version = 1\nid = \""
        + id
        + "\"\ntitle = \""
        + id
        + "\"\n[[field]]\nname = \"name\"\ntype = \"string\"\n";
  }

  private static String set(String id, String types, String sets) {
    return "format_version = 1\nid = \""
        + id
        + "\"\ntitle = \""
        + id
        + "\"\n"
        + (types == null ? "" : "types = " + types + "\n")
        + (sets == null ? "" : "sets = " + sets + "\n");
  }

  private Path layerWithSets(String name, Map<String, String> types, Map<String, String> sets)
      throws IOException {
    Path root = dir.resolve(name);
    for (var e : types.entrySet()) {
      Files.createDirectories(root.resolve("entities"));
      Files.writeString(root.resolve("entities").resolve(e.getKey() + ".toml"), e.getValue());
    }
    for (var e : sets.entrySet()) {
      Files.createDirectories(root.resolve("entity-sets"));
      Files.writeString(root.resolve("entity-sets").resolve(e.getKey() + ".toml"), e.getValue());
    }
    return root;
  }

  @Test
  void setsGroupTypesAndNestToAnyDepth() throws IOException {
    Path layer =
        layerWithSets(
            "s1",
            Map.of(
                "contact", type("contact"), "author", type("author"), "invoice", type("invoice")),
            Map.of(
                "people", set("people", "[\"contact\"]", "[\"staff\"]"),
                "staff", set("staff", "[\"author\"]", null),
                "money", set("money", "[\"invoice\", \"contact\"]", null)));
    EntityCatalog.Loaded loaded = load(layer);
    assertThat(loaded.problems()).isEmpty();
    EntityCatalog c = loaded.catalog();
    assertThat(c.members("people")).containsExactly("author", "contact");
    assertThat(c.members("staff")).containsExactly("author");
    assertThat(c.members("nope")).isEmpty();
    // a type is in as many sets as hold it, also through other sets
    assertThat(c.setsOf("contact")).containsExactly("money", "people");
    assertThat(c.setsOf("author")).containsExactly("people", "staff");
    assertThat(c.setsOf("invoice")).containsExactly("money");
    assertThat(c.allMembers()).containsOnlyKeys("people", "staff", "money");
    assertThat(c.setOwner("people")).contains("L0");
  }

  @Test
  void aSetWithAMemberThatDoesNotExistOrASetInsideItselfIsRefused() throws IOException {
    Path layer =
        layerWithSets(
            "s2",
            Map.of("contact", type("contact")),
            Map.of(
                "a", set("a", "[\"contact\", \"ghost\"]", "[\"phantom\"]"),
                "x", set("x", null, "[\"y\"]"),
                "y", set("y", null, "[\"z\"]"),
                "z", set("z", "[\"contact\"]", "[\"x\"]"),
                "self", set("self", "[\"contact\"]", "[\"self\"]")));
    List<String> messages = load(layer).problems().stream().map(Problem::toString).toList();
    assertThat(messages)
        .anyMatch(
            m ->
                m.contains("entity-sets/a.toml")
                    && m.contains("'ghost', which is not an entity type"))
        .anyMatch(
            m ->
                m.contains("entity-sets/a.toml")
                    && m.contains("'phantom', which is not an entity set"))
        .anyMatch(
            m ->
                m.contains("entity-sets/x.toml") && m.contains("contain each other: x > y > z > x"))
        .anyMatch(
            m -> m.contains("entity-sets/self.toml") && m.contains("cannot be inside itself"));
  }

  @Test
  void setsAreNotNestedWithoutLimit() throws IOException {
    java.util.Map<String, String> sets = new java.util.LinkedHashMap<>();
    for (int i = 0; i < 10; i++) {
      sets.put(
          "s" + i,
          set("s" + i, i == 9 ? "[\"contact\"]" : null, i == 9 ? null : "[\"s" + (i + 1) + "\"]"));
    }
    EntityCatalog.Loaded loaded =
        load(layerWithSets("s3", Map.of("contact", type("contact")), sets));
    assertThat(loaded.problems()).anyMatch(p -> p.message().contains("nested more than 8 deep"));
  }

  @Test
  void aSetIsReplacedByNamingTheHashAndAMissingOrStaleHashIsRefused() throws IOException {
    Map<String, String> types = Map.of("contact", type("contact"), "author", type("author"));
    String base = set("people", "[\"contact\"]", null);
    Path below = layerWithSets("s4", types, Map.of("people", base));
    String hash = EntityTypes.parseSet(base, "people", new ArrayList<>()).orElseThrow().hash();
    String wider = set("people", "[\"contact\", \"author\"]", null);

    EntityCatalog.Loaded plain =
        load(below, layerWithSets("s5", Map.of(), Map.of("people", wider)));
    assertThat(plain.problems())
        .anyMatch(p -> p.message().contains("set 'people' already exists (added by 'L0')"));
    assertThat(plain.catalog().members("people")).containsExactly("contact");

    EntityCatalog.Loaded ok =
        load(
            below,
            layerWithSets(
                "s6",
                Map.of(),
                Map.of(
                    "people",
                    wider.replace(
                        "format_version = 1", "format_version = 1\nbased_on = \"" + hash + "\""))));
    assertThat(ok.problems()).isEmpty();
    assertThat(ok.catalog().members("people")).containsExactly("author", "contact");
    assertThat(ok.catalog().setOwner("people")).contains("L1");

    EntityCatalog.Loaded stale =
        load(
            below,
            layerWithSets(
                "s7",
                Map.of(),
                Map.of(
                    "people",
                    wider.replace(
                        "format_version = 1", "format_version = 1\nbased_on = \"sha256:old\""))));
    assertThat(stale.problems()).anyMatch(p -> p.message().contains("has changed since"));
    assertThat(
            load(layerWithSets(
                    "s8",
                    types,
                    Map.of(
                        "people",
                        wider.replace(
                            "format_version = 1", "format_version = 1\nbased_on = \"sha256:x\""))))
                .problems())
        .anyMatch(p -> p.message().contains("there is no set 'people' below"));
  }

  @Test
  void theFileOfASetIsChecked() {
    record Bad(String toml, String message) {}
    for (Bad bad :
        List.of(
            new Bad(
                "format_version = 2\nid = \"s\"\ntitle = \"S\"\ntypes = [\"a\"]\n",
                "format_version"),
            new Bad(set("other", "[\"a\"]", null), "must be the file name 's'"),
            new Bad("format_version = 1\nid = \"s\"\ntitle = \"S\"\n", "needs at least one entry"),
            new Bad(set("s", "[\"A b\"]", null), "'A b', which is not an id"),
            new Bad(set("s", "[\"a\", \"a\"]", null), "'a' twice"),
            new Bad(set("s", "[\"a\"]", null) + "colour = 1\n", "unknown key 'colour'"))) {
      List<Problem> problems = new ArrayList<>();
      assertThat(EntityTypes.parseSet(bad.toml(), "s", problems)).isEmpty();
      assertThat(problems).anyMatch(p -> p.message().contains(bad.message()));
    }
  }
}
