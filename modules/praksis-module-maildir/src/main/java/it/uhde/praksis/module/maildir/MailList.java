// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Lists the mails of a folder of the Maildir, newest first: id (the path, which {@code mail.move}
 * takes), sender, subject, date (as written and as a time), size and whether the mail is unread.
 * Only reads; a folder that does not exist is empty.
 */
public final class MailList implements Capability {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  private Settings settings;

  @Override
  public String name() {
    return "mail.list";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.list is not configured", false);
    }
    Path folder = settings.folderDir((String) args.get("folder"));
    long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
    int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
    try {
      List<Path> files = new ArrayList<>();
      for (String dir : new String[] {"new", "cur"}) {
        Path d = folder.resolve(dir);
        if (Files.isDirectory(d)) {
          try (Stream<Path> list = Files.list(d)) {
            list.filter(Files::isRegularFile).forEach(files::add);
          }
        }
      }
      files.sort(Comparator.comparing(MailList::modified).reversed());
      List<Object> items = new ArrayList<>();
      for (Path file : files.subList(0, Math.min(limit, files.size()))) {
        Map<String, String> headers = Headers.read(file);
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", file.toString());
        item.put("from", headers.getOrDefault("from", ""));
        item.put("subject", headers.getOrDefault("subject", ""));
        item.put("date", headers.getOrDefault("date", ""));
        item.put("time", MailGet.time(headers.getOrDefault("date", ""), file));
        item.put("size", Files.size(file));
        item.put(
            "unread",
            MailFolders.isUnread(
                file.getParent().getFileName().toString(), file.getFileName().toString()));
        items.add(item);
      }
      return Map.of("items", items, "count", (long) files.size());
    } catch (IOException e) {
      throw new CapabilityException("cannot list " + folder + ": " + e.getMessage(), true, e);
    }
  }

  private static Instant modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant();
    } catch (IOException e) {
      return Instant.EPOCH;
    }
  }
}
