// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Table;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads entity types from TOML. Problems are collected, not thrown, so one run reports them all.
 */
public final class EntityTypes {
  private static final TomlMapper MAPPER = new TomlMapper();

  private EntityTypes() {}

  /**
   * What was read from a directory.
   *
   * @param types the valid types by id, in file name order
   * @param problems everything that is wrong; empty if all files are valid
   */
  public record Loaded(Map<String, EntityType> types, List<Problem> problems) {}

  /** Reads one type from the text of {@code entities/<fileId>.toml}. */
  public static Optional<EntityType> parse(String toml, String fileId, List<Problem> problems) {
    String where = "entities/" + fileId + ".toml";
    Map<String, Object> raw;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = MAPPER.readValue(toml, Map.class);
      raw = read;
    } catch (JsonProcessingException e) {
      problems.add(new Problem(where, "not valid TOML: " + e.getOriginalMessage()));
      return Optional.empty();
    }
    return EntityType.parse(new Table(raw, where, problems), problems, fileId, raw);
  }

  /**
   * What was read from the sets of a directory.
   *
   * @param sets the valid sets by id, in file name order
   * @param problems everything that is wrong; empty if all files are valid
   */
  public record LoadedSets(Map<String, EntitySet> sets, List<Problem> problems) {}

  /** Reads one set from the text of {@code entity-sets/<fileId>.toml}. */
  public static Optional<EntitySet> parseSet(String toml, String fileId, List<Problem> problems) {
    String where = "entity-sets/" + fileId + ".toml";
    Map<String, Object> raw;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> read = MAPPER.readValue(toml, Map.class);
      raw = read;
    } catch (JsonProcessingException e) {
      problems.add(new Problem(where, "not valid TOML: " + e.getOriginalMessage()));
      return Optional.empty();
    }
    return EntitySet.parse(new Table(raw, where, problems), problems, fileId, raw);
  }

  /** Reads every {@code entity-sets/*.toml} below a layer directory. */
  public static LoadedSets loadSets(Path directory) throws IOException {
    Path dir = directory.resolve("entity-sets");
    Map<String, EntitySet> sets = new LinkedHashMap<>();
    List<Problem> problems = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return new LoadedSets(sets, problems);
    }
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> !Files.isDirectory(p)).sorted().toList();
    }
    for (Path file : files) {
      String name = file.getFileName().toString();
      if (!name.endsWith(".toml")) {
        problems.add(new Problem("entity-sets/" + name, "is not a .toml file"));
        continue;
      }
      String id = name.substring(0, name.length() - ".toml".length());
      parseSet(Files.readString(file, StandardCharsets.UTF_8), id, problems)
          .ifPresent(t -> sets.put(t.id(), t));
    }
    return new LoadedSets(sets, problems);
  }

  /**
   * Reads every {@code entities/*.toml} below a module or layer directory. A directory without
   * {@code entities/} has no types.
   */
  public static Loaded load(Path directory) throws IOException {
    Path dir = directory.resolve("entities");
    Map<String, EntityType> types = new LinkedHashMap<>();
    List<Problem> problems = new ArrayList<>();
    if (!Files.isDirectory(dir)) {
      return new Loaded(types, problems);
    }
    List<Path> files;
    try (Stream<Path> s = Files.list(dir)) {
      files = s.filter(p -> !Files.isDirectory(p)).sorted().toList();
    }
    for (Path file : files) {
      String name = file.getFileName().toString();
      if (!name.endsWith(".toml")) {
        problems.add(new Problem("entities/" + name, "is not a .toml file"));
        continue;
      }
      String id = name.substring(0, name.length() - ".toml".length());
      parse(Files.readString(file, StandardCharsets.UTF_8), id, problems)
          .ifPresent(t -> types.put(t.id(), t));
    }
    return new Loaded(types, problems);
  }
}
