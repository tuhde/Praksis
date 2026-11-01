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
 * The entity types in use. They come from the {@code entities/} directories of the layers, lowest
 * first. A type is added by the first layer that has it. A higher layer may replace it by naming
 * the hash of the type it replaces in {@code based_on}; if the type below has changed since, the
 * replacement is refused, as for views. A replacement may not break the entities that are stored
 * already (see {@link #incompatibilities}), because migrations come later.
 */
public final class EntityCatalog {
  private final Map<String, EntityType> types;
  private final Map<String, String> owners;

  public EntityCatalog(Map<String, EntityType> types) {
    this(types, Map.of());
  }

  private EntityCatalog(Map<String, EntityType> types, Map<String, String> owners) {
    this.types = Map.copyOf(types);
    this.owners = Map.copyOf(owners);
  }

  /** A catalog without types. */
  public static EntityCatalog empty() {
    return new EntityCatalog(Map.of());
  }

  /**
   * A layer and where its files are.
   *
   * @param name the layer name
   * @param directory the directory of the layer
   */
  public record Source(String name, Path directory) {}

  /**
   * What was read from directories.
   *
   * @param catalog the valid types
   * @param problems everything that is wrong in the files or in how the layers put them together
   */
  public record Loaded(EntityCatalog catalog, List<Problem> problems) {}

  /** Reads the types of the layers, bottom to top, and puts them together. */
  public static Loaded load(List<Source> layers) throws IOException {
    Map<String, EntityType> all = new LinkedHashMap<>();
    Map<String, String> definedBy = new LinkedHashMap<>();
    Map<String, String> owners = new LinkedHashMap<>();
    List<Problem> problems = new ArrayList<>();
    for (Source layer : layers) {
      EntityTypes.Loaded loaded = EntityTypes.load(layer.directory());
      for (Problem p : loaded.problems()) {
        problems.add(new Problem("layer '" + layer.name() + "': " + p.location(), p.message()));
      }
      for (EntityType type : loaded.types().values()) {
        String where = "layer '" + layer.name() + "': entities/" + type.id() + ".toml";
        EntityType below = all.get(type.id());
        if (below == null && type.basedOn().isEmpty()) {
          all.put(type.id(), type);
          definedBy.put(type.id(), layer.name());
          owners.put(type.id(), layer.name());
        } else if (below == null) {
          problems.add(
              new Problem(
                  where,
                  "'based_on' names a type, but there is no type '" + type.id() + "' below"));
        } else if (type.basedOn().isEmpty()) {
          problems.add(
              new Problem(
                  where,
                  "type '"
                      + type.id()
                      + "' already exists (added by '"
                      + definedBy.get(type.id())
                      + "'); to replace it name its hash in 'based_on': "
                      + below.hash()));
        } else if (!type.basedOn().get().equals(below.hash())) {
          problems.add(
              new Problem(
                  where,
                  "the type '"
                      + type.id()
                      + "' has changed since this replacement was written: it is now "
                      + below.hash()
                      + ", not "
                      + type.basedOn().get()));
        } else {
          List<String> broken = incompatibilities(below, type);
          broken.forEach(
              message ->
                  problems.add(
                      new Problem(where, "the replacement breaks stored entities: " + message)));
          if (broken.isEmpty()) {
            all.put(type.id(), type);
            owners.put(type.id(), layer.name());
          }
        }
      }
    }
    return new Loaded(new EntityCatalog(all, owners), problems);
  }

  /**
   * What a replacement of a type changes that entities already stored cannot take: a field removed
   * or of another type, a new field that is required, a field that becomes required or unique, and
   * a sensitive field that becomes readable. (Migrations will allow some of these.)
   */
  public static List<String> incompatibilities(EntityType old, EntityType replacement) {
    List<String> out = new ArrayList<>();
    for (EntityType.Field before : old.fields()) {
      EntityType.Field after = replacement.field(before.name()).orElse(null);
      if (after == null) {
        out.add("field '" + before.name() + "' is removed");
        continue;
      }
      if (after.type() != before.type()) {
        out.add(
            "field '"
                + before.name()
                + "' changes its type from "
                + before.type().id()
                + " to "
                + after.type().id());
      }
      if (after.required() && !before.required()) {
        out.add("field '" + before.name() + "' becomes required");
      }
      if (after.unique() && !before.unique()) {
        out.add("field '" + before.name() + "' becomes unique");
      }
      if (before.sensitive() && !after.sensitive()) {
        out.add("field '" + before.name() + "' is no longer sensitive");
      }
    }
    for (EntityType.Field added : replacement.fields()) {
      if (old.field(added.name()).isEmpty() && added.required()) {
        out.add("the new field '" + added.name() + "' is required");
      }
    }
    return out;
  }

  public Optional<EntityType> get(String id) {
    return Optional.ofNullable(types.get(id));
  }

  public Map<String, EntityType> types() {
    return types;
  }

  /** The layer that defined the type last, if the catalog was made from layers. */
  public Optional<String> owner(String id) {
    return Optional.ofNullable(owners.get(id));
  }
}
