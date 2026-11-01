// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.ruleset.Problem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The entity types in use. They come from the {@code entities/} directories of modules and layers,
 * lowest first; a type of a later directory replaces one of the same id. (Checking {@code based_on}
 * and the rights to define a type come with the layering of types.)
 */
public final class EntityCatalog {
  private final Map<String, EntityType> types;

  public EntityCatalog(Map<String, EntityType> types) {
    this.types = Map.copyOf(types);
  }

  /** A catalog without types. */
  public static EntityCatalog empty() {
    return new EntityCatalog(Map.of());
  }

  /**
   * What was read from directories.
   *
   * @param catalog the valid types
   * @param problems everything that is wrong in the files
   */
  public record Loaded(EntityCatalog catalog, List<Problem> problems) {}

  /** Reads the types of the directories in order. */
  public static Loaded load(List<Path> directories) throws IOException {
    Map<String, EntityType> all = new LinkedHashMap<>();
    List<Problem> problems = new ArrayList<>();
    for (Path directory : directories) {
      EntityTypes.Loaded loaded = EntityTypes.load(directory);
      all.putAll(loaded.types());
      problems.addAll(loaded.problems());
    }
    return new Loaded(new EntityCatalog(all), problems);
  }

  public Optional<EntityType> get(String id) {
    return Optional.ofNullable(types.get(id));
  }

  public Map<String, EntityType> types() {
    return types;
  }
}
