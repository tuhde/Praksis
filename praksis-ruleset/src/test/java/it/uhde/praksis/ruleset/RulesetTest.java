// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expressions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulesetTest {
  @TempDir Path dir;

  private static final ModuleCatalog CATALOG =
      new ModuleCatalog(
          List.of(
              new ModuleDescriptor(
                  "maildir",
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
                          List.of(new FieldDescriptor("path", ValueType.STRING, true, false))),
                      new CapabilityDescriptor(
                          "mail.delete",
                          EffectClass.DESTRUCTIVE,
                          true,
                          List.of(new FieldDescriptor("message", ValueType.STRING, true, false)),
                          List.of())),
                  List.of(
                      new EventTypeDescriptor(
                          "mail.received",
                          List.of(
                              new FieldDescriptor("message", ValueType.STRING, true, false),
                              new FieldDescriptor("subject", ValueType.STRING, true, false),
                              new FieldDescriptor("headers", ValueType.MAP, false, false)))))));

  private static final String HEADER =
      """
      format_version = 1
      name = "mail"

      [param.newsletter_folder]
      type = "string"
      default = "News"
      """;

  private static final String ARCHIVE =
      """
      id = "mail.archive-newsletters"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"
      filter = "event.data.headers['List-Id'] != ''"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = { cel = "params.newsletter_folder" } }
      """;

  private void write(String relative, String content) throws IOException {
    Path file = dir.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private List<Problem> validate() throws Exception {
    Ruleset ruleset = RulesetLoader.load(dir);
    return new RulesetValidator(Expressions.compiler(), CATALOG).validate(ruleset);
  }

  private void rule(String id, String body) throws IOException {
    write("ruleset.toml", HEADER);
    write("rules/" + id + ".toml", body);
  }

  private static String problems(List<Problem> problems) {
    return problems.toString();
  }

  @Test
  void loadsAndValidatesTheExampleRule() throws Exception {
    rule("mail.archive-newsletters", ARCHIVE);
    Ruleset ruleset = RulesetLoader.load(dir);
    assertThat(ruleset.name()).isEqualTo("mail");
    assertThat(ruleset.params()).hasSize(1);
    assertThat(ruleset.params().get(0).defaultValue()).contains("News");
    Rule rule = ruleset.rules().get(0);
    assertThat(rule.trigger()).isInstanceOf(Trigger.OnEvent.class);
    Action.Call call = (Action.Call) rule.actions().get(0);
    assertThat(call.args().get("folder")).isEqualTo(new Arg.Expr("params.newsletter_folder"));
    assertThat(validate()).isEmpty();
  }

  @Test
  void rulesFireInIdOrder() throws Exception {
    write("ruleset.toml", HEADER);
    write("rules/b.toml", ARCHIVE.replace("mail.archive-newsletters", "b"));
    write("rules/a.toml", ARCHIVE.replace("mail.archive-newsletters", "a"));
    assertThat(RulesetLoader.load(dir).rules()).extracting(Rule::id).containsExactly("a", "b");
  }

  @Test
  void loaderReportsEveryStructuralProblem() throws Exception {
    write("ruleset.toml", "name = \"mail\"\n");
    write(
        "rules/x.toml",
        """
        id = "y"
        effect = "sideways"
        typo = 1

        [trigger]
        kind = "schedule"

        [[action]]
        id = "a"
        capability = "mail.move"
        args = { folder = { cel = "1", other = 2 } }
        """);
    assertThatThrownBy(() -> RulesetLoader.load(dir))
        .isInstanceOfSatisfying(
            RulesetException.class,
            e -> {
              String all = problems(e.problems());
              assertThat(all)
                  .contains("missing 'format_version'")
                  .contains("must equal the file name 'x'")
                  .contains("unknown effect 'sideways'")
                  .contains("unknown key 'typo'")
                  .contains("trigger kind 'schedule' is not supported yet")
                  .contains("{ cel = \"...\" } and nothing else");
            });
  }

  @Test
  void validatorRejectsUnsupportedFormatVersion() throws Exception {
    write("ruleset.toml", HEADER.replace("format_version = 1", "format_version = 2"));
    assertThat(problems(validate())).contains("format_version 2 is not supported");
  }

  @Test
  void validatorChecksTriggerAndFilter() throws Exception {
    rule(
        "r",
        ARCHIVE
            .replace("mail.archive-newsletters", "r")
            .replace("\"mail.received\"", "\"mail.sent\""));
    assertThat(problems(validate())).contains("unknown event type 'mail.sent'");

    rule(
        "r",
        ARCHIVE
            .replace("mail.archive-newsletters", "r")
            .replace("event.data.headers['List-Id'] != ''", "event.data.subject"));
    assertThat(problems(validate())).contains("trigger filter");
  }

  @Test
  void validatorChecksCapabilitiesAndNeeds() throws Exception {
    String base = ARCHIVE.replace("mail.archive-newsletters", "r");
    rule("r", base.replace("capability = \"mail.move\"", "capability = \"mail.copy\""));
    assertThat(problems(validate())).contains("unknown capability 'mail.copy'");

    rule("r", base.replace("needs = [\"mail.move\"]", "needs = []"));
    assertThat(problems(validate())).contains("does not list it in 'needs'");

    rule("r", base.replace("needs = [\"mail.move\"]", "needs = [\"mail.move\", \"mail.nope\"]"));
    assertThat(problems(validate())).contains("unknown capability 'mail.nope'");
  }

  @Test
  void validatorChecksEffectClass() throws Exception {
    rule(
        "r",
        ARCHIVE
            .replace("mail.archive-newsletters", "r")
            .replace("\"local-write\"", "\"read-only\""));
    assertThat(problems(validate()))
        .contains("declares effect 'read-only' but uses a capability with effect 'local-write'");
  }

  @Test
  void validatorChecksArguments() throws Exception {
    String base = ARCHIVE.replace("mail.archive-newsletters", "r");
    rule("r", base.replace("folder = { cel = \"params.newsletter_folder\" }", "folder = 5"));
    assertThat(problems(validate())).contains("arg 'folder': must be a string");

    rule(
        "r",
        base.replace(
            "folder = { cel = \"params.newsletter_folder\" }", "folder = \"X\", colour = \"red\""));
    assertThat(problems(validate()))
        .contains("arg 'colour': capability 'mail.move' has no such parameter");

    rule("r", base.replace(", folder = { cel = \"params.newsletter_folder\" }", ""));
    assertThat(problems(validate())).contains("missing required argument 'folder'");

    rule(
        "r",
        base.replace("{ cel = \"params.newsletter_folder\" }", "{ cel = \"event.data.subjekt\" }"));
    assertThat(problems(validate())).contains("arg 'folder'");

    rule("r", base.replace("{ cel = \"params.newsletter_folder\" }", "{ cel = \"1 + 1\" }"));
    assertThat(problems(validate())).contains("arg 'folder'");
  }

  @Test
  void laterActionsSeeEarlierResults() throws Exception {
    String body =
        ARCHIVE.replace("mail.archive-newsletters", "r")
            + """

            [[action]]
            id = "again"
            capability = "mail.move"
            args = { message = { cel = "results.move.path" }, folder = "Archive" }
            """;
    rule("r", body);
    assertThat(validate()).isEmpty();

    rule("r", body.replace("results.move.path", "results.later.path"));
    assertThat(problems(validate())).contains("action 'again'");
  }

  @Test
  void validatorRejectsActionsAfterStop() throws Exception {
    rule(
        "r",
        ARCHIVE.replace("mail.archive-newsletters", "r")
            + "\n[[action]]\nstop = true\n\n[[action]]\nid = \"late\"\ncapability = \"mail.move\"\nargs = { message = \"m\", folder = \"f\" }\n");
    assertThat(problems(validate())).contains("action after 'stop' can never run");
  }

  @Test
  void aStepCanHaveAConditionThatIsCheckedAndASingleConditionalStopLeavesLaterStepsReachable()
      throws Exception {
    String base = ARCHIVE.replace("mail.archive-newsletters", "r");
    String later =
        "\n[[action]]\nid = \"late\"\ncapability = \"mail.move\"\nargs = { message = \"m\", folder = \"f\" }\n";
    // a stop with a condition does not make what follows unreachable
    rule(
        "r",
        base
            + "\n[[action]]\nstop = true\nwhen = { cel = \"event.data.subject == 'x'\" }\n"
            + later);
    assertThat(validate()).isEmpty();

    // a condition can read the results of the steps before it, and has to be true or false
    rule(
        "r",
        base + later.replace("args =", "when = { cel = \"results.move.path == 'p'\" }\nargs ="));
    assertThat(problems(validate())).doesNotContain("when");
    rule("r", base + later.replace("args =", "when = { cel = \"event.data.subject\" }\nargs ="));
    assertThat(problems(validate())).contains("action 'late'").contains("when");
    rule(
        "r",
        base + later.replace("args =", "when = { cel = \"results.nothing.path == 'p'\" }\nargs ="));
    assertThat(problems(validate())).contains("action 'late'").contains("when");

    // it is written as an expression, and only so
    rule("r", base + later.replace("args =", "when = true\nargs ="));
    assertThatThrownBy(this::validate).hasMessageContaining("'when' is an expression");
    rule("r", base + "\n[[action]]\nstop = true\nwhen = { cel = \"true\", x = 1 }\n");
    assertThatThrownBy(this::validate).hasMessageContaining("'when' is an expression");
  }

  @Test
  void parameterDefaultsAreTypeChecked() throws Exception {
    write("ruleset.toml", HEADER.replace("default = \"News\"", "default = 5"));
    assertThatThrownBy(() -> RulesetLoader.load(dir))
        .hasMessageContaining("'default' is not a string");
  }
}
