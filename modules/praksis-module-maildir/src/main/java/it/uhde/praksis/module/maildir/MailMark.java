// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Sets or clears the flags of a mail: {@code seen} (read) and {@code flagged} (starred). A mail
 * that is seen leaves {@code new} for {@code cur}. The flags are part of the file name, so the
 * answer names the mail again; the old name still finds it. Setting what is set already is not an
 * error.
 */
public final class MailMark implements Capability {
  private Settings settings;

  @Override
  public String name() {
    return "mail.mark";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.mark is not configured", false);
    }
    Path file = MailFiles.resolve(settings, (String) args.get("message"));
    String flags = MailFiles.flags(file.getFileName().toString());
    flags = change(flags, 'S', args.get("seen"));
    flags = change(flags, 'F', args.get("flagged"));
    Path folder = file.getParent().getParent();
    boolean wasNew = file.getParent().getFileName().toString().equals("new");
    boolean seen = flags.indexOf('S') >= 0;
    Path dir = folder.resolve(wasNew && !seen ? "new" : "cur");
    Path target =
        dir.resolve(
            wasNew && !seen
                ? file.getFileName().toString()
                : MailFiles.named(file.getFileName().toString(), flags));
    try {
      if (!target.equals(file) && !context.dryRun()) {
        Files.createDirectories(dir);
        Files.move(file, target);
      }
      return Map.of("message", target.toString());
    } catch (IOException e) {
      throw new CapabilityException("cannot mark " + file + ": " + e.getMessage(), true, e);
    }
  }

  private static String change(String flags, char flag, Object wanted) {
    if (!(wanted instanceof Boolean on)) {
      return flags;
    }
    boolean has = flags.indexOf(flag) >= 0;
    if (on && !has) {
      return flags + flag;
    }
    return !on && has ? flags.replace(String.valueOf(flag), "") : flags;
  }
}
