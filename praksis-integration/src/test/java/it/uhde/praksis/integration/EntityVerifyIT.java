// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Entity types and the rules that use them, checked by {@code praksis verify} and {@code test}. */
class EntityVerifyIT {
  private static final String NOTE =
      """
      format_version = 1
      id = "note"
      title = "Note"

      [[field]]
      name = "subject"
      type = "string"
      required = true
      """;

  private static final String RULE =
      """
      id = "mail.note"
      effect = "local-write"
      needs = ["entity.create"]

      [trigger]
      kind = "event"
      type = "mail.received"

      [[action]]
      id = "make"
      capability = "entity.create"
      args = { type = "note", values = { subject = "from mail" } }
      """;

  private static final String TEST =
      """
      format_version = 1

      [[test]]
      name = "a mail makes a note"
      [test.event]
      type = "mail.received"
      data = { message = "m1" }
      [[test.call]]
      capability = "entity.create"
      args = { type = "note" }
      """;

  private static void write(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void typesAndTheirRulesAreVerifiedWithoutADaemon() throws Exception {
    try (Harness h = new Harness()) {
      write(h.home.resolve("ruleset/entities/note.toml"), NOTE);
      write(h.home.resolve("ruleset/rules/mail.note.toml"), RULE);
      write(h.home.resolve("ruleset/tests/mail.note.toml"), TEST);
      write(
          h.home.resolve("ruleset/tests/mail.archive.toml"),
          TEST.replace("entity.create", "mail.move")
              .replace("{ type = \"note\" }", "{ folder = \"Archive\" }"));
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "notes"
              layer = "ruleset"
              capability = "entity.create"
              [grant.args]
              type = { equals = "note" }
              """);
      Harness.CliResult good = h.cli("verify");
      assertThat(good.exit()).as(good.output()).isZero();
      assertThat(good.output()).contains("0 error(s), 0 warning(s)");

      // a type that does not exist, a field it does not have, and a value of the wrong type
      write(
          h.home.resolve("ruleset/rules/mail.note.toml"),
          RULE.replace(
              "type = \"note\", values = { subject = \"from mail\" }",
              "type = \"memo\", values = {}"));
      Harness.CliResult unknownType = h.cli("verify");
      assertThat(unknownType.exit()).isEqualTo(1);
      assertThat(unknownType.output())
          .containsPattern(
              "ERROR +entity +mail\\.note, action 'make': entity\\.create: there is no entity type 'memo'");

      write(
          h.home.resolve("ruleset/rules/mail.note.toml"),
          RULE.replace("subject = \"from mail\"", "subject = 5, colour = \"red\""));
      Harness.CliResult fields = h.cli("verify");
      assertThat(fields.output())
          .contains("type 'note' has no field 'colour'")
          .contains("field 'subject' must be of type string");

      // a file that is not a valid type stops the checks of the layer, with the file named
      write(h.home.resolve("ruleset/rules/mail.note.toml"), RULE);
      write(
          h.home.resolve("ruleset/entities/broken.toml"), "format_version = 1\nid = \"broken\"\n");
      Harness.CliResult broken = h.cli("verify");
      assertThat(broken.exit()).isEqualTo(1);
      assertThat(broken.output())
          .containsPattern(
              "ERROR +entity +layer 'ruleset': entities/broken\\.toml: missing 'title'");
    }
  }

  @Test
  void aDaemonDoesNotStartWithAnInvalidType() throws Exception {
    try (Harness h = new Harness()) {
      write(h.home.resolve("ruleset/entities/note.toml"), "format_version = 1\nid = \"note\"\n");
      Harness.CliResult start = h.cli("start");
      assertThat(start.exit()).isNotZero();
      assertThat(start.output())
          .contains("the entity types are not valid")
          .contains("entities/note.toml");
    }
  }
}
