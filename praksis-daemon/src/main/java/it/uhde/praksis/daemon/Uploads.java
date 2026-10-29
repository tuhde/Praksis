// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Response;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * The private place where a file waits that a person chose to attach to something they send. The
 * frontend asks for a place, copies the chosen file into it and hands the token on; only the token
 * leaves the frontend, and a module reads only the files whose tokens it is given, so no rule can
 * make anything read a file the person did not choose. The directory is for this user alone and
 * what is in it is removed after a day.
 */
final class Uploads {
  static final Duration KEEP = Duration.ofDays(1);

  /** The most uploaded files at one time, so that nothing can fill the disk with tickets. */
  static final int MAX_FILES = 200;

  private Uploads() {}

  /** A new, empty file that only this user can read, and its token. */
  static Response ticket(Path directory, Instant now) {
    try {
      if (!Files.isDirectory(directory)) {
        try {
          Files.createDirectories(
              directory,
              PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        } catch (UnsupportedOperationException e) {
          Files.createDirectories(directory);
        }
      }
      cleanup(directory, now);
      try (Stream<Path> files = Files.list(directory)) {
        if (files.count() >= MAX_FILES) {
          return new Response.Error("there are too many files waiting to be attached");
        }
      }
      String token = UUID.randomUUID().toString();
      Path file = directory.resolve(token + ".part");
      try {
        Files.createFile(
            file,
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      } catch (UnsupportedOperationException e) {
        Files.createFile(file);
      }
      return new Response.Uploaded(token, file.toString());
    } catch (IOException e) {
      return new Response.Error("cannot make room for the file: " + e.getMessage());
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
          // tried again at the next request
        }
      }
    } catch (IOException e) {
      // nothing to clean
    }
  }
}
