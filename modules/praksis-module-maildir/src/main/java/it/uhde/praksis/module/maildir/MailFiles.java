// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * Finding the file of a mail. A mail is named by its path, but the path changes when its flags do
 * (the flags are part of the file name), so a path that is not there any more is looked up again by
 * the unique part of the name in the folder it was in.
 */
final class MailFiles {
  private MailFiles() {}

  /**
   * The file of the mail, inside the Maildir.
   *
   * @throws CapabilityException if the path is not a mail of the Maildir, or the mail is gone
   */
  static Path resolve(Settings settings, String message) throws CapabilityException {
    Path file = find(settings, message);
    if (file == null) {
      throw new CapabilityException("mail not found: " + message, false);
    }
    return file;
  }

  /** As {@link #resolve}, but null if the mail is gone. */
  static Path find(Settings settings, String message) throws CapabilityException {
    Path file = Path.of(message).toAbsolutePath().normalize();
    inside(settings, file);
    if (Files.exists(file)) {
      return file;
    }
    Path folder = file.getParent().getParent();
    String unique = MailMove.uniquePart(file.getFileName().toString());
    for (String dir : new String[] {"cur", "new"}) {
      Path d = folder.resolve(dir);
      if (!Files.isDirectory(d)) {
        continue;
      }
      try (Stream<Path> files = Files.list(d)) {
        var hit =
            files
                .filter(f -> MailMove.uniquePart(f.getFileName().toString()).equals(unique))
                .findFirst();
        if (hit.isPresent()) {
          return hit.get();
        }
      } catch (IOException e) {
        throw new CapabilityException("cannot read " + d + ": " + e.getMessage(), true, e);
      }
    }
    return null;
  }

  /** Only a file directly in {@code new} or {@code cur} of a folder of the root. */
  static void inside(Settings settings, Path file) throws CapabilityException {
    Path dir = file.getParent();
    Path folder = dir == null ? null : dir.getParent();
    boolean mailDir = dir != null && Set.of("new", "cur").contains(dir.getFileName().toString());
    boolean inRoot =
        folder != null
            && (folder.equals(settings.root())
                || (settings.root().equals(folder.getParent())
                    && folder.getFileName().toString().startsWith(".")));
    if (!mailDir || !inRoot) {
      throw new CapabilityException(
          "path " + file + " is not a mail in the Maildir " + settings.root(), false);
    }
  }

  /** The flags of a file name ({@code S}, {@code F}, ...), sorted. */
  static String flags(String fileName) {
    int info = fileName.lastIndexOf(":2,");
    return info < 0 ? "" : fileName.substring(info + 3);
  }

  /** The file name with these flags, which are sorted as the Maildir format wants. */
  static String named(String fileName, String flags) {
    TreeSet<Character> sorted = new TreeSet<>();
    for (char c : flags.toCharArray()) {
      sorted.add(c);
    }
    StringBuilder out = new StringBuilder(MailMove.uniquePart(fileName)).append(":2,");
    sorted.forEach(out::append);
    return out.toString();
  }
}
