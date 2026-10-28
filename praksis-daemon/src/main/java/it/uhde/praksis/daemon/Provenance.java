// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.git.GitSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Who wrote the current version of a rule, as far as the history of its layer tells. This is
 * information for the person, not proof: the classification as AI comes from the commit message, so
 * it is only as honest as whoever wrote the commit.
 *
 * @param layer the layer that defines the rule
 * @param file the file in that layer, e.g. {@code patch/mail.20-lists.toml}
 * @param change the last commit that changed the file, empty for a layer that is a directory or
 *     whose history is not at hand
 */
record Provenance(String layer, String file, Optional<GitSource.Change> change) {
  /** How the commit says it was made. */
  enum Author {
    /** A trailer {@code Praksis-Author: ai}, or a {@code Co-Authored-By} of an AI assistant. */
    AI,
    /** A commit without such a mark. */
    HUMAN,
    /** No history: a directory layer, or a repository that is not at hand. */
    UNKNOWN;

    String label() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }

  Author author() {
    if (change.isEmpty()) {
      return Author.UNKNOWN;
    }
    for (String line : change.get().body().split("\\R")) {
      String l = line.strip().toLowerCase(java.util.Locale.ROOT);
      if (l.equals("praksis-author: ai")
          || (l.startsWith("co-authored-by:")
              && (l.contains("noreply@anthropic.com") || l.contains("claude")))) {
        return Author.AI;
      }
    }
    return Author.HUMAN;
  }

  /** The message of the commit without its trailers: why the change was made. */
  String reasoning() {
    if (change.isEmpty()) {
      return "";
    }
    List<String> kept = new ArrayList<>();
    for (String line : change.get().body().split("\\R")) {
      String l = line.strip().toLowerCase(java.util.Locale.ROOT);
      if (l.startsWith("praksis-")
          || l.startsWith("co-authored-by:")
          || l.startsWith("signed-off-by:")) {
        continue;
      }
      kept.add(line.stripTrailing());
    }
    String text = String.join("\n", kept).strip();
    return text.length() > 1000 ? text.substring(0, 1000) + "..." : text;
  }

  /** One line: who, in which commit, and the subject. */
  String describe() {
    if (change.isEmpty()) {
      return "(no history for layer '" + layer + "': a directory, or the commit is not at hand)";
    }
    GitSource.Change c = change.get();
    return "last changed by "
        + c.authorName()
        + " <"
        + c.authorEmail()
        + "> ("
        + author().label()
        + ") in "
        + c.commit().substring(0, Math.min(10, c.commit().length()))
        + ": "
        + c.subject();
  }
}
