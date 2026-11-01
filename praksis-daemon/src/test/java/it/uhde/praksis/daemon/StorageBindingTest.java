// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.entity.EntityCatalog;
import it.uhde.praksis.entity.EntityType;
import it.uhde.praksis.entity.EntityTypes;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageBindingTest {
  @TempDir Path dir;

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

  /**
   * a, b, c, d are types; people = {a, b} holding staff = {b}; money = {b, c}; all = {a, b, c, d}.
   */
  private EntityCatalog catalog() throws Exception {
    Path entities = Files.createDirectories(dir.resolve("entities"));
    for (String t : List.of("a", "b", "c", "d")) {
      Files.writeString(entities.resolve(t + ".toml"), type(t));
    }
    Path sets = Files.createDirectories(dir.resolve("entity-sets"));
    Files.writeString(sets.resolve("people.toml"), set("people", "[\"a\"]", "[\"staff\"]"));
    Files.writeString(sets.resolve("staff.toml"), set("staff", "[\"b\"]", null));
    Files.writeString(sets.resolve("money.toml"), set("money", "[\"b\", \"c\"]", null));
    Files.writeString(sets.resolve("all.toml"), set("all", "[\"a\", \"b\", \"c\", \"d\"]", null));
    EntityCatalog.Loaded loaded = EntityCatalog.load(List.of(new EntityCatalog.Source("L", dir)));
    assertThat(loaded.problems()).isEmpty();
    return loaded.catalog();
  }

  private static EntitiesConfig config(Map<String, String> bind, Map<String, String> bindSet) {
    Map<String, EntitiesConfig.Storage> storages = new java.util.LinkedHashMap<>();
    for (String n : List.of("s1", "s2", "s3")) {
      storages.put(n, new EntitiesConfig.Storage(n, "json-file", Map.of()));
    }
    return new EntitiesConfig(storages, bind, bindSet);
  }

  @Test
  void nothingBoundMeansTheDaemonsDatabase() throws Exception {
    assertThat(StorageBinding.resolve(config(Map.of(), Map.of()), catalog())).isEmpty();
  }

  @Test
  void aSetBindsAllItsTypesAlsoThoseOfSetsInsideIt() throws Exception {
    assertThat(StorageBinding.resolve(config(Map.of(), Map.of("people", "s1")), catalog()))
        .containsOnlyKeys("a", "b")
        .containsEntry("a", "s1")
        .containsEntry("b", "s1");
  }

  @Test
  void aTypesOwnBindingWinsOverEverySet() throws Exception {
    assertThat(
            StorageBinding.resolve(
                config(Map.of("b", "s3"), Map.of("people", "s1", "money", "s2")), catalog()))
        .containsEntry("a", "s1")
        .containsEntry("b", "s3")
        .containsEntry("c", "s2")
        .doesNotContainKey("d");
  }

  @Test
  void theSmallestSetThatIsBoundWins() throws Exception {
    // b is in people (2 types), staff (1), money (2) and all (4): staff is the smallest bound set
    assertThat(
            StorageBinding.resolve(config(Map.of(), Map.of("all", "s1", "staff", "s2")), catalog()))
        .containsEntry("a", "s1")
        .containsEntry("b", "s2")
        .containsEntry("c", "s1")
        .containsEntry("d", "s1");
  }

  @Test
  void equalSetsThatAgreeAreFineAndThatDisagreeAreRefusedUntilTheTypeIsBound() throws Exception {
    EntityCatalog catalog = catalog();
    // b is in people and money (both of 2 types)
    assertThat(
            StorageBinding.resolve(
                config(Map.of(), Map.of("people", "s1", "money", "s1")), catalog))
        .containsEntry("b", "s1");
    EntitiesConfig clash = config(Map.of(), Map.of("people", "s1", "money", "s2"));
    assertThatThrownBy(() -> StorageBinding.resolve(clash, catalog))
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("the type 'b' is in the sets [money, people]")
        .hasMessageContaining("equally small and bound to different storages [s1, s2]")
        .hasMessageContaining("bind the type itself");
    assertThat(
            StorageBinding.resolve(
                config(Map.of("b", "s3"), Map.of("people", "s1", "money", "s2")), catalog))
        .containsEntry("b", "s3");
  }

  @Test
  void unknownTypesAndSetsAreNamed() throws Exception {
    EntityCatalog catalog = catalog();
    assertThatThrownBy(() -> StorageBinding.resolve(config(Map.of("zzz", "s1"), Map.of()), catalog))
        .hasMessageContaining(
            "[entities.bind]: there is no entity type 'zzz' (types: [a, b, c, d])");
    assertThatThrownBy(
            () -> StorageBinding.resolve(config(Map.of(), Map.of("ghosts", "s1")), catalog))
        .hasMessageContaining("[entities.bind_set]: there is no entity set 'ghosts'")
        .hasMessageContaining("sets: [all, money, people, staff]");
    assertThat(new ArrayList<EntityType>()).isEmpty();
    assertThat(EntityTypes.class).isNotNull();
  }
}
