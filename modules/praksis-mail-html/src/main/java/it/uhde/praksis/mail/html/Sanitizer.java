// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.mail.html;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Turns the HTML of a mail into a description that can only show text: a list of blocks, each with
 * a few kinds ({@code paragraph}, {@code heading}, {@code quote}, {@code list}, {@code item},
 * {@code pre}, {@code rule}) and runs of text with {@code bold}, {@code italic}, {@code code}, a
 * {@code link} and, if the caller allowed remote images, an {@code image}. Nothing else gets
 * through, by construction and not by filtering: there is no place in the description for a script,
 * a style, a form, a frame, an event handler or a remote resource, because the output is built from
 * the text of the page and a handful of known tags, never copied from it. Links keep only the
 * schemes http, https and mailto; an image is a link to an https address and only when asked for,
 * and otherwise its alternative text takes its place.
 *
 * <p>The result is bounded: a hostile page cannot make it larger than {@link #MAX_CHARS} characters
 * or {@link #MAX_BLOCKS} blocks or nest deeper than {@link #MAX_DEPTH}.
 *
 * <p>A block is a map with {@code kind}, {@code level} (headings, from 1; else 0), {@code ordered}
 * (lists), {@code runs} (a list of maps with {@code text} and, when set, {@code bold}, {@code
 * italic}, {@code code}, {@code muted}, {@code link}, {@code image}) and {@code children} (a list
 * of blocks, for {@code quote}, {@code list} and {@code item}).
 */
public final class Sanitizer {
  /** The most text in a description. */
  public static final int MAX_CHARS = 400_000;

  /** The most blocks in a description, counted over all levels. */
  public static final int MAX_BLOCKS = 5_000;

  /** The deepest nesting of quotes and lists. */
  public static final int MAX_DEPTH = 12;

  private static final int MAX_URL = 2_000;

  /** The deepest element of the page that is looked at; a page nested deeper is cut. */
  private static final int MAX_DOM_DEPTH = 150;

  /** Tags whose whole content is dropped. */
  private static final Set<String> DROPPED =
      Set.of(
          "script",
          "style",
          "head",
          "title",
          "meta",
          "link",
          "base",
          "object",
          "embed",
          "iframe",
          "frame",
          "frameset",
          "form",
          "input",
          "button",
          "select",
          "option",
          "textarea",
          "svg",
          "math",
          "canvas",
          "audio",
          "video",
          "source",
          "track",
          "noscript",
          "template",
          "applet",
          "map",
          "area",
          "dialog",
          "datalist",
          "output",
          "meter",
          "progress");

  /** Tags that start a new block of text and are otherwise transparent. */
  private static final Set<String> BLOCKS =
      Set.of(
          "p",
          "div",
          "section",
          "article",
          "header",
          "footer",
          "main",
          "aside",
          "nav",
          "center",
          "address",
          "figure",
          "figcaption",
          "details",
          "summary",
          "table",
          "tbody",
          "thead",
          "tfoot",
          "tr",
          "td",
          "th",
          "caption",
          "dl",
          "dt",
          "dd",
          "fieldset",
          "legend",
          "body",
          "html");

  /**
   * The text of a description as plain text, for a mail that has no plain-text part: paragraphs and
   * headings separated by blank lines, items behind "- ", quotes behind "&gt; ".
   */
  public static String plain(List<Map<String, Object>> blocks) {
    StringBuilder out = new StringBuilder();
    plain(blocks, "", out);
    return out.toString().strip();
  }

  @SuppressWarnings("unchecked")
  private static void plain(List<Map<String, Object>> blocks, String indent, StringBuilder out) {
    for (Map<String, Object> b : blocks) {
      StringBuilder text = new StringBuilder();
      for (Map<String, Object> run : (List<Map<String, Object>>) b.get("runs")) {
        text.append(run.get("text"));
      }
      String kind = (String) b.get("kind");
      List<Map<String, Object>> children = (List<Map<String, Object>>) b.get("children");
      switch (kind) {
        case "rule" -> out.append(indent).append("----\n\n");
        case "quote" -> {
          StringBuilder inner = new StringBuilder();
          if (!text.isEmpty()) {
            inner.append(text).append("\n\n");
          }
          plain(children, "", inner);
          for (String line : inner.toString().stripTrailing().split("\n", -1)) {
            out.append(indent).append(line.startsWith(">") ? ">" : "> ").append(line).append('\n');
          }
          out.append('\n');
        }
        case "list" -> {
          plain(children, indent, out);
          out.append('\n');
        }
        case "item" -> {
          StringBuilder inner = new StringBuilder();
          if (!text.isEmpty()) {
            inner.append(text.toString().replace("\n", " ")).append('\n');
          }
          plain(children, "", inner);
          String[] lines = inner.toString().strip().split("\n", -1);
          for (int i = 0; i < lines.length; i++) {
            if (lines[i].isEmpty() && i + 1 < lines.length && lines[i + 1].startsWith("- ")) {
              continue; // a nested list follows its item without a gap
            }
            out.append(indent);
            if (i == 0) {
              out.append("- ").append(lines[i]);
            } else if (!lines[i].isEmpty()) {
              out.append("  ").append(lines[i]);
            }
            out.append('\n');
          }
        }
        default -> {
          if (!text.isEmpty()) {
            for (String line : text.toString().split("\n", -1)) {
              out.append(indent).append(line).append('\n');
            }
            out.append('\n');
          }
          plain(children, indent, out);
        }
      }
    }
  }

  /** What came out of a page. */
  public record Result(List<Map<String, Object>> blocks, int blockedImages) {}

  private final boolean allowImages;
  private int chars;
  private int blocks;
  private int blocked;
  private boolean cut;
  private int dom;

  private Sanitizer(boolean allowImages) {
    this.allowImages = allowImages;
  }

  /**
   * Sanitizes a page.
   *
   * @param html the HTML of the mail, as it came
   * @param allowImages whether images at https addresses are described as images (the client then
   *     loads them) or, as by default, replaced by their alternative text
   */
  public static Result sanitize(String html, boolean allowImages) {
    Sanitizer s = new Sanitizer(allowImages);
    List<Map<String, Object>> out = new ArrayList<>();
    Current current = new Current();
    s.walk(Jsoup.parse(html).body(), new Style(false, false, false, null), out, current, 0, false);
    s.flush(out, current);
    if (s.cut) {
      Map<String, Object> note = block("paragraph", 0);
      runs(note)
          .add(
              run(
                  "[the rest of this mail is not shown]",
                  new Style(false, false, false, null),
                  true));
      out.add(note);
    }
    return new Result(out, s.blocked);
  }

  /** The runs of the paragraph that is being filled. */
  private static final class Current {
    String kind = "paragraph";
    int level;
    final List<Map<String, Object>> runs = new ArrayList<>();

    boolean blank() {
      return runs.stream()
          .allMatch(r -> ((String) r.get("text")).isBlank() && !r.containsKey("image"));
    }
  }

  private record Style(boolean bold, boolean italic, boolean code, String link) {
    Style with(boolean b, boolean i, boolean c, String l) {
      return new Style(bold || b, italic || i, code || c, l != null ? l : link);
    }
  }

  private void walk(
      Node node, Style style, List<Map<String, Object>> out, Current cur, int depth, boolean pre) {
    if (cut) {
      return;
    }
    if (node instanceof TextNode text) {
      text(pre ? text.getWholeText() : collapse(text.getWholeText(), cur), style, cur, false);
      return;
    }
    if (!(node instanceof Element e)) {
      return; // comments, data, doctype: never shown
    }
    String tag = e.normalName();
    if (DROPPED.contains(tag) || hidden(e)) {
      return;
    }
    if (dom >= MAX_DOM_DEPTH) {
      cut = true;
      return;
    }
    dom++;
    try {
      element(e, tag, style, out, cur, depth, pre);
    } finally {
      dom--;
    }
  }

  private void element(
      Element e,
      String tag,
      Style style,
      List<Map<String, Object>> out,
      Current cur,
      int depth,
      boolean pre) {
    switch (tag) {
      case "br" -> text("\n", style, cur, false);
      case "hr" -> {
        flush(out, cur);
        add(out, block("rule", 0));
      }
      case "h1", "h2", "h3", "h4", "h5", "h6" -> {
        flush(out, cur);
        cur.kind = "heading";
        cur.level = tag.charAt(1) - '0';
        children(e, style.with(true, false, false, null), out, cur, depth, false);
        flush(out, cur);
      }
      case "blockquote" -> {
        flush(out, cur);
        container("quote", false, e, style, out, depth);
      }
      case "ul", "ol" -> {
        flush(out, cur);
        container("list", tag.equals("ol"), e, style, out, depth);
      }
      case "li" -> {
        // an item outside of a list is shown as a paragraph
        flush(out, cur);
        children(e, style, out, cur, depth, false);
        flush(out, cur);
      }
      case "pre" -> {
        flush(out, cur);
        cur.kind = "pre";
        children(e, style.with(false, false, true, null), out, cur, depth, true);
        flush(out, cur);
      }
      case "img" -> image(e, style, cur);
      case "a" -> {
        String href = link(e.attr("href"));
        children(e, style.with(false, false, false, href), out, cur, depth, pre);
      }
      case "b", "strong" -> children(e, style.with(true, false, false, null), out, cur, depth, pre);
      case "i", "em", "cite", "dfn", "var" ->
          children(e, style.with(false, true, false, null), out, cur, depth, pre);
      case "code", "tt", "kbd", "samp" ->
          children(e, style.with(false, false, true, null), out, cur, depth, pre);
      default -> {
        if (BLOCKS.contains(tag)) {
          flush(out, cur);
          children(e, style, out, cur, depth, pre);
          flush(out, cur);
        } else {
          children(e, style, out, cur, depth, pre); // span, font, u, small, ...: only their text
        }
      }
    }
  }

  private void children(
      Element e, Style style, List<Map<String, Object>> out, Current cur, int depth, boolean pre) {
    for (Node child : e.childNodes()) {
      walk(child, style, out, cur, depth, pre);
    }
  }

  /** A quote or a list: its content is a list of blocks of its own. */
  private void container(
      String kind,
      boolean ordered,
      Element e,
      Style style,
      List<Map<String, Object>> out,
      int depth) {
    if (depth >= MAX_DEPTH) {
      // too deep: its text is shown, flat
      Current flat = new Current();
      children(e, style, out, flat, depth, false);
      flush(out, flat);
      return;
    }
    Map<String, Object> box = block(kind, 0);
    box.put("ordered", ordered);
    List<Map<String, Object>> inner = children(box);
    if (kind.equals("list")) {
      for (Node child : e.childNodes()) {
        if (child instanceof Element li && li.normalName().equals("li")) {
          Map<String, Object> item = block("item", 0);
          List<Map<String, Object>> content = children(item);
          Current cur = new Current();
          children(li, style, content, cur, depth + 1, false);
          flush(content, cur);
          inner.add(item);
          count();
        } else if (child instanceof Element nested && !DROPPED.contains(nested.normalName())) {
          Current cur = new Current();
          walk(child, style, inner, cur, depth + 1, false);
          flush(inner, cur);
        } else if (child instanceof TextNode t && !t.isBlank()) {
          Current cur = new Current();
          walk(child, style, inner, cur, depth + 1, false);
          flush(inner, cur);
        }
      }
    } else {
      Current cur = new Current();
      children(e, style, inner, cur, depth + 1, false);
      flush(inner, cur);
    }
    if (!inner.isEmpty()) {
      add(out, box);
    }
  }

  private void image(Element e, Style style, Current cur) {
    String alt = e.attr("alt").strip();
    String src = clean(e.attr("src"));
    boolean https = src.regionMatches(true, 0, "https://", 0, 8) && src.length() <= MAX_URL;
    boolean tiny = small(e.attr("width")) || small(e.attr("height"));
    if (https && allowImages) {
      Map<String, Object> run = run("", style, false);
      run.put("image", src);
      if (!alt.isEmpty()) {
        run.put("text", alt);
      }
      cur.runs.add(run);
      return;
    }
    if (https) {
      blocked++;
    }
    if (tiny) {
      return; // a tracking pixel is not worth a line of its own
    }
    text(alt.isEmpty() ? "[image]" : "[image: " + alt + "]", style, cur, true);
  }

  private static boolean small(String size) {
    try {
      return Integer.parseInt(size.strip().replace("px", "")) <= 2;
    } catch (NumberFormatException e) {
      return false;
    }
  }

  /** The address of a link if it is one that may be shown as a link, else null. */
  private static String link(String href) {
    String clean = clean(href);
    String lower = clean.toLowerCase(Locale.ROOT);
    if (clean.isEmpty() || clean.length() > MAX_URL) {
      return null;
    }
    return lower.startsWith("http://")
            || lower.startsWith("https://")
            || lower.startsWith("mailto:")
        ? clean
        : null;
  }

  /** Browsers ignore white space and control characters inside an address; so is it done here. */
  private static String clean(String url) {
    return url.replaceAll("[\\p{Cntrl}\\s]", "");
  }

  private static boolean hidden(Element e) {
    if (e.hasAttr("hidden")) {
      return true;
    }
    String style = e.attr("style").toLowerCase(Locale.ROOT).replaceAll("\\s", "");
    return style.contains("display:none") || style.contains("visibility:hidden");
  }

  /** White space collapsed as HTML does, and no space at the start of a line. */
  private static String collapse(String text, Current cur) {
    String c = text.replaceAll("[ \\t\\r\\n\\f]+", " ");
    if (!c.isEmpty() && c.charAt(0) == ' ') {
      boolean atStart = cur.runs.isEmpty();
      if (!atStart) {
        String last = (String) cur.runs.get(cur.runs.size() - 1).get("text");
        atStart = last.endsWith(" ") || last.endsWith("\n");
      }
      if (atStart) {
        c = c.substring(1);
      }
    }
    return c;
  }

  private void text(String text, Style style, Current cur, boolean muted) {
    if (text.isEmpty()) {
      return;
    }
    if (chars + text.length() > MAX_CHARS) {
      text = text.substring(0, Math.max(0, MAX_CHARS - chars));
      cut = true;
    }
    chars += text.length();
    if (!cur.runs.isEmpty()) {
      Map<String, Object> last = cur.runs.get(cur.runs.size() - 1);
      if (!last.containsKey("image") && same(last, style, muted)) {
        last.put("text", last.get("text") + text);
        return;
      }
    }
    cur.runs.add(run(text, style, muted));
  }

  private static boolean same(Map<String, Object> run, Style style, boolean muted) {
    return Boolean.TRUE.equals(run.get("bold")) == style.bold()
        && Boolean.TRUE.equals(run.get("italic")) == style.italic()
        && Boolean.TRUE.equals(run.get("code")) == style.code()
        && Boolean.TRUE.equals(run.get("muted")) == muted
        && java.util.Objects.equals(run.get("link"), style.link());
  }

  private static Map<String, Object> run(String text, Style style, boolean muted) {
    Map<String, Object> run = new LinkedHashMap<>();
    run.put("text", text);
    if (style.bold()) {
      run.put("bold", true);
    }
    if (style.italic()) {
      run.put("italic", true);
    }
    if (style.code()) {
      run.put("code", true);
    }
    if (muted) {
      run.put("muted", true);
    }
    if (style.link() != null) {
      run.put("link", style.link());
    }
    return run;
  }

  private static Map<String, Object> block(String kind, int level) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("kind", kind);
    b.put("level", (long) level);
    b.put("runs", new ArrayList<Map<String, Object>>());
    b.put("children", new ArrayList<Map<String, Object>>());
    return b;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> runs(Map<String, Object> block) {
    return (List<Map<String, Object>>) block.get("runs");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> children(Map<String, Object> block) {
    return (List<Map<String, Object>>) block.get("children");
  }

  private void count() {
    if (++blocks > MAX_BLOCKS) {
      cut = true;
    }
  }

  private void add(List<Map<String, Object>> out, Map<String, Object> b) {
    if (cut) {
      return;
    }
    if (++blocks > MAX_BLOCKS) {
      cut = true;
      return;
    }
    out.add(b);
  }

  /** Closes the paragraph that is being filled; one with nothing but white space is dropped. */
  private void flush(List<Map<String, Object>> out, Current cur) {
    if (!cur.blank()) {
      List<Map<String, Object>> runs = cur.runs;
      // trailing white space of the last run
      Map<String, Object> last = runs.get(runs.size() - 1);
      if (!last.containsKey("image") && !cur.kind.equals("pre")) {
        last.put("text", ((String) last.get("text")).stripTrailing());
      }
      Map<String, Object> b = block(cur.kind, cur.level);
      runs(b).addAll(runs);
      add(out, b);
    }
    cur.runs.clear();
    cur.kind = "paragraph";
    cur.level = 0;
  }
}
