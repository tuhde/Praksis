// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Query;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.store.Store;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A replacement that comes with steps, and the first start with it. */
class EntityMigrationTest {
  @TempDir Path dir;
  private Store store;

  private static final String V1 =
      """
      format_version = 1
      id = "contact"
      title = "Contact"
      [[field]]
      name = "name"
      type = "string"
      [[field]]
      name = "phone"
      type = "string"
      [[field]]
      name = "fax"
      type = "string"
      [[field]]
      name = "age"
      type = "string"
      [[field]]
      name = "email"
      type = "string"
      """;

  /** v2: phone is mobile, fax is gone, age is a number, a country is required, email is unique. */
  private static final String V2_BODY =
      """
      [[field]]
      name = "name"
      type = "string"
      [[field]]
      name = "mobile"
      type = "string"
      [[field]]
      name = "age"
      type = "int"
      [[field]]
      name = "email"
      type = "string"
      unique = true
      [[field]]
      name = "country"
      type = "string"
      required = true

      [[migration]]
      rename = { from = "phone", to = "mobile" }
      [[migration]]
      drop = "fax"
      [[migration]]
      convert = { field = "age", to = "int" }
      [[migration]]
      default = { field = "country", value = "DE" }
      [[migration]]
      check = "email"
      """;

  private static String v2(String body) {
    return "format_version = 1\nid = \"contact\"\ntitle = \"Contact\"\nbased_on = \""
        + EntityTypes.parse(V1, "contact", new ArrayList<>()).orElseThrow().hash()
        + "\"\n"
        + body;
  }

