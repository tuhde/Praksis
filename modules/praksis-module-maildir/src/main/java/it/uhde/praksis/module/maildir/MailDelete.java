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
 * Removes a mail for good. This cannot be undone, so it is a destructive capability that needs an
 * explicit grant; moving a mail to a Trash folder with {@code mail.move} is the usual way to
 * delete. A mail that is gone already is not an error, so a repeat after a crash is harmless.
 */
public final class MailDelete implements Capability {
  private Settings settings;

  @Override
  public String name() {
    return "mail.delete";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.delete is not configured", false);
    }
    Path file = MailFiles.find(settings, (String) args.get("message"));
    if (file == null) {
      return Map.of("deleted", false);
    }
    try {
      if (!context.dryRun()) {
        Files.delete(file);
      }
      return Map.of("deleted", true);
    } catch (IOException e) {
      throw new CapabilityException("cannot delete " + file + ": " + e.getMessage(), true, e);
    }
  }
}
