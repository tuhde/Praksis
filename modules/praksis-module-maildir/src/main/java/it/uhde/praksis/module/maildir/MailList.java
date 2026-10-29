// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.mail.html.Threads;
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
 * Lists the mails of a folder of the Maildir, newest first unless {@code sort} (time, from,
 * subject, size), {@code reverse} and {@code offset} say otherwise: id (the path, which {@code
 * mail.move} takes), sender, subject, date (as written and as a time), size and whether the mail is
 * unread. Only reads; a folder that does not exist is empty.
 */
public final class MailList implements Capability {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  private final Map<String, Settings> accounts = new java.util.LinkedHashMap<>();

  @Override
  public String name() {
    return "mail.list";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    accounts.putAll(Accounts.parse(config, Settings::parse));
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (accounts.isEmpty()) {
      throw new CapabilityException("mail.list is not configured", false);
    }
    Settings settings = Accounts.settingsFor(accounts, args);
    Path folder = settings.folderDir((String) args.get("folder"));
    long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
    int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
    int offset = args.get("offset") instanceof Number n ? (int) Math.max(0, n.longValue()) : 0;
    String sort = args.get("sort") instanceof String t ? t : "time";
    boolean reverse = Boolean.TRUE.equals(args.get("reverse"));
    boolean threaded = Boolean.TRUE.equals(args.get("threaded"));
    if (!List.of("time", "from", "subject", "size").contains(sort)) {
      throw new CapabilityException("sort must be time, from, subject or size", false);
    }
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
      List<Object> items = new ArrayList<>();
      if (sort.equals("time")) {
        files.sort(Comparator.comparing(MailList::modified).reversed());
        if (reverse) {
          java.util.Collections.reverse(files);
        }
        for (Path file : window(files, offset, limit)) {
          items.add(item(file, threaded));
        }
      } else {
        // sorting by a header needs every header, so only the window is built as a full item
        List<Map<String, Object>> all = new ArrayList<>();
        for (Path file : files) {
          all.add(item(file, threaded));
        }
        Comparator<Map<String, Object>> order =
            sort.equals("size")
                ? Comparator.comparingLong(m -> (Long) m.get("size"))
                : Comparator.comparing(
                    m -> ((String) m.get(sort)).toLowerCase(java.util.Locale.ROOT));
        all.sort(reverse ? order.reversed() : order);
        items.addAll(window(all, offset, limit));
      }
      if (threaded) {
        // the mails of a conversation together, within the page that was asked for
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> page = (List<Map<String, Object>>) (List<?>) items;
        items = new ArrayList<>(Threads.arrange(page));
      }
      return Map.of("items", items, "count", (long) files.size());
    } catch (IOException e) {
      throw new CapabilityException("cannot list " + folder + ": " + e.getMessage(), true, e);
    }
  }

  private static <T> List<T> window(List<T> all, int offset, int limit) {
    int from = Math.min(offset, all.size());
    return all.subList(from, Math.min(from + limit, all.size()));
  }

  /** The list entry of one mail. */
  static Map<String, Object> item(Path file) throws IOException {
    return item(file, false);
  }

  /** As above; for conversations also the headers that say which mail answers which. */
  static Map<String, Object> item(Path file, boolean threadHeaders) throws IOException {
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
    item.put("starred", MailFiles.flags(file.getFileName().toString()).indexOf('F') >= 0);
    if (threadHeaders) {
      item.put("message_id", headers.getOrDefault("message-id", ""));
      item.put("in_reply_to", headers.getOrDefault("in-reply-to", ""));
      item.put("references", headers.getOrDefault("references", ""));
    }
    return item;
  }

  static Instant modified(Path file) {
    try {
      return Files.getLastModifiedTime(file).toInstant();
    } catch (IOException e) {
      return Instant.EPOCH;
    }
  }
}
