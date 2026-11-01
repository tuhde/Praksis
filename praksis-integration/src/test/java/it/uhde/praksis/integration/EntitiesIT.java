// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Entities against the runtime image: a person works with them through the CLI, and a rule reacts
 * to the events of the changes, with the grants and the audit log of any rule.
 */
class EntitiesIT {
  private static void write(Path file, String text) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  @Test
  void aPersonCreatesReadsAndChangesEntitiesAndARuleReactsToTheEvents() throws Exception {
    try (Harness h = new Harness()) {
      write(
          h.home.resolve("ruleset/entities/person.toml"),
          """
          format_version = 1
          id = "person"
          title = "Person"
          description = "Someone I know."

          [[field]]
          name = "name"
          type = "string"
          required = true
          unique = true

          [[field]]
          name = "age"
          type = "int"

          [[field]]
          name = "iban"
          type = "string"
          sensitive = true
          """);
      write(
          h.home.resolve("ruleset/entities/journal.toml"),
          """
          format_version = 1
          id = "journal"
          title = "Journal"

          [[field]]
          name = "text"
          type = "string"
          required = true
          """);
      write(
          h.home.resolve("ruleset/rules/person.journal.toml"),
          """
          id = "person.journal"
          effect = "local-write"
          needs = ["entity.create"]

          [trigger]
          kind = "event"
          type = "entity.created"
          filter = "event.data.type == 'person'"

          [[action]]
          id = "note"
          capability = "entity.create"
          args = { type = "journal", values = { cel = "{'text': 'met ' + event.data.id}" } }
          """);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "journal"
              layer = "ruleset"
              capability = "entity.create"
              [grant.args]
              type = { equals = "journal" }
              """);
      Process daemon = h.startDaemon();

      Harness.CliResult types = h.cli("entities", "types");
      assertThat(types.exit()).as(types.output()).isZero();
      assertThat(types.output())
          .contains("person  Person  [layer ruleset]")
          .contains("Someone I know.")
          .contains("name (string), required, unique")
          .contains("iban (string), sensitive");

      Harness.CliResult created =
          h.cli(
              "entities",
              "create",
              "person",
              "--set",
              "name=Mei",
              "--set",
              "age=41",
              "--set",
              "iban=DE01");
      assertThat(created.exit()).as(created.output()).isZero();
      assertThat(created.output())
          .contains("name: Mei")
          .contains("age: 41")
          .contains("created_by: user")
          .doesNotContain("DE01");
      Matcher m = Pattern.compile("(?m)^id: (\\S+)$").matcher(created.output());
      assertThat(m.find()).isTrue();
      String id = m.group(1);

      // the rule reacted to the event of the change
      Harness.await(
          "the rule to write the journal",
          () -> h.cli("entities", "list", "journal").output().contains("text=met " + id));
      String journalId =
          Pattern.compile("(?m)^(\\S+)  text=met")
              .matcher(h.cli("entities", "list", "journal").output())
              .results()
              .findFirst()
              .orElseThrow()
              .group(1);
      assertThat(h.cli("entities", "show", "journal", journalId).output())
          .contains("created_by: rule:person.journal");

      // a secret is shown only on request, and asking is logged
      assertThat(h.cli("entities", "show", "person", id).output()).doesNotContain("DE01");
      Harness.CliResult revealed = h.cli("entities", "show", "person", id, "--reveal", "iban");
      assertThat(revealed.output()).contains("iban: DE01  (revealed)");
      assertThat(h.cli("entities", "show", "person", id, "--reveal", "name").exit()).isEqualTo(1);

      // lists, filters, changes and mistakes
      assertThat(
              h.cli(
                      "entities",
                      "list",
                      "person",
                      "--filter",
                      "{\"op\":\"range\",\"field\":\"age\",\"gte\":18}")
                  .output())
          .contains(id + "  name=Mei  age=41")
          .contains("1 of 1");
      assertThat(h.cli("entities", "list", "person", "--filter", "not json").exit()).isEqualTo(2);
      assertThat(h.cli("entities", "update", id.toString(), "x").exit()).isNotZero();
      Harness.CliResult updated = h.cli("entities", "update", "person", id, "--set", "age=42");
      assertThat(updated.output()).contains("age: 42");
      Harness.CliResult clash =
          h.cli("entities", "create", "person", "--json", "{\"name\":\"Mei\"}");
      assertThat(clash.exit()).isEqualTo(1);
      assertThat(clash.output()).contains("conflict: another 'person' already has name = Mei");
      assertThat(h.cli("entities", "create", "person", "--set", "age=x").output())
          .contains("field 'age' must be a whole number");
      assertThat(h.cli("entities", "create", "person", "--set", "oops").exit()).isEqualTo(2);

      // the audit log names what the person did and holds no secret
      Harness.CliResult audit = h.cli("audit");
      assertThat(audit.output())
          .contains("entity.create")
          .contains("entity.reveal")
          .contains("entity.update")
          .doesNotContain("DE01");

      assertThat(h.cli("entities", "delete", "person", id).exit()).isZero();
      assertThat(h.cli("entities", "delete", "person", id).exit()).isEqualTo(1);
      assertThat(h.cli("entities", "list", "person").output()).contains("0 of 0");
      h.stop(daemon);
    }
  }
}
