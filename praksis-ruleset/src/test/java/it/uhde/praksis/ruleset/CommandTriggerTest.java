// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expressions;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandTriggerTest {
  @TempDir Path dir;

  private static final ModuleCatalog CATALOG =
      new ModuleCatalog(
          List.of(
              new ModuleDescriptor(
                  "mail",
                  "0.1.0",
                  List.of(),
                  List.of(
                      new CapabilityDescriptor(
                          "mail.move",
                          EffectClass.LOCAL_WRITE,
                          true,
                          List.of(
                              new FieldDescriptor("message", ValueType.STRING, true, false),
                              new FieldDescriptor("folder", ValueType.STRING, true, true)),
                          List.of())),
                  List.of(),
                  List.of())));

  private static final String COMMAND =
      """
      id = "mail.file"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "command"
      title = "File a mail"
      description = "Moves one mail to a folder."

      [[trigger.param]]
      name = "message"
      type = "string"
      label = "The mail"

      [[trigger.param]]
      name = "folder"
      type = "string"
      default = "Archive"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = { cel = "event.data.folder" } }
      """;

  private Ruleset load(String rule) throws Exception {
    Files.createDirectories(dir.resolve("rules"));
    Files.writeString(dir.resolve("ruleset.toml"), "format_version = 1\nname = \"t\"\n");
    Files.writeString(dir.resolve("rules/mail.file.toml"), rule);
    return RulesetLoader.load(dir);
  }

  @Test
  void aCommandHasATitleTypedParametersAndItsOwnEventType() throws Exception {
    Rule rule = load(COMMAND).rules().get(0);

    Trigger.OnCommand command = (Trigger.OnCommand) rule.trigger();
    assertThat(command.title()).isEqualTo("File a mail");
    assertThat(command.description()).contains("Moves one mail to a folder.");
    assertThat(command.params())
        .extracting(Trigger.CommandParam::name)
        .containsExactly("message", "folder");
    assertThat(command.params().get(0).required()).isTrue();
    assertThat(command.params().get(0).label()).contains("The mail");
    assertThat(command.params().get(1).required()).isFalse();
    assertThat(command.params().get(1).defaultValue()).contains("Archive");
    assertThat(rule.eventType()).isEqualTo("command.mail.file");
  }

  @Test
  void theActionsAreTypeCheckedAgainstTheParameters() throws Exception {
    Ruleset ok = load(COMMAND);
    assertThat(new RulesetValidator(Expressions.compiler(), CATALOG).validate(ok)).isEmpty();

    Ruleset bad = load(COMMAND.replace("event.data.folder", "event.data.nope"));
    assertThat(new RulesetValidator(Expressions.compiler(), CATALOG).validate(bad))
        .anyMatch(p -> p.message().contains("nope"));
  }

  @Test
  void aCommandSurvivesTheJsonRoundTripAndKeepsItsHash() throws Exception {
    Rule rule = load(COMMAND).rules().get(0);
    Rule back = RuleView.fromJson(RuleView.toJson(rule));
    assertThat(RuleView.hash(back)).isEqualTo(RuleView.hash(rule));
    assertThat(back.trigger()).isEqualTo(rule.trigger());
  }

  @Test
  void mistakesInTheParametersAreReported() throws Exception {
    assertThatThrownBy(() -> load(COMMAND.replace("name = \"folder\"", "name = \"message\"")))
        .hasMessageContaining("declared twice");
    assertThatThrownBy(() -> load(COMMAND.replace("default = \"Archive\"", "default = 3")))
        .hasMessageContaining("'default' is not a string");
    assertThatThrownBy(
            () ->
                load(COMMAND.replace("default = \"Archive\"", "default = \"A\"\nrequired = true")))
        .hasMessageContaining("cannot be required");
    assertThatThrownBy(() -> load(COMMAND.replace("name = \"folder\"", "name = \"Folder\"")))
        .hasMessageContaining("must match");
    assertThatThrownBy(() -> load(COMMAND.replace("title = \"File a mail\"\n", "")))
        .hasMessageContaining("missing 'title'");
    assertThatThrownBy(
            () ->
                load(
                    COMMAND.replace("kind = \"command\"", "kind = \"command\"\nfilter = \"true\"")))
        .hasMessageContaining("unknown key 'filter'");
  }
}
