// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * The {@code [module.maildir]} settings: {@code root}, the Maildir directory (it holds {@code new},
 * {@code cur} and {@code tmp}), and optionally {@code poll_interval_ms}.
 */
record Settings(Path root, Duration pollInterval) {
  private static final long DEFAULT_POLL_MS = 1000;

  static Settings parse(Map<String, Object> settings) throws CapabilityException {
    for (String key : settings.keySet()) {
      if (!key.equals("root") && !key.equals("poll_interval_ms")) {
        throw new CapabilityException("unknown maildir setting '" + key + "'", false);
      }
    }
    if (!(settings.get("root") instanceof String root) || root.isBlank()) {
      throw new CapabilityException("maildir setting 'root' is required (a path)", false);
    }
    long poll = DEFAULT_POLL_MS;
    if (settings.containsKey("poll_interval_ms")) {
      if (!(settings.get("poll_interval_ms") instanceof Long value) || value < 1) {
        throw new CapabilityException(
            "maildir setting 'poll_interval_ms' must be a positive integer", false);
      }
      poll = value;
    }
    Path path = Path.of(root).toAbsolutePath().normalize();
    if (!Files.isDirectory(path)) {
      throw new CapabilityException("maildir root " + path + " is not a directory", false);
    }
    return new Settings(path, Duration.ofMillis(poll));
  }

  /**
   * The directory of a Maildir folder: {@code INBOX} is the root, others are {@code .Name}. Levels
   * of a folder are written with {@code /} as for every mail module ({@code Auto/invoice}) and are
   * separated by a dot in the directory ({@code .Auto.invoice}, Maildir++).
   */
  Path folderDir(String folder) throws CapabilityException {
    if (folder.equals("INBOX")) {
      return root;
    }
    if (folder.isEmpty()
        || folder.startsWith(".")
        || folder.contains("..")
        || folder.startsWith("/")
        || folder.endsWith("/")
        || folder.contains("//")
        || folder.indexOf('\\') >= 0
        || folder.indexOf('\0') >= 0) {
      throw new CapabilityException("invalid mail folder name '" + folder + "'", false);
    }
    return root.resolve("." + folder.replace('/', '.'));
  }
}
