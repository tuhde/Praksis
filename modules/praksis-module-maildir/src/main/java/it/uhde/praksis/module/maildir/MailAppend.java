// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * {@code mail.append}: puts a mail into a folder, which is made if it does not exist. It is how the
 * copy of a sent mail gets into the Sent folder and how a draft is saved. The mail is written to
 * {@code tmp} and then moved to {@code cur}, as the Maildir format asks, so a reader never sees a
 * half-written mail. It is marked read (unless {@code seen} is false) and as a draft if asked.
 * Unlike the IMAP module this does not look for the same mail in the folder first.
 */
public final class MailAppend implements Capability {
  private Settings settings;

  @Override
  public String name() {
    return "mail.append";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.append is not configured", false);
    }
    if (!(args.get("content") instanceof byte[] content) || content.length == 0) {
      throw new CapabilityException("mail.append needs the content of the mail", false);
    }
    Path folder = settings.folderDir((String) args.get("folder"));
    String flags =
        (Boolean.FALSE.equals(args.get("seen")) ? "" : "S")
            + (Boolean.TRUE.equals(args.get("draft")) ? "D" : "");
    String unique = System.currentTimeMillis() + "." + UUID.randomUUID() + ".praksis";
    Path target = folder.resolve("cur").resolve(MailFiles.named(unique, flags));
    if (context.dryRun()) {
      return Map.of("message", target.toString());
    }
    try {
      for (String d : new String[] {"tmp", "new", "cur"}) {
        Files.createDirectories(folder.resolve(d));
      }
      Path tmp = folder.resolve("tmp").resolve(unique);
      Files.write(tmp, content);
      Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE);
      return Map.of("message", target.toString());
    } catch (IOException e) {
      throw new CapabilityException("cannot write to " + folder + ": " + e.getMessage(), true, e);
    }
  }
}
