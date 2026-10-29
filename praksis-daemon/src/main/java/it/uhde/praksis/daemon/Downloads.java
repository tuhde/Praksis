// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The private place where a file that a person asked for waits until the frontend has copied it
 * where the person wants it. The directory is for this user alone, the files have names that no
 * mail chose, and they are removed after an hour. Nothing in it is opened or run.
 */
final class Downloads {
  static final Duration KEEP = Duration.ofHours(1);

  /** The biggest file that is kept, whatever the capability allows. */
  static final long MAX_BYTES = 64L * 1024 * 1024;

  private Downloads() {}

  /** Writes the bytes to a new file in the directory (made if missing) and returns its path. */
  static Path store(Path directory, byte[] content) throws IOException {
    makeDirectory(directory);
    Path file = directory.resolve(UUID.randomUUID() + ".part");
    try {
      Files.createFile(
          file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
    } catch (UnsupportedOperationException e) {
      Files.createFile(file); // not a POSIX file system: the directory is the user's anyway
    }
    Files.write(file, content);
    return file;
  }

  private static void makeDirectory(Path directory) throws IOException {
    if (Files.isDirectory(directory)) {
      return;
    }
    try {
      Files.createDirectories(
          directory,
          PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
    } catch (UnsupportedOperationException e) {
      Files.createDirectories(directory);
    }
  }

  /** Removes what is older than {@link #KEEP}. */
  static void cleanup(Path directory, Instant now) {
    if (!Files.isDirectory(directory)) {
      return;
    }
    try (Stream<Path> files = Files.list(directory)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        try {
          if (Files.getLastModifiedTime(f).toInstant().isBefore(now.minus(KEEP))) {
            Files.delete(f);
          }
        } catch (IOException e) {
          // tried again at the next fetch
        }
      }
    } catch (IOException e) {
      // nothing to clean
    }
  }

  /**
   * A name for the person to start from: the last part of what was suggested, with no path, no
   * control characters and no leading dots, and not too long.
   */
  static String cleanName(String suggested) {
    String name = suggested == null ? "" : suggested;
    name = name.substring(Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\')) + 1);
    name = name.replaceAll("[\\p{Cntrl}\\\\/:*?\"<>|]", "_").strip();
    while (name.startsWith(".")) {
      name = name.substring(1);
    }
    if (name.length() > 120) {
      String extension = name.contains(".") ? name.substring(name.lastIndexOf('.')) : "";
      extension = extension.length() > 12 ? "" : extension;
      name = name.substring(0, 120 - extension.length()) + extension;
    }
    return name.isEmpty() ? "download" : name;
  }
}
