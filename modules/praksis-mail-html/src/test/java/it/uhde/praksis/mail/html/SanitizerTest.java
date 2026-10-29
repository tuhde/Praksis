// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.mail.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SanitizerTest {
  private static final Set<String> BLOCK_KEYS =
      Set.of("kind", "level", "ordered", "runs", "children");
  private static final Set<String> RUN_KEYS =
      Set.of("text", "bold", "italic", "code", "muted", "link", "image");
  private static final Set<String> KINDS =
      Set.of("paragraph", "heading", "quote", "list", "item", "pre", "rule");

  /** Everything that came out has the known shape, and no address but http(s) and mailto. */
  @SuppressWarnings("unchecked")
  private static void assertSafe(List<Map<String, Object>> blocks) {
    for (Map<String, Object> b : blocks) {
      assertThat(BLOCK_KEYS).containsAll(b.keySet());
      assertThat(KINDS).contains((String) b.get("kind"));
      for (Map<String, Object> r : (List<Map<String, Object>>) b.get("runs")) {
        assertThat(RUN_KEYS).containsAll(r.keySet());
        assertThat(r.get("text")).isInstanceOf(String.class);
        if (r.get("link") instanceof String l) {
          assertThat(l.toLowerCase()).matches("^(https?://|mailto:)[^\\s]*$");
        }
        if (r.get("image") instanceof String i) {
          assertThat(i.toLowerCase()).startsWith("https://");
        }
      }
      assertSafe((List<Map<String, Object>>) b.get("children"));
    }
  }

  private static String text(List<Map<String, Object>> blocks) {
    StringBuilder out = new StringBuilder();
    collect(blocks, out);
    return out.toString();
  }

  @SuppressWarnings("unchecked")
  private static void collect(List<Map<String, Object>> blocks, StringBuilder out) {
    for (Map<String, Object> b : blocks) {
      for (Map<String, Object> r : (List<Map<String, Object>>) b.get("runs")) {
        out.append(r.get("text"));
      }
      out.append('\n');
      collect((List<Map<String, Object>>) b.get("children"), out);
    }
  }

  private static Sanitizer.Result clean(String html) {
    Sanitizer.Result r = Sanitizer.sanitize(html, false);
    assertSafe(r.blocks());
    return r;
  }

  @Test
  void scriptsStylesFramesFormsAndEverythingThatLoadsAreGoneWithTheirContent() {
    String html =
        """
        <html><head><title>t</title><style>p { color: red }</style><script>alert(1)</script></head>
        <body onload="alert(2)">
        <p onclick="alert(3)" style="background:url(https://t.example/x)">Hello</p>
        <script>document.write('bad')</script>
        <iframe src="https://evil.example/">frame text</iframe>
        <object data="x"><param name="a" value="b">object text</object>
        <embed src="x"><form action="https://evil.example"><input value="typed">
        <button>press</button><textarea>area</textarea></form>
        <svg><script>alert(4)</script><text>svg text</text></svg>
        <math><mi>m</mi></math><video src="v">video text</video>
        <noscript>no script text</noscript><link rel="stylesheet" href="https://t.example/s.css">
        <p>World</p></body></html>
        """;
    String shown = text(clean(html).blocks());
    assertThat(shown).contains("Hello").contains("World");
    for (String gone :
        List.of(
            "alert",
            "bad",
            "frame text",
            "object text",
            "typed",
            "press",
            "area",
            "svg text",
            "video text",
            "no script text",
            "color: red",
            "evil.example")) {
      assertThat(shown).as(gone).doesNotContain(gone);
    }
  }

  @Test
  void onlyHttpHttpsAndMailtoAreLinksHoweverTheyAreDisguised() {
    String html =
        """
        <a href="https://ok.example/a?b=1&amp;c=2">https</a>
        <a href="HTTP://ok.example/">http</a>
        <a href="mailto:me@example.org">mail</a>
        <a href="javascript:alert(1)">js</a>
        <a href=" JaVaScRiPt:alert(1)">js2</a>
        <a href="java&#9;script:alert(1)">js3</a>
        <a href="&#106;avascript:alert(1)">js4</a>
        <a href="jav&#x0A;ascript:alert(1)">js5</a>
        <a href="data:text/html;base64,PHNjcmlwdD4=">data</a>
        <a href="file:///etc/passwd">file</a>
        <a href="vbscript:x">vb</a>
        <a href="/relative">rel</a>
        <a href="cid:part1">cid</a>
        <a href="//evil.example/">scheme relative</a>
        """;
    List<String> links = new ArrayList<>();
    for (var b : clean(html).blocks()) {
      for (var r : runs(b)) {
        if (r.get("link") != null) {
          links.add((String) r.get("link"));
        }
      }
    }
    assertThat(links)
        .containsExactly(
            "https://ok.example/a?b=1&c=2", "HTTP://ok.example/", "mailto:me@example.org");
  }

  @Test
  void imagesAreBlockedAndCountedUntilAllowed() {
    String html =
        """
        <p>Look <img src="https://t.example/p.png" alt="logo"> and
        <img src="https://t.example/pixel.gif" width="1" height="1">
        <img src="http://t.example/insecure.png" alt="plain">
        <img src="cid:abc" alt="inline"> <img src="data:image/png;base64,AAAA">
        <img src="javascript:alert(1)" alt="js"></p>
        """;
    Sanitizer.Result blocked = Sanitizer.sanitize(html, false);
    assertSafe(blocked.blocks());
    assertThat(blocked.blockedImages())
        .isEqualTo(2); // the two https ones; the rest cannot be loaded
    assertThat(text(blocked.blocks()))
        .contains("[image: logo]", "[image: plain]", "[image: inline]", "[image]", "[image: js]")
        .doesNotContain("pixel");

    Sanitizer.Result allowed = Sanitizer.sanitize(html, true);
    assertSafe(allowed.blocks());
    assertThat(allowed.blockedImages()).isZero();
    List<String> images = new ArrayList<>();
    for (var b : allowed.blocks()) {
      for (var r : runs(b)) {
        if (r.get("image") != null) {
          images.add((String) r.get("image"));
        }
      }
    }
    // only https, and never the others, even when images are allowed
    assertThat(images).containsExactly("https://t.example/p.png", "https://t.example/pixel.gif");
  }

  @Test
  void structureAndStylesSurviveAndWhiteSpaceIsCollapsed() {
    String html =
        """
        <h1>Title</h1>
        <p>One   two
        three <b>bold <i>both</i></b> <i>it</i> <code>c</code><br>next line</p>
        <ul><li>a</li><li>b<ul><li>nested</li></ul></li></ul>
        <ol><li>first</li></ol>
        <blockquote><p>quoted</p><blockquote>deeper</blockquote></blockquote>
        <pre>  keep
          this </pre>
        <hr>
        <table><tr><td>cell 1</td><td>cell 2</td></tr></table>
        """;
    var blocks = clean(html).blocks();
    assertThat(blocks.get(0)).containsEntry("kind", "heading").containsEntry("level", 1L);
    assertThat(text(blocks)).contains("One two three").contains("next line").contains("cell 1");
    Map<String, Object> para = blocks.get(1);
    assertThat(runs(para)).anyMatch(r -> r.containsKey("bold") && r.containsKey("italic"));
    assertThat(runs(para)).anyMatch(r -> r.containsKey("code"));
    assertThat(blocks).extracting(b -> b.get("kind")).contains("list", "quote", "pre", "rule");
    Map<String, Object> list =
        blocks.stream().filter(b -> b.get("kind").equals("list")).findFirst().get();
    assertThat(list).containsEntry("ordered", false);
    assertThat(children(list)).hasSize(2).allMatch(i -> i.get("kind").equals("item"));
    Map<String, Object> pre =
        blocks.stream().filter(b -> b.get("kind").equals("pre")).findFirst().get();
    assertThat(text(List.of(pre))).contains("  keep\n  this");
  }

  @Test
  void hiddenContentIsNotShown() {
    String shown =
        text(
            clean(
                    """
                    <div style="display:none">preheader junk</div>
                    <span style="visibility: hidden">invisible</span>
                    <p hidden>hidden attribute</p><p>visible</p>
                    """)
                .blocks());
    assertThat(shown)
        .contains("visible")
        .doesNotContain("junk")
        .doesNotContain("invisible")
        .doesNotContain("hidden attribute");
  }

  @Test
  void aHostilePageIsCutAndNeverBlowsUp() {
    // very deep nesting must not overflow the stack
    String deep = "<div>".repeat(20_000) + "deep text" + "</div>".repeat(20_000);
    assertSafe(Sanitizer.sanitize(deep, false).blocks());
    String quotes = "<blockquote>".repeat(500) + "inner" + "</blockquote>".repeat(500);
    assertSafe(Sanitizer.sanitize(quotes, false).blocks());
    String lists = "<ul><li>".repeat(500) + "inner" + "</li></ul>".repeat(500);
    Sanitizer.Result cutLists = Sanitizer.sanitize(lists, false);
    assertSafe(cutLists.blocks());
    assertThat(text(cutLists.blocks())).contains("the rest of this mail is not shown");
    // nested as deep as real mail is (tables in tables, quotes in quotes), the text is all there
    String real = "<ul><li>".repeat(40) + "inner" + "</li></ul>".repeat(40);
    assertThat(text(clean(real).blocks())).contains("inner");
    String realQuotes = "<blockquote>".repeat(40) + "quoted" + "</blockquote>".repeat(40);
    assertThat(text(clean(realQuotes).blocks())).contains("quoted");
    String tables = "<table><tr><td>".repeat(30) + "cell" + "</td></tr></table>".repeat(30);
    assertThat(text(clean(tables).blocks())).contains("cell");

    // lots of text and lots of blocks are cut at the limits, with a note
    String big = "<p>" + "x".repeat(Sanitizer.MAX_CHARS + 5000) + "</p>";
    Sanitizer.Result bigResult = Sanitizer.sanitize(big, false);
    assertSafe(bigResult.blocks());
    assertThat(text(bigResult.blocks()).length()).isLessThan(Sanitizer.MAX_CHARS + 200);
    assertThat(text(bigResult.blocks())).contains("the rest of this mail is not shown");
    String many = "<p>a</p>".repeat(Sanitizer.MAX_BLOCKS + 500);
    Sanitizer.Result manyResult = Sanitizer.sanitize(many, false);
    assertSafe(manyResult.blocks());
    assertThat(manyResult.blocks().size()).isLessThanOrEqualTo(Sanitizer.MAX_BLOCKS + 1);
  }

  @Test
  void anythingThatIsNotHtmlStillComesOutAsSafeText() {
    assertThat(clean("").blocks()).isEmpty();
    assertThat(text(clean("just text, no tags").blocks())).contains("just text, no tags");
    assertThat(
            text(
                clean("<<<>>> &nbsp; <unknown-tag foo=bar>odd</unknown-tag> &#0; &lt;b&gt;")
                    .blocks()))
        .contains("odd")
        .contains("<b>");
    assertThat(
            clean("<p>a</p><!-- <script>x</script> --><!--[if mso]><p>conditional</p><![endif]-->")
                .blocks())
        .hasSize(1);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> runs(Map<String, Object> block) {
    return (List<Map<String, Object>>) block.get("runs");
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> children(Map<String, Object> block) {
    return (List<Map<String, Object>>) block.get("children");
  }

  @Test
  void theDescriptionCanBeReadBackAsPlainText() {
    String html =
        """
        <h1>Title</h1><p>One <b>two</b><br>three</p>
        <ul><li>a</li><li>b<ul><li>nested</li></ul></li></ul>
        <blockquote><p>quoted</p><blockquote>deeper</blockquote></blockquote><hr><p>end</p>
        """;

    assertThat(Sanitizer.plain(clean(html).blocks()))
        .isEqualTo(
            "Title\n\nOne two\nthree\n\n- a\n- b\n  - nested\n\n> quoted\n> \n>> deeper\n\n----\n\nend");
    assertThat(Sanitizer.plain(List.of())).isEmpty();
  }
}
