// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Changes {@code policy.toml} as text, so what a person wrote there (comments, order, spacing)
 * stays as it is. A change is checked by loading the result before it replaces the file.
 */
public final class PolicyFile {
  private PolicyFile() {}

  /** The TOML text of a grant. */
  public static String block(Grant grant) {
    StringBuilder out = new StringBuilder("[[grant]]\n");
    out.append("id = ").append(quote(grant.id())).append('\n');
    out.append("layer = ").append(quote(grant.layer())).append('\n');
    out.append("capability = ").append(quote(grant.capability())).append('\n');
    grant.note().ifPresent(n -> out.append("note = ").append(quote(n)).append('\n'));
    if (!grant.args().isEmpty()) {
      out.append("[grant.args]\n");
      new java.util.TreeMap<>(grant.args())
          .forEach(
              (name, matcher) ->
                  out.append(name).append(" = { ").append(matcher.describe()).append(" }\n"));
    }
    return out.toString();
  }

  /** Adds a grant at the end of the file, which is created if there is none. */
  public static void append(Path file, Grant grant) throws IOException, RulesetException {
    String text = Files.exists(file) ? Files.readString(file) : "format_version = 1\n";
    if (!text.endsWith("\n")) {
      text += "\n";
    }
    write(file, text + "\n" + block(grant));
  }

  /**
   * Removes the grant with this id, including its {@code [grant.args]} table. Comments and blank
   * lines right above the next grant stay with that grant.
   *
   * @return false if there is no such grant
   */
  public static boolean remove(Path file, String id) throws IOException, RulesetException {
    if (!Files.exists(file)) {
      return false;
    }
    List<String> lines = new ArrayList<>(Files.readAllLines(file, StandardCharsets.UTF_8));
    Pattern idLine = Pattern.compile("\\s*id\\s*=\\s*\"" + Pattern.quote(id) + "\"\\s*(#.*)?");
    for (int start = 0; start < lines.size(); start++) {
      if (!lines.get(start).strip().equals("[[grant]]")) {
        continue;
      }
      int next = start + 1;
      boolean found = false;
      boolean inArgs = false;
      while (next < lines.size() && !lines.get(next).strip().equals("[[grant]]")) {
        String line = lines.get(next);
        if (line.strip().startsWith("[")) {
          inArgs = true;
        } else if (!inArgs && idLine.matcher(line).matches()) {
          found = true;
        }
        next++;
      }
      if (found) {
        int end = next;
        while (end > start + 1
            && (lines.get(end - 1).isBlank() || lines.get(end - 1).strip().startsWith("#"))) {
          end--;
        }
        lines.subList(start, end).clear();
        write(file, String.join("\n", lines) + "\n");
        return true;
      }
    }
    return false;
  }

  private static void write(Path file, String text) throws IOException, RulesetException {
    Path temp = file.resolveSibling(file.getFileName() + ".new");
    Files.writeString(temp, text);
    try {
      Policy.load(temp);
      Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } finally {
      Files.deleteIfExists(temp);
    }
  }

  /** A TOML basic string. */
  static String quote(String value) {
    StringBuilder out = new StringBuilder("\"");
    for (char c : value.toCharArray()) {
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    return out.append('"').toString();
  }
}
