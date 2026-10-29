// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The content of one attachment of a mail, by name. Only reads, and refuses an attachment that is
 * bigger than the limit (5 MiB unless {@code max_bytes} says less or more, up to 25 MiB). The
 * content is handed over as bytes; this module never opens, runs or saves it.
 */
public final class MailAttachment implements Capability {
  static final long DEFAULT_MAX = 5L * 1024 * 1024;
  static final long LIMIT = 25L * 1024 * 1024;

  private Settings settings;

  @Override
  public String name() {
    return "mail.attachment";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.attachment is not configured", false);
    }
    long max =
        args.get("max_bytes") instanceof Number n
            ? Math.max(1, Math.min(n.longValue(), LIMIT))
            : DEFAULT_MAX;
    Path file = MailFiles.resolve(settings, (String) args.get("message"));
    String name = (String) args.get("name");
    try {
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, LIMIT + MailGet.MAX_BYTES));
      }
      for (MailBody.Attachment a : MailBody.parse(bytes, true).attachments()) {
        if (a.name().equals(name)) {
          if (a.size() > max) {
            throw new CapabilityException(
                "the attachment '"
                    + name
                    + "' is "
                    + a.size()
                    + " bytes, more than the limit of "
                    + max,
                false);
          }
          Map<String, Object> out = new LinkedHashMap<>();
          out.put("name", a.name());
          out.put("type", a.type());
          out.put("size", a.size());
          out.put("content", a.content());
          return out;
        }
      }
      throw new CapabilityException("the mail has no attachment named '" + name + "'", false);
    } catch (IOException e) {
      throw new CapabilityException("cannot read " + file + ": " + e.getMessage(), true, e);
    }
  }
}
