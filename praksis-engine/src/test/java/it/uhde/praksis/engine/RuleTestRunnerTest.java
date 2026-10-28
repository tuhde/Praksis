// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.ruleset.LayerStack.Source;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleTestRunnerTest {
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
                  List.of(
                      new EventTypeDescriptor(
                          "mail.received",
                          List.of(
                              new FieldDescriptor("message", ValueType.STRING, true, false),
                              new FieldDescriptor("subject", ValueType.STRING, true, false)))),
                  List.of())));

  private static final String LISTS =
      """
      id = "mail.lists"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"
      filter = "event.data.subject.startsWith('[list]')"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Lists" }
      """;

  private static final String TESTS =
      """
      format_version = 1

      [[test]]
      name = "a list mail goes to Lists"
      [test.event]
      type = "mail.received"
      data = { subject = "[list] hi", message = "m1" }
      [[test.call]]
      capability = "mail.move"
      args = { folder = "Lists", message = "m1" }

      [[test]]
      name = "other mail is left alone"
      [test.event]
      type = "mail.received"
      data = { subject = "hello", message = "m2" }
      """;

  private void write(String relative, String content) throws IOException {
    Path f = dir.resolve(relative);
    Files.createDirectories(f.getParent());
    Files.writeString(f, content);
  }

  private RuleTestRunner.Report run(String... layers) {
    return new RuleTestRunner(CATALOG, Expressions.compiler())
        .run(
            java.util.Arrays.stream(layers).map(l -> new Source(l, dir.resolve(l))).toList(),
            Map.of());
  }

  @Test
  void aRuleThatBehavesAsTheTestsSayPasses() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write("base/rules/mail.lists.toml", LISTS);
    write("base/tests/mail.lists.toml", TESTS);

    RuleTestRunner.Report report = run("base");

    assertThat(report.ok()).as(report.toString()).isTrue();
    assertThat(report.results()).hasSize(2);
    assertThat(report.results().get(0).fired()).containsExactly("mail.lists");
    assertThat(report.results().get(1).fired()).isEmpty();
    assertThat(report.covered(Map.of("mail.lists", "base"))).containsExactly("mail.lists");
  }

  @Test
  void aDifferentCallAnExtraCallAndAMissingCallAreFound() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write("base/rules/mail.lists.toml", LISTS.replace("\"Lists\"", "\"Spam\""));
    write("base/tests/mail.lists.toml", TESTS.replace("hello", "[list] hello"));

    RuleTestRunner.Report report = run("base");

    assertThat(report.ok()).isFalse();
    assertThat(report.results().get(0).message())
        .contains("call 1 should be mail.move")
        .contains("but is mail.move");
    assertThat(report.results().get(1).message()).contains("unexpected call 1");
  }

  @Test
  void aRuleThatFailsFailsTheTest() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write("base/rules/mail.lists.toml", LISTS);
    write("base/tests/mail.lists.toml", TESTS.replace("subject = \"[list] hi\", ", ""));

    RuleTestRunner.Report report = run("base");

    assertThat(report.results().get(0).passed()).isFalse();
    assertThat(report.results().get(0).message()).isNotEmpty();
  }

  @Test
  void aTestOfAnUnknownRuleOrAnUnreadableFileIsReported() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write("base/rules/mail.lists.toml", LISTS);
    write("base/tests/mail.nope.toml", TESTS);
    assertThat(run("base").results())
        .hasSize(2)
        .allSatisfy(
            r -> {
              assertThat(r.message()).contains("there is no rule 'mail.nope'");
              assertThat(r.passed()).isFalse();
            });
  }

  @Test
  void aTestFileWithMistakesIsAProblemNotAFailure() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write("base/rules/mail.lists.toml", LISTS);
    write("base/tests/mail.lists.toml", "format_version = 1\n[[test]]\nname = \"x\"\n");

    RuleTestRunner.Report report = run("base");

    assertThat(report.ok()).isFalse();
    assertThat(report.problems()).anyMatch(p -> p.message().contains("missing [event]"));
  }

  @Test
  void testsOfALayerRunAgainstTheStackUpToThatLayer() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write("base/rules/mail.lists.toml", LISTS);
    write("base/tests/mail.lists.toml", TESTS);
    // the top layer replaces where lists mail goes; its own test sees that, the base test does not
    String replaced = LISTS.replace("\"Lists\"", "\"Archive\"");
    write("top/ruleset.toml", "format_version = 1\nname = \"top\"\n");
    write("top/replace/mail.lists.toml", "based_on = \"" + hashOf(LISTS) + "\"\n" + replaced);
    write("top/tests/mail.lists.toml", TESTS.replace("folder = \"Lists\"", "folder = \"Archive\""));

    RuleTestRunner.Report report = run("base", "top");

    assertThat(report.ok()).as(report.toString()).isTrue();
    assertThat(report.results())
        .extracting(RuleTestRunner.Result::layer)
        .containsExactly("base", "base", "top", "top");
    assertThat(report.covered(Map.of("mail.lists", "top"))).containsExactly("mail.lists");
  }

  private String hashOf(String rule) throws Exception {
    write("hash/ruleset.toml", "format_version = 1\nname = \"h\"\n");
    write("hash/rules/mail.lists.toml", rule);
    var rules = it.uhde.praksis.ruleset.RulesetLoader.load(dir.resolve("hash")).rules();
    return it.uhde.praksis.ruleset.RuleView.hash(rules.get(0));
  }
}
