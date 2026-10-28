// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Moves a mail into another folder of the same Maildir, into its {@code cur} directory.
 *
 * <p>Folders follow the Maildir++ convention: {@code INBOX} is the root, {@code Archive} is the
 * directory {@code .Archive}, and {@code Archive.2024} is {@code .Archive.2024}. A folder that does
 * not exist is created.
 *
 * <p>The call is idempotent: if the mail is gone from its old place but is already in the target
 * folder, the call succeeds and reports where it is. That makes a repeat after a crash harmless.
 */
public final class MailMove implements Capability {
  private Settings settings;

  @Override
  public String name() {
    return "mail.move";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.move is not configured", false);
    }
    Path source = Path.of((String) args.get("message")).toAbsolutePath().normalize();
    checkInsideRoot(source);
    Path targetFolder = settings.folderDir((String) args.get("folder"));
    String name = source.getFileName().toString();
    String unique = uniquePart(name);
    Path target = targetFolder.resolve("cur").resolve(unique + ":2," + flags(name));

    try {
      if (!Files.exists(source)) {
        Optional<Path> already = find(targetFolder, unique);
        if (already.isPresent()) {
          return Map.of("message", already.get().toString());
        }
        throw new CapabilityException("mail not found: " + source, false);
      }
      if (!context.dryRun()) {
        for (String dir : Set.of("cur", "new", "tmp")) {
          Files.createDirectories(targetFolder.resolve(dir));
        }
        Files.move(source, target);
      }
      return Map.of("message", target.toString());
    } catch (IOException e) {
      throw new CapabilityException("cannot move " + source + ": " + e.getMessage(), true, e);
    }
  }

  /**
   * The source must be a file directly in the {@code new} or {@code cur} of a folder of the root.
   */
  private void checkInsideRoot(Path source) throws CapabilityException {
    Path dir = source.getParent();
    Path folder = dir == null ? null : dir.getParent();
    boolean mailDir = dir != null && Set.of("new", "cur").contains(dir.getFileName().toString());
    boolean inRoot =
        folder != null
            && (folder.equals(settings.root())
                || (settings.root().equals(folder.getParent())
                    && folder.getFileName().toString().startsWith(".")));
    if (!mailDir || !inRoot) {
      throw new CapabilityException(
          "path " + source + " is not a mail in the Maildir " + settings.root(), false);
    }
  }

  private static Optional<Path> find(Path folder, String unique) throws IOException {
    for (String dir : new String[] {"cur", "new"}) {
      Path d = folder.resolve(dir);
      if (!Files.isDirectory(d)) {
        continue;
      }
      try (Stream<Path> files = Files.list(d)) {
        Optional<Path> hit =
            files.filter(f -> uniquePart(f.getFileName().toString()).equals(unique)).findFirst();
        if (hit.isPresent()) {
          return hit;
        }
      }
    }
    return Optional.empty();
  }

  /** The part of a Maildir file name before the info suffix; unique by definition. */
  static String uniquePart(String fileName) {
    int colon = fileName.indexOf(':');
    return colon < 0 ? fileName : fileName.substring(0, colon);
  }

  /** The flags of a file name ({@code S}, {@code R}, ...), or empty if it has none. */
  private static String flags(String fileName) {
    int colon = fileName.indexOf(':');
    String info = colon < 0 ? "" : fileName.substring(colon + 1);
    return info.startsWith("2,") ? info.substring(2) : "";
  }
}
