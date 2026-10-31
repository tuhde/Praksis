// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.docs;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The documentation pages, copied from {@code docs/} into the resources at build time. The pages
 * are the ones listed in {@code docs/index.md}, in its order; a topic is the path of a page without
 * {@code .md}, for example {@code user/concepts}.
 */
public final class Docs {
  private static final Pattern ENTRY = Pattern.compile("^- \\[([^\\]]+)\\]\\(([^)]+)\\.md\\)\\s*$");

  /**
   * One page of the index.
   *
   * @param name the topic
   * @param title what the index calls it
   */
  public record Topic(String name, String title) {}

  private Docs() {}

  /** The pages of the index, in its order. */
  public static List<Topic> topics() {
    List<Topic> topics = new ArrayList<>();
    for (String line : read("index").split("\n")) {
      Matcher m = ENTRY.matcher(line);
      if (m.matches()) {
        topics.add(new Topic(m.group(2), m.group(1)));
      }
    }
    return topics;
  }

  /** The topic with this name, if the index lists it. */
  public static Optional<Topic> find(String name) {
    String bare = name.endsWith(".md") ? name.substring(0, name.length() - 3) : name;
    return topics().stream().filter(t -> t.name().equals(bare)).findFirst();
  }

  /**
   * The text of a page, including {@code index}.
   *
   * @throws IllegalStateException if the page is not part of this build
   */
  public static String read(String name) {
    try (InputStream in = Docs.class.getResourceAsStream("/docs/" + name + ".md")) {
      if (in == null) {
        throw new IllegalStateException(
            "The page docs/" + name + ".md is missing from this build.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
