// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import it.uhde.praksis.protocol.Client;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Identities are found by a convention: the identity {@code private} has its home in {@code
 * ~/.praksis-private}, next to the default {@code ~/.praksis}. Each is a daemon of its own with its
 * own socket, store, policy and credentials; nothing is shared unless a person grants it.
 */
final class Identities {
  static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,15}");
  private static final String PREFIX = ".praksis-";

  private Identities() {}

  private static Path userHome() {
    return Path.of(System.getProperty("user.home"));
  }

  /** The home of an identity; refuses a name that is not valid. */
  static Path home(String name) {
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "'"
              + name
              + "' is not an identity name: lower case letters, digits and '-', starting with a"
              + " letter, 16 characters at most");
    }
    return userHome().resolve(PREFIX + name);
  }

  /** Makes the home of a new identity, with a configuration that names it. */
  static Path create(String name, Path sharedModules) throws IOException {
    Path home = home(name);
    if (Files.exists(home.resolve("praksis.toml"))) {
      throw new IOException("the identity '" + name + "' exists already: " + home);
    }
    Files.createDirectories(home);
    try {
      Files.setPosixFilePermissions(
          home, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
    } catch (UnsupportedOperationException ignored) {
      // not a POSIX file system
    }
    StringBuilder toml = new StringBuilder("format_version = 1\nidentity = \"" + name + "\"\n");
    if (sharedModules != null) {
      toml.append("shared_modules = \"")
          .append(sharedModules.toAbsolutePath().normalize().toString().replace("\\", "\\\\"))
          .append("\"\n");
    }
    Files.writeString(home.resolve("praksis.toml"), toml.toString());
    return home;
  }

  /** One identity found on this machine. */
  record Found(String name, Path home, boolean running) {}

  /** The identities of this machine: the directories {@code ~/.praksis-<name>}. */
  static List<Found> list() {
    List<Found> out = new ArrayList<>();
    try (DirectoryStream<Path> dirs = Files.newDirectoryStream(userHome(), PREFIX + "*")) {
      for (Path dir : dirs) {
        String name = dir.getFileName().toString().substring(PREFIX.length());
        if (Files.isDirectory(dir) && NAME.matcher(name).matches()) {
          out.add(new Found(name, dir, running(dir.resolve("praksis.sock"))));
        }
      }
    } catch (IOException e) {
      return List.of();
    }
    out.sort(java.util.Comparator.comparing(Found::name));
    return out;
  }

  private static boolean running(Path socket) {
    if (!Files.exists(socket)) {
      return false;
    }
    try {
      Client.connect(socket).close();
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
