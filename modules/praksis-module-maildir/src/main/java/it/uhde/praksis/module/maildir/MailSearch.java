// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Finds the mails of a folder that contain a text, newest first. {@code in} says where to look:
 * {@code from}, {@code subject}, {@code body} (the first 2 MiB of the mail) or {@code all}, the
 * default. The case does not matter. Only reads. The answer has the entries of {@code mail.list}.
 */
public final class MailSearch implements Capability {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  private Settings settings;

  @Override
  public String name() {
    return "mail.search";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.search is not configured", false);
    }
    Path folder = settings.folderDir((String) args.get("folder"));
    String query = ((String) args.get("query")).toLowerCase(Locale.ROOT);
    String in = args.get("in") instanceof String s ? s : "all";
    if (!List.of("all", "from", "subject", "body").contains(in)) {
      throw new CapabilityException("in must be all, from, subject or body", false);
    }
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
      long found = 0;
      for (Path file : files) {
        if (!matches(file, query, in)) {
          continue;
        }
        found++;
        if (items.size() < limit) {
          items.add(MailList.item(file));
        }
      }
      return Map.of("items", items, "count", found);
    } catch (IOException e) {
      throw new CapabilityException("cannot search " + folder + ": " + e.getMessage(), true, e);
    }
  }

  private static boolean matches(Path file, String query, String in) throws IOException {
    if (query.isEmpty()) {
      return true;
    }
    if (!in.equals("body")) {
      Map<String, String> headers = Headers.read(file);
      boolean from = headers.getOrDefault("from", "").toLowerCase(Locale.ROOT).contains(query);
      boolean subject =
          headers.getOrDefault("subject", "").toLowerCase(Locale.ROOT).contains(query);
      if (in.equals("from") ? from : in.equals("subject") ? subject : from || subject) {
        return true;
      }
      if (!in.equals("all")) {
        return false;
      }
    }
    try (var input = Files.newInputStream(file)) {
      byte[] bytes = input.readNBytes(MailGet.MAX_BYTES);
      MailBody.Parsed body = MailBody.parse(bytes);
      return body.text().toLowerCase(Locale.ROOT).contains(query)
          || body.html().toLowerCase(Locale.ROOT).contains(query);
    }
  }
}
