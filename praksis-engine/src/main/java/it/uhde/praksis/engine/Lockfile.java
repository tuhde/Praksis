// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * The local record of which module contents the user has accepted: a SHA-256 over all files of a
 * module directory, per module. A swapped or extended jar changes the hash and the module is not
 * loaded.
 *
 * <pre>
 * [module.maildir]
 * sha256 = "..."
 * </pre>
 */
final class Lockfile {
  private static final TomlMapper MAPPER = new TomlMapper();

  private final Map<String, String> hashes;

  private Lockfile(Map<String, String> hashes) {
    this.hashes = hashes;
  }

  static Lockfile read(Path file) throws ModuleLoadException {
    if (!Files.exists(file)) {
      return new Lockfile(new TreeMap<>());
    }
    try {
      Map<String, Map<String, Map<String, String>>> raw =
          MAPPER.readValue(file.toFile(), new TypeReference<>() {});
      Map<String, String> hashes = new TreeMap<>();
      raw.getOrDefault("module", Map.of())
          .forEach((name, entry) -> hashes.put(name, entry.get("sha256")));
      return new Lockfile(hashes);
    } catch (IOException | RuntimeException e) {
      throw new ModuleLoadException("cannot read " + file + ": " + e.getMessage(), e);
    }
  }

  String hashOf(String module) {
    return hashes.get(module);
  }

  void put(String module, String sha256) {
    hashes.put(module, sha256);
  }

  void write(Path file) throws ModuleLoadException {
    Map<String, Object> modules = new TreeMap<>();
    hashes.forEach((name, hash) -> modules.put(name, Map.of("sha256", hash)));
    try {
      MAPPER.writeValue(file.toFile(), Map.of("module", modules));
    } catch (IOException e) {
      throw new ModuleLoadException("cannot write " + file + ": " + e.getMessage(), e);
    }
  }

  /** Hashes the names and contents of all files below the directory, in a fixed order. */
  static String hashDirectory(Path dir) throws ModuleLoadException {
    try (Stream<Path> files = Files.walk(dir)) {
      MessageDigest all = MessageDigest.getInstance("SHA-256");
      List<Path> sorted = files.filter(Files::isRegularFile).sorted().toList();
      for (Path file : sorted) {
        all.update(
            dir.relativize(file)
                .toString()
                .replace('\\', '/')
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        all.update((byte) 0);
        all.update(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
      }
      return HexFormat.of().formatHex(all.digest());
    } catch (IOException | NoSuchAlgorithmException e) {
      throw new ModuleLoadException("cannot hash " + dir + ": " + e.getMessage(), e);
    }
  }
}
