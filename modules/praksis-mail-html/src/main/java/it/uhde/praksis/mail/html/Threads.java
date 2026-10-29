// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.mail.html;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Puts the mails of a list into conversations, by the headers that mail programs keep for it: a
 * reply names the mail it answers in {@code In-Reply-To} and the whole chain in {@code References}.
 * This works on the mails that are in the list, which is a window of a folder: a conversation whose
 * first mails are outside of it starts at the oldest one that is inside.
 *
 * <p>The mails of a conversation are put together, the oldest first so that an answer follows what
 * it answers, and the conversations are ordered by their newest mail, newest first, like a plain
 * list. Every item gets {@code thread} (the id that names the conversation), {@code depth} (0 for a
 * mail that answers nothing in the list, else one more than what it answers, at most 3) and {@code
 * thread_size}.
 */
public final class Threads {
  private static final Pattern ID = Pattern.compile("<[^<>\\s]+>");
  private static final int MAX_DEPTH = 3;

  private Threads() {}

  /**
   * Arranges the items. Each needs {@code message_id}, {@code in_reply_to}, {@code references}
   * (text, may be empty) and {@code time} (an {@link Instant}); the last two are removed again.
   *
   * @return the items in the new order, each with {@code thread}, {@code depth} and {@code
   *     thread_size}
   */
  public static List<Map<String, Object>> arrange(List<Map<String, Object>> items) {
    Map<String, Map<String, Object>> byId = new HashMap<>();
    for (Map<String, Object> item : items) {
      String id = first(text(item, "message_id"));
      if (!id.isEmpty()) {
        byId.putIfAbsent(id, item);
      }
    }
    Map<Map<String, Object>, String> keys = new HashMap<>();
    Map<Map<String, Object>, Integer> depths = new HashMap<>();
    for (Map<String, Object> item : items) {
      keys.put(item, key(item, byId, new HashSet<>()));
      depths.put(item, depth(item, byId, new HashSet<>()));
    }
    Map<String, List<Map<String, Object>>> threads = new LinkedHashMap<>();
    for (Map<String, Object> item : items) {
      threads.computeIfAbsent(keys.get(item), k -> new ArrayList<>()).add(item);
    }
    List<List<Map<String, Object>>> ordered = new ArrayList<>(threads.values());
    ordered.forEach(t -> t.sort(Comparator.comparing(Threads::time)));
    ordered.sort(
        Comparator.comparing((List<Map<String, Object>> t) -> time(t.get(t.size() - 1)))
            .reversed());
    List<Map<String, Object>> out = new ArrayList<>();
    for (List<Map<String, Object>> thread : ordered) {
      for (Map<String, Object> item : thread) {
        Map<String, Object> copy = new LinkedHashMap<>(item);
        copy.remove("in_reply_to");
        copy.remove("references");
        copy.put("thread", keys.get(item));
        copy.put("depth", (long) depths.get(item));
        copy.put("thread_size", (long) thread.size());
        out.add(copy);
      }
    }
    return out;
  }

  /** The conversation of a mail: that of what it answers if that is in the list, else its root. */
  private static String key(
      Map<String, Object> item, Map<String, Map<String, Object>> byId, Set<String> seen) {
    String parent = parent(item);
    Map<String, Object> above = parent.isEmpty() ? null : byId.get(parent);
    String self = first(text(item, "message_id"));
    if (above != null && above != item && seen.add(self)) {
      return key(above, byId, seen);
    }
    String refs = first(text(item, "references"));
    if (!refs.isEmpty()) {
      return refs;
    }
    if (!parent.isEmpty()) {
      return parent;
    }
    return self.isEmpty() ? "unthreaded:" + System.identityHashCode(item) : self;
  }

  private static int depth(
      Map<String, Object> item, Map<String, Map<String, Object>> byId, Set<String> seen) {
    String parent = parent(item);
    Map<String, Object> above = parent.isEmpty() ? null : byId.get(parent);
    if (above == null || above == item || !seen.add(first(text(item, "message_id")))) {
      return 0;
    }
    return Math.min(MAX_DEPTH, depth(above, byId, seen) + 1);
  }

  /** The mail that this one answers: the one named in In-Reply-To, else the last of References. */
  private static String parent(Map<String, Object> item) {
    String replyTo = first(text(item, "in_reply_to"));
    if (!replyTo.isEmpty()) {
      return replyTo;
    }
    Matcher m = ID.matcher(text(item, "references"));
    String last = "";
    while (m.find()) {
      last = m.group();
    }
    return last;
  }

  private static String first(String ids) {
    Matcher m = ID.matcher(ids);
    return m.find() ? m.group() : "";
  }

  private static String text(Map<String, Object> item, String key) {
    return item.get(key) instanceof String s ? s : "";
  }

  private static Instant time(Map<String, Object> item) {
    return item.get("time") instanceof Instant t ? t : Instant.EPOCH;
  }
}
