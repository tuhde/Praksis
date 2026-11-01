// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.ruleset.Problem;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

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
  private final Map<String, EntitySet> sets;
  private final Map<String, String> setOwners;
  private final Map<String, List<Version>> versions;

  /**
   * One version of a type: the hash of its file, the type, and the steps that turn the entities of
   * the version below into entities of this one (none for the first version, or for a replacement
   * that needs none).
   */
  public record Version(String hash, List<EntityMigration> steps, EntityType type) {}

  public EntityCatalog(Map<String, EntityType> types) {
    this(types, Map.of(), Map.of(), Map.of(), Map.of());
  }

  private EntityCatalog(
      Map<String, EntityType> types,
      Map<String, String> owners,
      Map<String, EntitySet> sets,
      Map<String, String> setOwners,
      Map<String, List<Version>> versions) {
    this.versions = Map.copyOf(versions);
    this.types = Map.copyOf(types);
    this.owners = Map.copyOf(owners);
    this.sets = Map.copyOf(sets);
    this.setOwners = Map.copyOf(setOwners);
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
    Map<String, List<Version>> versions = new LinkedHashMap<>();
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
          List<String> stepProblems = new ArrayList<>();
          EntityMigration.check(type.migrations(), type, stepProblems);
          stepProblems.forEach(m -> problems.add(new Problem(where, "migration " + m)));
          all.put(type.id(), type);
          definedBy.put(type.id(), layer.name());
          owners.put(type.id(), layer.name());
          // steps of a type that is not a replacement start from the version named in
          // 'migrates_from', which the layers do not have
          versions
              .computeIfAbsent(type.id(), k -> new ArrayList<>())
              .add(new Version(type.hash(), type.migrations(), type));
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
          List<String> broken = acceptance(below, type);
          broken.forEach(
              message ->
                  problems.add(
                      new Problem(where, "the replacement breaks stored entities: " + message)));
          if (broken.isEmpty()) {
            all.put(type.id(), type);
            owners.put(type.id(), layer.name());
            versions
                .computeIfAbsent(type.id(), k -> new ArrayList<>())
                .add(new Version(type.hash(), type.migrations(), type));
          }
        }
      }
    }
    // every reference points at a type that exists
    for (EntityType type : all.values()) {
      for (EntityType.Field f : type.fields()) {
        if (f.ref().isPresent() && !all.containsKey(f.ref().get())) {
          problems.add(
              new Problem(
                  "layer '" + owners.get(type.id()) + "': entities/" + type.id() + ".toml",
                  "field '"
                      + f.name()
                      + "' refers to the type '"
                      + f.ref().get()
                      + "', which does not exist"));
        }
      }
    }
    // the sets, layered like the types
    Map<String, EntitySet> allSets = new LinkedHashMap<>();
    Map<String, String> setDefinedBy = new LinkedHashMap<>();
    Map<String, String> setOwners = new LinkedHashMap<>();
    for (Source layer : layers) {
      EntityTypes.LoadedSets loaded = EntityTypes.loadSets(layer.directory());
      for (Problem p : loaded.problems()) {
        problems.add(new Problem("layer '" + layer.name() + "': " + p.location(), p.message()));
      }
      for (EntitySet set : loaded.sets().values()) {
        String where = "layer '" + layer.name() + "': entity-sets/" + set.id() + ".toml";
        EntitySet below = allSets.get(set.id());
        if (below == null && set.basedOn().isEmpty()) {
          allSets.put(set.id(), set);
          setDefinedBy.put(set.id(), layer.name());
          setOwners.put(set.id(), layer.name());
        } else if (below == null) {
          problems.add(
              new Problem(
                  where, "'based_on' names a set, but there is no set '" + set.id() + "' below"));
        } else if (set.basedOn().isEmpty()) {
          problems.add(
              new Problem(
                  where,
                  "set '"
                      + set.id()
                      + "' already exists (added by '"
                      + setDefinedBy.get(set.id())
                      + "'); to replace it name its hash in 'based_on': "
                      + below.hash()));
        } else if (!set.basedOn().get().equals(below.hash())) {
          problems.add(
              new Problem(
                  where,
                  "the set '"
                      + set.id()
                      + "' has changed since this replacement was written: it is now "
                      + below.hash()
                      + ", not "
                      + set.basedOn().get()));
        } else {
          allSets.put(set.id(), set);
          setOwners.put(set.id(), layer.name());
        }
      }
    }
    checkSets(allSets, setOwners, all, problems);
    return new Loaded(new EntityCatalog(all, owners, allSets, setOwners, versions), problems);
  }

  /** Every member of a set exists, sets do not contain themselves, and they are not too deep. */
  private static void checkSets(
      Map<String, EntitySet> sets,
      Map<String, String> owners,
      Map<String, EntityType> types,
      List<Problem> problems) {
    for (EntitySet set : sets.values()) {
      String where = "layer '" + owners.get(set.id()) + "': entity-sets/" + set.id() + ".toml";
      for (String type : set.types()) {
        if (!types.containsKey(type)) {
          problems.add(
              new Problem(where, "'types' has '" + type + "', which is not an entity type"));
        }
      }
      for (String inner : set.sets()) {
        if (!sets.containsKey(inner)) {
          problems.add(
              new Problem(where, "'sets' has '" + inner + "', which is not an entity set"));
        }
      }
      String cycle = cycle(sets, set.id(), List.of());
      if (cycle != null) {
        problems.add(new Problem(where, "the sets contain each other: " + cycle));
      } else if (depth(sets, set.id()) > EntitySet.MAX_DEPTH) {
        problems.add(
            new Problem(where, "sets are nested more than " + EntitySet.MAX_DEPTH + " deep"));
      }
    }
  }

  private static String cycle(Map<String, EntitySet> sets, String id, List<String> path) {
    if (path.contains(id)) {
      List<String> loop = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
      loop.add(id);
      return String.join(" > ", loop);
    }
    EntitySet set = sets.get(id);
    if (set == null) {
      return null;
    }
    List<String> next = new ArrayList<>(path);
    next.add(id);
    for (String inner : set.sets()) {
      String c = cycle(sets, inner, next);
      if (c != null) {
        return c;
      }
    }
    return null;
  }

  private static int depth(Map<String, EntitySet> sets, String id) {
    EntitySet set = sets.get(id);
    if (set == null) {
      return 0;
    }
    int deepest = 0;
    for (String inner : set.sets()) {
      deepest = Math.max(deepest, depth(sets, inner));
    }
    return 1 + deepest;
  }

  /** The sets by id. */
  public Map<String, EntitySet> sets() {
    return sets;
  }

  /** The layer that defined the set last. */
  public Optional<String> setOwner(String id) {
    return Optional.ofNullable(setOwners.get(id));
  }

  /**
   * The ids of all types in the set: its own and those of the sets inside it, to any depth. Empty
   * for a set that does not exist.
   */
  public Set<String> members(String setId) {
    Set<String> out = new TreeSet<>();
    collect(setId, out, new HashSet<>());
    return out;
  }

  private void collect(String setId, Set<String> out, Set<String> visited) {
    EntitySet set = sets.get(setId);
    if (set == null || !visited.add(setId)) {
      return;
    }
    set.types().stream().filter(types::containsKey).forEach(out::add);
    set.sets().forEach(inner -> collect(inner, out, visited));
  }

  /** The members of every set, by set id: what a policy needs to know about the sets. */
  public Map<String, Set<String>> allMembers() {
    Map<String, Set<String>> out = new java.util.TreeMap<>();
    sets.keySet().forEach(id -> out.put(id, members(id)));
    return out;
  }

  /** The ids of the sets that hold the type, directly or inside other sets. */
  public Set<String> setsOf(String typeId) {
    Set<String> out = new TreeSet<>();
    sets.keySet().stream().filter(id -> members(id).contains(typeId)).forEach(out::add);
    return out;
  }

  /**
   * What stops a replacement: without migration steps what {@link #incompatibilities} finds, with
   * them the steps that cannot be done and what is still wrong with the entities as the steps leave
   * them.
   */
  public static List<String> acceptance(EntityType old, EntityType replacement) {
    if (replacement.migrations().isEmpty()) {
      return incompatibilities(old, replacement);
    }
    List<String> problems = new ArrayList<>();
    List<EntityType.Field> shape =
        EntityMigration.shape(old, replacement.migrations(), replacement, problems);
    if (!problems.isEmpty()) {
      return problems;
    }
    EntityType migrated =
        new EntityType(
            old.id(), old.title(), old.description(), shape, old.basedOn(), old.raw(), List.of());
    return incompatibilities(migrated, replacement);
  }

  /** The versions of a type, oldest first; the last is the one in use. */
  public List<Version> versions(String typeId) {
    return versions.getOrDefault(typeId, List.of());
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
      if (after.ref().isPresent() && !after.ref().equals(before.ref())) {
        out.add(
            before.ref().isEmpty()
                ? "field '" + before.name() + "' becomes a reference to " + after.ref().get()
                : "field '"
                    + before.name()
                    + "' refers to "
                    + after.ref().get()
                    + " and not to "
                    + before.ref().get()
                    + " any more");
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

  /**
   * A field of a type that refers to another type.
   *
   * @param type the type that has the field
   * @param field the field
   */
  public record Reference(EntityType type, EntityType.Field field) {}

  /** The fields of all types that refer to this type, in the order of type ids and fields. */
  public List<Reference> referencesTo(String typeId) {
    List<Reference> out = new ArrayList<>();
    types.values().stream()
        .sorted(java.util.Comparator.comparing(EntityType::id))
        .forEach(
            t ->
                t.fields().stream()
                    .filter(f -> f.ref().filter(typeId::equals).isPresent())
                    .forEach(f -> out.add(new Reference(t, f))));
    return out;
  }

  /** The layer that defined the type last, if the catalog was made from layers. */
  public Optional<String> owner(String id) {
    return Optional.ofNullable(owners.get(id));
  }
}
