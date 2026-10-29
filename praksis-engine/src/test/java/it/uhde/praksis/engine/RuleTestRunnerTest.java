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
                          List.of()),
                      new CapabilityDescriptor(
                          "mail.compose",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(new FieldDescriptor("subject", ValueType.STRING, true, false)),
                          List.of(new FieldDescriptor("raw", ValueType.BYTES, true, false))),
                      new CapabilityDescriptor(
                          "mail.append",
                          EffectClass.LOCAL_WRITE,
                          true,
                          List.of(
                              new FieldDescriptor("folder", ValueType.STRING, true, true),
                              new FieldDescriptor("content", ValueType.BYTES, true, false)),
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

  @Test
  void aTestCanSetParametersAndOtherwiseRunsWithTheDefaults() throws Exception {
    write(
        "base/ruleset.toml",
        "format_version = 1\nname = \"base\"\n[param.box]\ntype = \"string\"\ndefault ="
            + " \"Lists\"\n");
    write(
        "base/rules/mail.lists.toml",
        LISTS.replace("folder = \"Lists\"", "folder = { cel = \"params.box\" }"));
    write(
        "base/tests/mail.lists.toml",
        TESTS
            + """

            [[test]]
            name = "another box"
            [test.params]
            box = "Elsewhere"
            [test.event]
            type = "mail.received"
            data = { subject = "[list] hi", message = "m3" }
            [[test.call]]
            capability = "mail.move"
            args = { folder = "Elsewhere" }

            [[test]]
            name = "a parameter nobody declared"
            [test.params]
            nope = "x"
            [test.event]
            type = "mail.received"
            data = { subject = "x", message = "m4" }
            """);

    RuleTestRunner.Report report = run("base");

    assertThat(report.results()).hasSize(4);
    assertThat(report.results().subList(0, 3)).allMatch(RuleTestRunner.Result::passed);
    assertThat(report.results().get(3).passed()).isFalse();
    assertThat(report.results().get(3).message()).contains("cannot set the parameters");
  }

  private String hashOf(String rule) throws Exception {
    write("hash/ruleset.toml", "format_version = 1\nname = \"h\"\n");
    write("hash/rules/mail.lists.toml", rule);
    var rules = it.uhde.praksis.ruleset.RulesetLoader.load(dir.resolve("hash")).rules();
    return it.uhde.praksis.ruleset.RuleView.hash(rules.get(0));
  }

  @Test
  void aCommandIsTestedWithAnEventOfItsOwnType() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write(
        "base/rules/mail.file.toml",
        """
        id = "mail.file"
        effect = "local-write"
        needs = ["mail.move"]

        [trigger]
        kind = "command"
        title = "File a mail"
        [[trigger.param]]
        name = "message"
        type = "string"

        [[action]]
        id = "move"
        capability = "mail.move"
        args = { message = { cel = "event.data.message" }, folder = "Archive" }
        """);
    write(
        "base/tests/mail.file.toml",
        """
        format_version = 1
        [[test]]
        name = "files the mail"
        [test.event]
        type = "command.mail.file"
        data = { message = "m1" }
        [[test.call]]
        capability = "mail.move"
        args = { message = "m1", folder = "Archive" }
        """);

    RuleTestRunner.Report report = run("base");

    assertThat(report.ok()).as(report.toString()).isTrue();
    assertThat(report.covered(Map.of("mail.file", "base"))).containsExactly("mail.file");
  }

  @Test
  void aCommandTestGetsTheDefaultsOfTheParametersItDoesNotGive() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write(
        "base/rules/mail.star.toml",
        """
        id = "mail.star"
        effect = "local-write"
        needs = ["mail.move"]

        [trigger]
        kind = "command"
        title = "Star"
        [[trigger.param]]
        name = "message"
        type = "string"
        [[trigger.param]]
        name = "folder"
        type = "string"
        default = "Starred"

        [[action]]
        id = "move"
        capability = "mail.move"
        args = { message = { cel = "event.data.message" }, folder = { cel = "event.data.folder" } }
        """);
    write(
        "base/tests/mail.star.toml",
        """
        format_version = 1
        [[test]]
        name = "uses the default"
        [test.event]
        type = "command.mail.star"
        data = { message = "m1" }
        [[test.call]]
        capability = "mail.move"
        args = { message = "m1", folder = "Starred" }

        [[test]]
        name = "a given value wins"
        [test.event]
        type = "command.mail.star"
        data = { message = "m1", folder = "Other" }
        [[test.call]]
        capability = "mail.move"
        args = { message = "m1", folder = "Other" }
        """);

    RuleTestRunner.Report report = run("base");

    assertThat(report.ok()).as(report.toString()).isTrue();
  }

  @Test
  void bytesAreMockedAndExpectedAsBase64() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write(
        "base/rules/mail.draft.toml",
        """
        id = "mail.draft"
        effect = "local-write"
        needs = ["mail.compose", "mail.append"]

        [trigger]
        kind = "command"
        title = "Draft"
        [[trigger.param]]
        name = "subject"
        type = "string"

        [[action]]
        id = "compose"
        capability = "mail.compose"
        args = { subject = { cel = "event.data.subject" } }
        [[action]]
        id = "save"
        capability = "mail.append"
        args = { folder = "Drafts", content = { cel = "results.compose.raw" } }
        """);
    write(
        "base/tests/mail.draft.toml",
        """
        format_version = 1
        [[test]]
        name = "the composed bytes are saved"
        [test.mock."mail.compose"]
        raw = "ZHJhZnQ="
        [test.event]
        type = "command.mail.draft"
        data = { subject = "s" }
        [[test.call]]
        capability = "mail.compose"
        args = { subject = "s" }
        [[test.call]]
        capability = "mail.append"
        args = { folder = "Drafts", content = "ZHJhZnQ=" }

        [[test]]
        name = "other bytes are a failure, and it says what they were"
        [test.mock."mail.compose"]
        raw = "ZHJhZnQ="
        [test.event]
        type = "command.mail.draft"
        data = { subject = "s" }
        [[test.call]]
        capability = "mail.compose"
        args = { subject = "s" }
        [[test.call]]
        capability = "mail.append"
        args = { folder = "Drafts", content = "b3RoZXI=" }
        """);

    RuleTestRunner.Report report = run("base");

    assertThat(report.results()).hasSize(2);
    assertThat(report.results().get(0).passed()).as(report.toString()).isTrue();
    assertThat(report.results().get(1).passed()).isFalse();
    assertThat(report.results().get(1).message())
        .contains("content=ZHJhZnQ=")
        .doesNotContain("[B@");
  }

  @Test
  void aTestOfARuleThatNeedsAModuleThatIsNotThereIsSkippedNotFailed() throws Exception {
    write("base/ruleset.toml", "format_version = 1\nname = \"base\"\n");
    write(
        "base/rules/mail.send.toml",
        """
        id = "mail.send"
        effect = "external"
        needs = ["mail.send"]
        requires_modules = ["smtp"]

        [trigger]
        kind = "command"
        title = "Send"

        [[action]]
        id = "send"
        capability = "mail.send"
        args = { to = "a@example.org" }
        """);
    write(
        "base/tests/mail.send.toml",
        """
        format_version = 1
        [[test]]
        name = "sends"
        [test.event]
        type = "command.mail.send"
        data = {}
        [[test.call]]
        capability = "mail.send"
        args = { to = "a@example.org" }
        """);

    RuleTestRunner.Report report = run("base");

    assertThat(report.ok()).as(report.toString()).isTrue();
    assertThat(report.results())
        .singleElement()
        .satisfies(r -> assertThat(r.message()).startsWith("skipped"));
  }
}
