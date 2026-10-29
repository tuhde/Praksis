// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Response.ViewComponent;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the value of a {@code richtext} component into the description the frontend draws. The
 * value comes from a capability, which is trusted code, but it is mail, so it is checked again here
 * and what does not fit is dropped, not repaired: only known kinds of blocks, text of a bounded
 * size, links to http, https and mailto, images at https addresses, and a bounded number of blocks
 * and levels. Whatever the capability puts into the value, the description cannot say more than
 * "text, in blocks, with a few styles".
 */
final class RichText {
  static final int MAX_BLOCKS = 5_000;
  static final int MAX_CHARS = 400_000;
  static final int MAX_DEPTH = 12;
  private static final int MAX_URL = 2_000;
  private static final Set<String> KINDS =
      Set.of("paragraph", "heading", "quote", "list", "item", "pre", "rule");

  private int blocks;
  private int chars;

  private RichText() {}

  static ViewComponent.Rich convert(Object value, Object blocked, String imagesParam) {
    RichText r = new RichText();
    List<ViewComponent.Block> out = r.blocks(value, 0);
    int count =
        blocked instanceof Number n ? (int) Math.max(0, Math.min(n.longValue(), 100_000)) : 0;
    return new ViewComponent.Rich(out, count, imagesParam);
  }

  private List<ViewComponent.Block> blocks(Object value, int depth) {
    List<ViewComponent.Block> out = new ArrayList<>();
    if (!(value instanceof List<?> list) || depth > MAX_DEPTH) {
      return out;
    }
    for (Object o : list) {
      if (blocks >= MAX_BLOCKS || chars >= MAX_CHARS) {
        break;
      }
      if (!(o instanceof Map<?, ?> m)
          || !(m.get("kind") instanceof String kind)
          || !KINDS.contains(kind)) {
        continue;
      }
      blocks++;
      int level =
          m.get("level") instanceof Number n ? (int) Math.max(0, Math.min(n.longValue(), 6)) : 0;
      boolean ordered = Boolean.TRUE.equals(m.get("ordered"));
      out.add(
          new ViewComponent.Block(
              kind, level, ordered, runs(m.get("runs")), blocks(m.get("children"), depth + 1)));
    }
    return out;
  }

  private List<ViewComponent.Run> runs(Object value) {
    List<ViewComponent.Run> out = new ArrayList<>();
    if (!(value instanceof List<?> list)) {
      return out;
    }
    for (Object o : list) {
      if (chars >= MAX_CHARS) {
        break;
      }
      if (!(o instanceof Map<?, ?> m) || !(m.get("text") instanceof String text)) {
        continue;
      }
      if (chars + text.length() > MAX_CHARS) {
        text = text.substring(0, MAX_CHARS - chars);
      }
      chars += text.length();
      out.add(
          new ViewComponent.Run(
              text,
              Boolean.TRUE.equals(m.get("bold")),
              Boolean.TRUE.equals(m.get("italic")),
              Boolean.TRUE.equals(m.get("code")),
              Boolean.TRUE.equals(m.get("muted")),
              address(m.get("link"), "http://", "https://", "mailto:"),
              address(m.get("image"), "https://")));
    }
    return out;
  }

  /**
   * The address if it starts with one of the schemes and has no white space or control character.
   */
  static String address(Object value, String... schemes) {
    if (!(value instanceof String s) || s.isEmpty() || s.length() > MAX_URL) {
      return null;
    }
    if (s.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c))) {
      return null;
    }
    String lower = s.toLowerCase(Locale.ROOT);
    for (String scheme : schemes) {
      if (lower.startsWith(scheme)) {
        return s;
      }
    }
    return null;
  }
}