  @BeforeEach
  void open() throws Exception {
    store = Store.open(dir.resolve("praksis.db"));
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  private Path layer(String name, String type) throws IOException {
    Path entities = Files.createDirectories(dir.resolve(name).resolve("entities"));
    Files.writeString(entities.resolve("contact.toml"), type);
    return dir.resolve(name);
  }

  private EntityCatalog.Loaded load(Path... layers) throws IOException {
    List<EntityCatalog.Source> sources = new ArrayList<>();
    for (int i = 0; i < layers.length; i++) {
      sources.add(new EntityCatalog.Source("L" + i, layers[i]));
    }
    return EntityCatalog.load(sources);
  }

  @Test
  void theStepsAreReadAndWrongOnesAreNamed() {
    record Bad(String body, String message) {}
    String head =
        "format_version = 1\nid = \"contact\"\ntitle = \"C\"\nbased_on = \"sha256:x\"\n[[field]]\nname = \"a\"\ntype = \"string\"\n";
    for (Bad bad :
        List.of(
            new Bad("[[migration]]\nrename = { from = \"a\" }\n", "missing 'to'"),
            new Bad("[[migration]]\ndrop = 5\n", "'drop' must be the name of a field"),
            new Bad("[[migration]]\ndrop = \"a\"\ncheck = \"a\"\n", "exactly one of"),
            new Bad(
                "[[migration]]\nconvert = { field = \"a\", to = \"text\" }\n",
                "unknown type 'text'"),
            new Bad("[[migration]]\ndefault = { field = \"a\" }\n", "missing 'value'"),
            new Bad("[[migration]]\nshrink = \"a\"\n", "unknown key 'shrink'"))) {
      List<Problem> problems = new ArrayList<>();
      assertThat(EntityTypes.parse(head + bad.body(), "contact", problems)).isEmpty();
      assertThat(problems).as(bad.message()).anyMatch(p -> p.message().contains(bad.message()));
    }
    List<Problem> problems = new ArrayList<>();
    assertThat(
            EntityTypes.parse(
                "format_version = 1\nid = \"contact\"\ntitle = \"C\"\n[[field]]\nname = \"a\"\ntype = \"string\"\n[[migration]]\ndrop = \"b\"\n",
                "contact",
                problems))
        .isEmpty();
    assertThat(problems)
        .anyMatch(p -> p.message().contains("need to know which version they start from"));
    EntityType ok = EntityTypes.parse(v2(V2_BODY), "contact", new ArrayList<>()).orElseThrow();
    assertThat(ok.migrations())
        .containsExactly(
            new EntityMigration.Rename("phone", "mobile"),
            new EntityMigration.Drop("fax"),
            new EntityMigration.Convert("age", it.uhde.praksis.capability.ValueType.INT),
            new EntityMigration.Default("country", "DE"),
            new EntityMigration.Check("email"));
  }

  @Test
  void aReplacementThatBreaksStoredEntitiesNeedsStepsThatCoverIt() throws IOException {
    Path base = layer("a", V1);
    // without steps it is refused
    String bare = V2_BODY.substring(0, V2_BODY.indexOf("[[migration]]"));
    assertThat(load(base, layer("b", v2(bare))).problems())
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("field 'phone' is removed"))
        .anyMatch(m -> m.contains("field 'age' changes its type from string to int"))
        .anyMatch(m -> m.contains("the new field 'country' is required"));
    // with all of them it is accepted
    EntityCatalog.Loaded ok = load(base, layer("c", v2(V2_BODY)));
    assertThat(ok.problems()).isEmpty();
    assertThat(ok.catalog().versions("contact")).hasSize(2);
    assertThat(ok.catalog().versions("contact").get(0).steps()).isEmpty();
    assertThat(ok.catalog().versions("contact").get(1).steps()).hasSize(5);
    // missing one step leaves one thing wrong
    String noDefault =
        V2_BODY.replace("[[migration]]\ndefault = { field = \"country\", value = \"DE\" }\n", "");
    assertThat(load(base, layer("d", v2(noDefault))).problems())
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("the new field 'country' is required"));
    String noCheck = V2_BODY.replace("[[migration]]\ncheck = \"email\"\n", "");
    assertThat(load(base, layer("e", v2(noCheck))).problems())
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("field 'email' becomes unique"));
  }

  @Test
  void aStepThatCannotBeDoneIsSaid() throws IOException {
    Path base = layer("a", V1);
    record Case(String steps, String message) {}
    String fields = V2_BODY.substring(0, V2_BODY.indexOf("[[migration]]"));
    for (Case c :
        List.of(
            new Case(
                "[[migration]]\nrename = { from = \"nope\", to = \"x\" }\n",
                "rename: there is no field 'nope'"),
            new Case(
                "[[migration]]\nrename = { from = \"phone\", to = \"name\" }\n",
                "rename: the field 'name' exists already"),
            new Case("[[migration]]\ndrop = \"nope\"\n", "drop: there is no field 'nope'"),
            new Case(
                "[[migration]]\nconvert = { field = \"name\", to = \"timestamp\" }\n[[migration]]\nconvert = { field = \"name\", to = \"bytes\" }\n",
                "convert: a timestamp cannot be converted to bytes"),
            new Case(
                "[[migration]]\ndefault = { field = \"ghost\", value = 1 }\n",
                "default: the type has no field 'ghost'"),
            new Case(
                "[[migration]]\ndefault = { field = \"country\", value = 5 }\n",
                "default: field 'country' must be of type string"),
            new Case("[[migration]]\ncheck = \"ghost\"\n", "check: there is no field 'ghost'"))) {
      EntityCatalog.Loaded loaded =
          load(base, layer("x" + Math.abs(c.message().hashCode()), v2(fields + c.steps())));
      assertThat(loaded.problems())
          .as(c.message())
          .extracting(Problem::message)
          .anyMatch(m -> m.contains(c.message()));
    }
  }

  private EntityService service(EntityCatalog catalog) {
    return new EntityService(store, catalog, Clock.systemUTC());
  }

  @Test
  void theFirstStartWithTheReplacementRewritesTheEntitiesOnceAndNotesTheVersion() throws Exception {
    Path base = layer("a", V1);
    EntityCatalog v1 = load(base).catalog();
    EntityService old = service(v1);
    // the daemon notes the version at every start
    EntityMigrations.run(old, v1, store, Clock.systemUTC());
    String ada =
        (String)
            old.create(
                    "contact",
                    Map.of("name", "Ada", "phone", "123", "fax", "9", "age", "41", "email", "a@x"),
                    null,
                    false)
                .get("id");
    String bo =
        (String) old.create("contact", Map.of("name", "Bo", "age", "7"), null, false).get("id");

    EntityCatalog.Loaded loaded = load(base, layer("b", v2(V2_BODY)));
    assertThat(loaded.problems()).isEmpty();
    EntityService now = service(loaded.catalog());
    Map<String, Integer> done =
        EntityMigrations.run(now, loaded.catalog(), store, Clock.systemUTC());
    assertThat(done).containsEntry("contact", 2);
    Map<String, Object> a = now.read("contact", ada).orElseThrow();
    assertThat(a)
        .containsEntry("mobile", "123")
        .containsEntry("age", 41L)
        .containsEntry("country", "DE")
        .containsEntry("email", "a@x")
        .doesNotContainKeys("phone", "fax");
    assertThat(now.read("contact", bo).orElseThrow())
        .containsEntry("age", 7L)
        .containsEntry("country", "DE")
        .doesNotContainKey("mobile");
    assertThat(a).containsEntry("created_by", "user"); // who made it is kept
    // the version is noted; a second start does nothing
    assertThat(EntityMigrations.run(now, loaded.catalog(), store, Clock.systemUTC())).isEmpty();
    assertThat(store.meta("entity-type-version:contact"))
        .contains(loaded.catalog().versions("contact").get(1).hash());
    assertThat(store.recentAudit(10)).anyMatch(e -> e.subject().equals("entity.migrate"));
  }

  @Test
  void anEntityThatDoesNotFitStopsTheMigrationAndChangesNothing() throws Exception {
    Path base = layer("a", V1);
    EntityCatalog v1 = load(base).catalog();
    EntityService old = service(v1);
    EntityMigrations.run(old, v1, store, Clock.systemUTC());
    old.create("contact", Map.of("name", "Ada", "age", "41"), null, false);
    String bad =
        (String) old.create("contact", Map.of("name", "Bo", "age", "old"), null, false).get("id");
    EntityCatalog.Loaded loaded = load(base, layer("b", v2(V2_BODY)));
    EntityService now = service(loaded.catalog());
    assertThatThrownBy(() -> EntityMigrations.run(now, loaded.catalog(), store, Clock.systemUTC()))
        .isInstanceOf(EntityException.class)
        .hasMessageContaining("entity '" + bad + "'")
        .hasMessageContaining("the value old of 'age' cannot be a int");
    // nothing was written and the old version is still the noted one
    assertThat(old.query("contact", Query.all()).items())
        .allSatisfy(e -> assertThat(e).doesNotContainKey("country"));
    assertThat(store.meta("entity-type-version:contact"))
        .contains(v1.versions("contact").get(0).hash());

    // two entities that now share a unique value are caught too
    old.update("contact", bad, Map.of("age", "8", "email", "dup"), false);
    old.create("contact", Map.of("name", "Cy", "age", "9", "email", "dup"), null, false);
    assertThatThrownBy(() -> EntityMigrations.run(now, loaded.catalog(), store, Clock.systemUTC()))
        .hasMessageContaining("has the same email = dup");
  }

  @Test
  void aReplacementWithoutStepsIsOnlyNotedAndAMissingNoteIsTakenAsCurrent() throws Exception {
    Path base = layer("a", V1);
    EntityCatalog v1 = load(base).catalog();
    // no note yet, entities exist: the type is taken to be current
    EntityService old = service(v1);
    old.create("contact", Map.of("name", "Ada"), null, false);
    assertThat(EntityMigrations.run(old, v1, store, Clock.systemUTC())).isEmpty();
    String wider =
        V1.replace(
                "format_version = 1",
                "format_version = 1\nbased_on = \"" + v1.versions("contact").get(0).hash() + "\"")
            + "[[field]]\nname = \"note\"\ntype = \"string\"\n";
    EntityCatalog.Loaded loaded = load(base, layer("b", wider));
    assertThat(loaded.problems()).isEmpty();
    assertThat(
            EntityMigrations.run(
                service(loaded.catalog()), loaded.catalog(), store, Clock.systemUTC()))
        .isEmpty();
    assertThat(store.meta("entity-type-version:contact"))
        .contains(loaded.catalog().versions("contact").get(1).hash());
  }

  @Test
  void aTypeChangedInPlaceIsMigratedIfItsStepsNameTheVersionAndOtherwiseLeftAlone()
      throws Exception {
    EntityCatalog v1 = load(layer("a", V1)).catalog();
    EntityService old = service(v1);
    EntityMigrations.run(old, v1, store, Clock.systemUTC());
    String ada =
        (String)
            old.create("contact", Map.of("name", "Ada", "phone", "1", "age", "41"), null, false)
                .get("id");
    String hash = v1.versions("contact").get(0).hash();

    // the same layer, the file edited: steps that say which version they start from
    String edited =
        "format_version = 1\nid = \"contact\"\ntitle = \"Contact\"\nmigrates_from = \""
            + hash
            + "\"\n[[field]]\nname = \"name\"\ntype = \"string\"\n"
            + "[[field]]\nname = \"mobile\"\ntype = \"string\"\n"
            + "[[field]]\nname = \"age\"\ntype = \"int\"\n"
            + "[[migration]]\nrename = { from = \"phone\", to = \"mobile\" }\n"
            + "[[migration]]\nconvert = { field = \"age\", to = \"int\" }\n";
    EntityCatalog.Loaded loaded = load(layer("b", edited));
    assertThat(loaded.problems()).isEmpty();
    assertThat(
            EntityMigrations.run(
                service(loaded.catalog()), loaded.catalog(), store, Clock.systemUTC()))
        .containsEntry("contact", 1);
    assertThat(service(loaded.catalog()).read("contact", ada).orElseThrow())
        .containsEntry("mobile", "1")
        .containsEntry("age", 41L)
        .doesNotContainKey("phone");

    // steps whose version is not the one noted are not run; a change without steps is only noted
    EntityCatalog other = load(layer("c", edited.replace(hash, "sha256:other"))).catalog();
    store.setMeta("entity-type-version:contact", "sha256:something-else");
    assertThat(EntityMigrations.run(service(other), other, store, Clock.systemUTC())).isEmpty();
    assertThat(store.meta("entity-type-version:contact"))
        .contains(other.versions("contact").get(0).hash());

    // a check of a field that the type does not have is a problem of the layer
    assertThat(load(layer("e", edited + "[[migration]]\ncheck = \"ghost\"\n")).problems())
        .extracting(Problem::message)
        .anyMatch(m -> m.contains("check: the type has no field 'ghost'"));
  }

  /** A storage that is not the daemon's database and keeps nothing. */
  private static final class Elsewhere implements it.uhde.praksis.capability.EntityStorage {
    @Override
    public String name() {
      return "elsewhere";
    }

    @Override
    public void open(List<it.uhde.praksis.capability.StorageType> types) {}

    @Override
    public java.util.Optional<it.uhde.praksis.capability.StoredEntity> get(String t, String id) {
      return java.util.Optional.empty();
    }

    @Override
    public void insert(String t, it.uhde.praksis.capability.StoredEntity e) {}

    @Override
    public void replace(String t, it.uhde.praksis.capability.StoredEntity e) {}

    @Override
    public boolean delete(String t, String id) {
      return false;
    }

    @Override
    public Page query(String t, Query q) {
      return new Page(List.of(), 0);
    }
  }

  @Test
  void aTypeKeptInAnotherStorageIsNotMigratedAndTheReasonIsSaid() throws Exception {
    Path base = layer("a", V1);
    EntityCatalog.Loaded loaded = load(base, layer("b", v2(V2_BODY)));
    EntityService service =
        new EntityService(
            store,
            loaded.catalog(),
            Clock.systemUTC(),
            e -> {},
            new EntityStorages(new Elsewhere(), Map.of(), List.of()));
    String old = loaded.catalog().versions("contact").get(0).hash();
    store.setMeta("entity-type-version:contact", old);
    assertThatThrownBy(
            () -> EntityMigrations.run(service, loaded.catalog(), store, Clock.systemUTC()))
        .hasMessageContaining("kept in the storage 'elsewhere', which cannot migrate them")
        .hasMessageContaining("only the daemon's own database can");
    assertThat(store.meta("entity-type-version:contact")).contains(old);
  }
}
