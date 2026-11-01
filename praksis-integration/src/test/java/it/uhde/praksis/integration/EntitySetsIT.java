// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Entity sets against the runtime image: grants that name a set, and what a change adds to one. */
class EntitySetsIT {
  private static String type(String id) {
    return "format_version = 1\nid = \""
        + id
        + "\"\ntitle = \""
        + id
        + "\"\n[[field]]\nname = \"text\"\ntype = \"string\"\n";
  }

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static void copy(Path from, Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      for (Path f : files.sorted(Comparator.naturalOrder()).toList()) {
        Path target = to.resolve(from.relativize(f).toString());
        if (Files.isDirectory(f)) {
          Files.createDirectories(target);
        } else {
          Files.copy(f, target);
        }
      }
    }
  }

  @Test
  void aSetThatGrowsIsNewAccessForTheGrantsThatNameItAndTheSetsAreListed() throws Exception {
    try (Harness h = new Harness()) {
      Path ruleset = h.home.resolve("ruleset");
      write(ruleset.resolve("entities/note.toml"), type("note"));
      write(ruleset.resolve("entities/memo.toml"), type("memo"));
      write(
          ruleset.resolve("entity-sets/docs.toml"),
          "format_version = 1\nid = \"docs\"\ntitle = \"Documents\"\ndescription = \"What I write.\"\n"
              + "types = [\"note\"]\n");
      write(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "read-docs"
              layer = "ruleset"
              capability = "entity.read"
              [grant.args]
              type = { set = "docs" }
              """);
      write(
          ruleset.resolve("tests/mail.archive.toml"),
          """
          format_version = 1

          [[test]]
          name = "mail is archived"
          [test.event]
          type = "mail.received"
          data = { message = "m1" }
          [[test.call]]
          capability = "mail.move"
          args = { folder = "Archive" }
          """);
      Harness.CliResult good = h.cli("verify");
      assertThat(good.exit()).as(good.output()).isZero();

      // a candidate that puts the type memo into the set: the grant would cover it
      Path candidate = Files.createDirectories(h.home.resolve("candidate"));
      copy(ruleset, candidate);
      write(
          candidate.resolve("entity-sets/docs.toml"),
          "format_version = 1\nid = \"docs\"\ntitle = \"Documents\"\ntypes = [\"note\", \"memo\"]\n");
      Harness.CliResult grown = h.cli("verify", "--layer", "ruleset=" + candidate);
      assertThat(grown.exit()).as(grown.output()).isEqualTo(1);
      assertThat(grown.output())
          .containsPattern(
              "ERROR +access +entity-sets: new access: the set 'docs' gains the type 'memo': grant"
                  + " 'read-docs' \\(layer ruleset, entity\\.read\\) would cover it");

      // a grant that names a set that does not exist is a mistake of the policy
      write(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "read-ghosts"
              layer = "ruleset"
              capability = "entity.read"
              [grant.args]
              type = { set = "ghosts" }
              """);
      Harness.CliResult ghost = h.cli("verify");
      assertThat(ghost.exit()).isEqualTo(1);
      assertThat(ghost.output())
          .contains("grant 'read-ghosts'")
          .contains("no entity set named 'ghosts' (sets: [docs])");

      // a set that holds a type that does not exist, and the sets in each other
      write(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              .replaceAll("(?s)\\n\\[\\[grant\\]\\]\\nid = \"read-ghosts\".*", "\n"));
      write(
          ruleset.resolve("entity-sets/loop.toml"),
          "format_version = 1\nid = \"loop\"\ntitle = \"Loop\"\ntypes = [\"nothing\"]\nsets = [\"loop2\"]\n");
      write(
          ruleset.resolve("entity-sets/loop2.toml"),
          "format_version = 1\nid = \"loop2\"\ntitle = \"Loop 2\"\nsets = [\"loop\"]\n");
      Harness.CliResult broken = h.cli("verify");
      assertThat(broken.exit()).isEqualTo(1);
      assertThat(broken.output())
          .contains("entity-sets/loop.toml")
          .contains("'nothing', which is not an entity type")
          .contains("the sets contain each other");
      Files.delete(ruleset.resolve("entity-sets/loop.toml"));
      Files.delete(ruleset.resolve("entity-sets/loop2.toml"));

      // a running daemon lists the sets and says which sets hold a type
      Process daemon = h.startDaemon();
      Harness.CliResult sets = h.cli("entities", "sets");
      assertThat(sets.exit()).as(sets.output()).isZero();
      assertThat(sets.output())
          .contains("docs  Documents  [layer ruleset]")
          .contains("What I write.")
          .contains("types: note")
          .contains("holds: note");
      assertThat(h.cli("entities", "types").output()).contains("in the sets: docs");
      h.stop(daemon);
    }
  }
}
