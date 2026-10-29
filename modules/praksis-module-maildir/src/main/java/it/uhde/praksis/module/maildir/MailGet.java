// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.mail.html.Sanitizer;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads one mail for showing: the headers a reader needs, the plain text, the HTML as text (it is
 * never executed here) and the list of attachments. Only reads, and only mails in the Maildir.
 */
public final class MailGet implements Capability {
  /** The most bytes of a mail that are read. */
  static final int MAX_BYTES = 2 * 1024 * 1024;

  private Settings settings;

  @Override
  public String name() {
    return "mail.get";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.get is not configured", false);
    }
    // the id may be from before the mail was marked, which renames the file
    Path file = MailFiles.resolve(settings, (String) args.get("message"));
    boolean images = Boolean.TRUE.equals(args.get("images"));
    try {
      byte[] bytes;
      try (InputStream in = Files.newInputStream(file)) {
        bytes = in.readNBytes(MAX_BYTES);
      }
      Map<String, String> headers =
          Headers.parse(new String(bytes, java.nio.charset.StandardCharsets.ISO_8859_1));
      // the headers are decoded as UTF-8 text by Headers.read; use it for fidelity
      headers = Headers.read(file);
      MailBody.Parsed body = MailBody.parse(bytes);
      Map<String, Object> out = new LinkedHashMap<>();
      out.put("from", headers.getOrDefault("from", ""));
      out.put("to", headers.getOrDefault("to", ""));
      out.put("cc", headers.getOrDefault("cc", ""));
      out.put("subject", headers.getOrDefault("subject", ""));
      out.put("date", headers.getOrDefault("date", ""));
      out.put("time", time(headers.getOrDefault("date", ""), file));
      out.put("message_id", headers.getOrDefault("message-id", ""));
      out.put("text", body.text());
      out.put("html", body.html());
      Sanitizer.Result safe = Sanitizer.sanitize(body.html(), images);
      out.put("blocks", safe.blocks());
      out.put("blocked", (long) safe.blockedImages());
      List<Object> attachments = new ArrayList<>();
      for (MailBody.Attachment a : body.attachments()) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", a.name());
        m.put("type", a.type());
        m.put("size", a.size());
        attachments.add(m);
      }
      out.put("attachments", attachments);
      out.put("size", Files.size(file));
      return out;
    } catch (java.nio.file.NoSuchFileException e) {
      throw new CapabilityException("mail not found: " + file, false);
    } catch (IOException e) {
      throw new CapabilityException("cannot read " + file + ": " + e.getMessage(), true, e);
    }
  }

  /** The time of the mail: its Date header, else when the file was written. */
  static Instant time(String date, Path file) {
    try {
      return OffsetDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
    } catch (DateTimeParseException e) {
      try {
        return Files.getLastModifiedTime(file).toInstant();
      } catch (IOException ex) {
        return Instant.EPOCH;
      }
    }
  }
}
