// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.ruleset.Verifier.Severity;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VerifierTest {
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
                              new FieldDescriptor("from", ValueType.STRING, true, false)))),
                  List.of())));

  private static String rule(
      String id, String effect, String needs, String filter, String actions) {
    return """
        id = "%s"
        effect = "%s"
        needs = [%s]

        [trigger]
        kind = "event"
        type = "mail.received"
        %s
        %s
        """
        .formatted(
            id, effect, needs, filter.isEmpty() ? "" : "filter = \"" + filter + "\"", actions);
  }

  private static final String MOVE =
      """

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Archive" }
      """;

  private static final String STOP =
      """

      [[action]]
      stop = true
      """;

  private Verifier.Report verify(String... idsAndContents) throws Exception {
    Files.createDirectories(dir.resolve("rules"));
    Files.writeString(dir.resolve("ruleset.toml"), "format_version = 1\nname = \"t\"\n");
    for (int i = 0; i < idsAndContents.length; i += 2) {
      Files.writeString(dir.resolve("rules/" + idsAndContents[i] + ".toml"), idsAndContents[i + 1]);
    }
    return new Verifier(Expressions.compiler(), CATALOG).verify(RulesetLoader.load(dir));
  }

  @Test
  void aCleanRulesetHasNoFindings() throws Exception {
    Verifier.Report report =
        verify(
            "a",
            rule("a", "local-write", "\"mail.move\"", "event.data.from == 'x'", MOVE + STOP),
            "b",
            rule("b", "local-write", "\"mail.move\"", "", MOVE));
    assertThat(report.findings()).isEmpty();
    assertThat(report.ok()).isTrue();
  }

  @Test
  void whatTheValidatorFindsIsAnError() throws Exception {
    Verifier.Report report =
        verify("a", rule("a", "local-write", "\"mail.move\"", "event.data.nope == 'x'", MOVE));
    assertThat(report.ok()).isFalse();
    assertThat(report.findings())
        .anySatisfy(
            f -> {
              assertThat(f.severity()).isEqualTo(Severity.ERROR);
              assertThat(f.check()).isEqualTo("validate");
              assertThat(f.where()).contains("rules/a.toml");
            });
  }

  @Test
  void aRuleMayOnlyAskForWhatItCalls() throws Exception {
    Verifier.Report report =
        verify("a", rule("a", "destructive", "\"mail.move\", \"mail.delete\"", "", MOVE));
    assertThat(report.findings())
        .filteredOn(f -> f.check().equals("unused-need"))
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.severity()).isEqualTo(Severity.ERROR);
              assertThat(f.where()).isEqualTo("rules/a.toml");
              assertThat(f.message()).contains("'mail.delete'").contains("no action calls it");
            });
    assertThat(report.ok()).isFalse();
  }

  @Test
  void anOverstatedEffectIsAWarning() throws Exception {
    Verifier.Report report = verify("a", rule("a", "destructive", "\"mail.move\"", "", MOVE));
    assertThat(report.findings())
        .singleElement()
        .satisfies(
            f -> {
              assertThat(f.severity()).isEqualTo(Severity.WARNING);
              assertThat(f.check()).isEqualTo("effect-overstated");
              assertThat(f.message()).contains("'destructive'").contains("at most 'local-write'");
            });
    assertThat(report.ok()).isTrue();
  }

  @Test
  void aRuleThatHandlesEverythingAndStopsShadowsTheRulesAfterIt() throws Exception {
    Verifier.Report report =
        verify(
            "a", rule("a", "local-write", "\"mail.move\"", "", MOVE + STOP),
            "b", rule("b", "local-write", "\"mail.move\"", "event.data.from == 'x'", MOVE),
            "c", rule("c", "local-write", "\"mail.move\"", "", MOVE));
    assertThat(report.findings())
        .extracting(f -> f.check() + " " + f.where())
        .containsExactly("shadowed rules/b.toml", "shadowed rules/c.toml");
    assertThat(report.findings().get(0).message()).contains("'a'").contains("mail.received");
    assertThat(report.ok()).isTrue();
  }

  @Test
  void aFilterOrNoStopMeansNoShadow() throws Exception {
    assertThat(
            verify(
                    "a",
                    rule(
                        "a", "local-write", "\"mail.move\"", "event.data.from == 'x'", MOVE + STOP),
                    "b",
                    rule("b", "local-write", "\"mail.move\"", "", MOVE))
                .findings())
        .isEmpty();
  }

  @Test
  void anEmptyRulesetIsAWarning() throws Exception {
    Verifier.Report report = verify();
    assertThat(report.findings())
        .singleElement()
        .satisfies(f -> assertThat(f.check()).isEqualTo("empty"));
    assertThat(report.ok()).isTrue();
  }

  @Test
  void errorsComeFirstInTheReport() {
    Verifier.Report report =
        new Verifier.Report(
            List.of(
                new Verifier.Finding(Severity.WARNING, "w", "x", "m"),
                new Verifier.Finding(Severity.ERROR, "e", "x", "m")));
    assertThat(report.findings()).extracting(Verifier.Finding::check).containsExactly("e", "w");
    assertThat(report.errors()).isEqualTo(1);
    assertThat(report.warnings()).isEqualTo(1);
  }
}
