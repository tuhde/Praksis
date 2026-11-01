// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.ruleset.Action;
import it.uhde.praksis.ruleset.Arg;
import it.uhde.praksis.ruleset.Rule;
import it.uhde.praksis.ruleset.Trigger;
import it.uhde.praksis.ruleset.Verifier.Finding;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EntityChecksTest {
  private static final EntityCatalog CATALOG =
      new EntityCatalog(
          Map.of(
              "person",
              EntityTypes.parse(
                      """
                      format_version = 1
                      id = "person"
                      title = "Person"
                      [[field]]
                      name = "name"
                      type = "string"
                      required = true
                      [[field]]
                      name = "age"
                      type = "int"
                      [[field]]
                      name = "iban"
                      type = "string"
                      sensitive = true
                      """,
                      "person",
                      new ArrayList<>())
                  .orElseThrow()));

  private static List<Finding> check(String capability, Map<String, Object> literals) {
    Map<String, Arg> args = new java.util.LinkedHashMap<>();
    literals.forEach((k, v) -> args.put(k, v instanceof Arg a ? a : new Arg.Literal(v)));
    Rule rule =
        new Rule(
            "r.1",
            Optional.empty(),
            EffectClass.LOCAL_WRITE,
            List.of(capability),
            false,
            new Trigger.OnEvent("mail.received", Optional.empty()),
            List.of(new Action.Call("a", capability, args)),
            List.of());
    return EntityChecks.check(List.of(rule), CATALOG);
  }

  private static void assertFinds(List<Finding> findings, String text) {
    assertThat(findings)
        .anyMatch(
            f ->
                f.message().contains(text)
                    && f.check().equals("entity")
                    && f.where().equals("r.1, action 'a'"));
  }

  @Test
  void aWrittenTypeMustExist() {
    assertFinds(
        check("entity.create", Map.of("type", "nope", "values", Map.of())),
        "no entity type 'nope'");
    assertThat(check("entity.create", Map.of("type", "person", "values", Map.of("name", "A"))))
        .isEmpty();
  }

  @Test
  void aComputedTypeOrValueIsLeftToTheRun() {
    assertThat(
            check(
                "entity.create", Map.of("type", new Arg.Expr("event.data.t"), "values", Map.of())))
        .isEmpty();
    assertThat(
            check(
                "entity.create", Map.of("type", "person", "values", new Arg.Expr("{'name': 'A'}"))))
        .isEmpty();
  }

  @Test
  void writtenValuesAreCheckedAgainstTheFields() {
    assertFinds(
        check("entity.create", Map.of("type", "person", "values", Map.of("name", "A", "x", 1))),
        "no field 'x'");
    assertFinds(
        check("entity.create", Map.of("type", "person", "values", Map.of("name", "A", "id", "i"))),
        "set by Praksis");
    assertFinds(
        check("entity.create", Map.of("type", "person", "values", Map.of("name", 5))),
        "must be of type string");
    assertFinds(
        check("entity.create", Map.of("type", "person", "values", Map.of("age", 5L))),
        "'name' is required");
    assertFinds(
        check("entity.update", Map.of("type", "person", "id", "i", "values", Map.of("age", "x"))),
        "must be of type int");
    assertThat(
            check(
                "entity.update", Map.of("type", "person", "id", "i", "values", Map.of("age", 5L))))
        .isEmpty();
  }

  @Test
  void writtenFiltersAndSortsAreChecked() {
    assertFinds(
        check("entity.query", Map.of("type", "person", "filter", Map.of("op", "like"))),
        "unknown op");
    assertFinds(
        check(
            "entity.query",
            Map.of(
                "type", "person", "filter", Map.of("op", "equals", "field", "iban", "value", "x"))),
        "sensitive");
    assertFinds(
        check("entity.query", Map.of("type", "person", "sort", "iban")), "cannot sort by 'iban'");
    assertThat(
            check(
                "entity.query",
                Map.of(
                    "type",
                    "person",
                    "filter",
                    Map.of("op", "equals", "field", "age", "value", 3L),
                    "sort",
                    "age")))
        .isEmpty();
  }

  @Test
  void aRevealNamesSensitiveFields() {
    assertFinds(
        check("entity.reveal", Map.of("type", "person", "id", "i", "fields", List.of("name"))),
        "not a sensitive field");
    assertFinds(
        check("entity.reveal", Map.of("type", "person", "id", "i", "fields", List.of())),
        "at least one");
    assertThat(
            check("entity.reveal", Map.of("type", "person", "id", "i", "fields", List.of("iban"))))
        .isEmpty();
  }

  @Test
  void otherCapabilitiesAreNotLookedAt() {
    assertThat(check("mail.move", Map.of("type", "nope"))).isEmpty();
  }
}
