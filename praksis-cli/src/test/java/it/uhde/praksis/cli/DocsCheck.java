// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Checks the documentation under {@code docs/}: every page is in the index and every entry of the
 * index exists, every page starts with a title, relative links point at pages that exist, and every
 * module of the repository has its generated page. {@code PLAN.md} is the design log and is not
 * checked.
 */
final class DocsCheck {
  private static final Pattern ENTRY = Pattern.compile("^- \\[[^\\]]+\\]\\(([^)]+\\.md)\\)\\s*$");
  private static final Pattern LINK = Pattern.compile("\\]\\(([^)\\s]+)\\)");
  private static final Pattern INLINE_CODE = Pattern.compile("`[^`]*`");
  private static final Pattern HTML = Pattern.compile("<[a-zA-Z/!]");
  private static final Pattern NESTED_LIST = Pattern.compile("^\\s+[-*]\\s+\\S");
  private static final Pattern MODULE_NAME =
      Pattern.compile("^name\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);

  private DocsCheck() {}

  /** The problems found; empty if the documentation is in order. */
  static List<String> problems(Path docs, Path modulesRoot) throws IOException {
    List<String> problems = new ArrayList<>();
    Path index = docs.resolve("index.md");
    if (!Files.isRegularFile(index)) {
      return List.of("docs/index.md is missing");
    }
    List<String> listed = new ArrayList<>();
    for (String line : Files.readAllLines(index)) {
      Matcher m = ENTRY.matcher(line);
      if (m.matches()) {
        listed.add(m.group(1));
      }
    }
    for (String page : listed) {
      if (!Files.isRegularFile(docs.resolve(page))) {
        problems.add("index.md lists " + page + ", which does not exist");
      }
    }
    try (Stream<Path> files = Files.walk(docs)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".md")).sorted().toList()) {
        String name = docs.relativize(file).toString().replace('\\', '/');
        if (name.equals("index.md") || name.equals("PLAN.md")) {
          continue;
        }
        if (!listed.contains(name)) {
          problems.add(name + " is not listed in index.md");
        }
      }
    }
    List<String> pages = new ArrayList<>(listed);
    pages.add("index.md");
    for (String page : pages) {
      Path file = docs.resolve(page);
      if (Files.isRegularFile(file)) {
        page(docs, page, Files.readAllLines(file), problems);
      }
    }
    if (modulesRoot != null && Files.isDirectory(modulesRoot)) {
      List<String> names = new ArrayList<>(List.of("praksis"));
      try (Stream<Path> dirs = Files.list(modulesRoot)) {
        for (Path toml : dirs.map(d -> d.resolve("module.toml")).sorted().toList()) {
          if (Files.isRegularFile(toml)) {
            Matcher m = MODULE_NAME.matcher(Files.readString(toml));
            if (m.find()) {
              names.add(m.group(1));
            }
          }
        }
      }
      for (String module : names) {
        if (!listed.contains("modules/" + module + ".md")) {
          problems.add("module " + module + " has no page modules/" + module + ".md in index.md");
        }
      }
    }
    return problems;
  }

  private static void page(Path docs, String name, List<String> lines, List<String> problems) {
    if (lines.isEmpty() || !lines.get(0).startsWith("# ")) {
      problems.add(name + " does not start with a title (# ...)");
    }
    boolean fenced = false;
    boolean comment = false;
    for (String line : lines) {
      if (line.startsWith("```") || line.startsWith("~~~")) {
        fenced = !fenced;
      }
      if (!fenced && !line.startsWith("    ")) {
        String text = INLINE_CODE.matcher(line).replaceAll("");
        if (comment || text.contains("<!--")) {
          comment = !text.contains("-->");
        } else if (HTML.matcher(text).find()) {
          problems.add(name + " has HTML, which the help does not show: " + line.strip());
        }
        if (text.contains("![")) {
          problems.add(name + " has an image, which the help does not show: " + line.strip());
        }
        if (NESTED_LIST.matcher(line).find()) {
          problems.add(name + " has a nested list, which the help does not show: " + line.strip());
        }
      }
      if (fenced || line.startsWith("    ") || name.equals("index.md")) {
        continue;
      }
      Matcher m = LINK.matcher(line);
      while (m.find()) {
        String target = m.group(1);
        if (target.startsWith("#") || target.contains(":")) {
          continue;
        }
        String path = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        Path resolved = docs.resolve(name).getParent().resolve(path).normalize();
        if (!resolved.startsWith(docs.normalize()) || !Files.exists(resolved)) {
          problems.add(name + " links to " + target + ", which does not exist");
        }
      }
    }
  }
}
