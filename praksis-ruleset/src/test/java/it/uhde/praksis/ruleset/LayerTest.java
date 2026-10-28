// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.ruleset.LayerStack.Effective;
import it.uhde.praksis.ruleset.LayerStack.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LayerTest {
  @TempDir Path dir;

  private Path base;
  private Path mine;

  private static final String BASE_HEADER =
      """
      format_version = 1
      name = "mail"

      [param.newsletter_folder]
      type = "string"
      default = "News"
      """;

  private static final String ARCHIVE =
      """
      id = "mail.archive"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"
      filter = "event.data.subject.startsWith('[list]')"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { path = { cel = "event.data.path" }, folder = { cel = "params.newsletter_folder" } }
      """;

  private static final String SPAM =
      """
      id = "mail.spam"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { path = { cel = "event.data.path" }, folder = "Spam" }

      [[action]]
      stop = true
      """;

  private static final String VIP =
      """
      id = "my.vip"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { path = { cel = "event.data.path" }, folder = "VIP" }
      """;

  @BeforeEach
  void setUp() throws IOException {
    base = dir.resolve("base");
    mine = dir.resolve("mine");
    write(base, "ruleset.toml", BASE_HEADER);
    write(base, "rules/mail.archive.toml", ARCHIVE);
    write(base, "rules/mail.spam.toml", SPAM);
    write(mine, "ruleset.toml", "format_version = 1\nname = \"mine\"\n");
  }

  private static void write(Path root, String relative, String content) throws IOException {
    Path file = root.resolve(relative);
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private String hashOf(String ruleId) throws Exception {
    return RuleView.hash(
        RulesetLoader.load(base).rules().stream()
            .filter(r -> r.id().equals(ruleId))
            .findFirst()
            .orElseThrow());
  }

  private Effective resolve() throws Exception {
    return LayerStack.resolve(
        List.of(new Source("mail", base), new Source("mine", mine)), Map.of());
  }

  private String problemsOf() throws Exception {
    try {
      resolve();
    } catch (RulesetException e) {
      return e.problems().toString();
    }
    throw new AssertionError("expected problems");
  }

  @Test
  void aSingleLayerIsAPlainRuleset() throws Exception {
    Effective e = LayerStack.resolve(List.of(new Source("mail", base)), Map.of());
    assertThat(e.ruleset().name()).isEqualTo("mail");
    assertThat(e.ruleset().rules())
        .extracting(Rule::id)
        .containsExactly("mail.archive", "mail.spam");
    assertThat(e.origins()).extracting(LayerStack.Origin::layer).containsOnly("mail");
    assertThat(e.disabled()).isEmpty();
  }

  @Test
  void aLayerAddsRulesAndTheRulesFireInIdOrder() throws Exception {
    write(mine, "rules/my.vip.toml", VIP);
    Effective e = resolve();
    assertThat(e.ruleset().name()).isEqualTo("mail+mine");
    assertThat(e.ruleset().rules())
        .extracting(Rule::id)
        .containsExactly("mail.archive", "mail.spam", "my.vip");
    assertThat(e.origins().get(2).layer()).isEqualTo("mine");
    assertThat(e.origins().get(2).history()).containsExactly("mine: add");
  }

  @Test
  void addingAnExistingIdIsAnErrorThatPointsToReplaceAndPatch() throws Exception {
    write(mine, "rules/mail.spam.toml", SPAM);
    assertThat(problemsOf())
        .contains("layer 'mine': rules/mail.spam.toml")
        .contains("already exists (added by 'mail')")
        .contains("use replace/ or patch/");
  }

  @Test
  void replaceNeedsTheRuleToBeUnchanged() throws Exception {
    String replacement = SPAM.replace("\"Spam\"", "\"Junk\"");
    write(
        mine,
        "replace/mail.spam.toml",
        "based_on = \"" + hashOf("mail.spam") + "\"\n" + replacement);
    Effective e = resolve();
    assertThat(e.ruleset().rules().get(1).actions().get(0))
        .isInstanceOfSatisfying(
            Action.Call.class,
            c -> assertThat(c.args().get("folder")).isEqualTo(new Arg.Literal("Junk")));
    assertThat(e.origins().get(1).history()).containsExactly("mail: add", "mine: replace");

    // the upstream rule changes: the replacement is refused until someone has looked
    write(base, "rules/mail.spam.toml", SPAM.replace("\"Spam\"", "\"Spam2\""));
    assertThat(problemsOf())
        .contains("replace/mail.spam.toml")
        .contains("has changed since this replace was written")
        .contains(hashOf("mail.spam"));
  }

  @Test
  void replaceOfAMissingRuleIsAnError() throws Exception {
    write(
        mine,
        "replace/mail.nothing.toml",
        "based_on = \"sha256:"
            + "0".repeat(64)
            + "\"\n"
            + SPAM.replace("mail.spam", "mail.nothing"));
    assertThat(problemsOf())
        .contains("cannot replace rule 'mail.nothing'")
        .contains("does not exist");
  }

  @Test
  void aPatchChangesOneFieldAndChecksTheOldValue() throws Exception {
    write(
        mine,
        "patch/mail.archive.toml",
        """
        [[change]]
        path = "action.move.args.folder"
        expect = { cel = "params.newsletter_folder" }
        value = "Lists"

        [[change]]
        path = "trigger.filter"
        expect = "event.data.subject.startsWith('[list]')"
        value = "event.data.subject.startsWith('[news]')"

        [[change]]
        path = "description"
        value = "Newsletters"
        """);
    Effective e = resolve();
    Rule archive = e.ruleset().rules().get(0);
    assertThat(archive.description()).contains("Newsletters");
    assertThat(archive.trigger())
        .isEqualTo(
            new Trigger.OnEvent(
                "mail.received", java.util.Optional.of("event.data.subject.startsWith('[news]')")));
    assertThat(((Action.Call) archive.actions().get(0)).args().get("folder"))
        .isEqualTo(new Arg.Literal("Lists"));
    assertThat(e.origins().get(0).history()).containsExactly("mail: add", "mine: patch");
    assertThat(e.origins().get(0).hash())
        .isEqualTo(RuleView.hash(archive))
        .isNotEqualTo(hashOf("mail.archive"));
  }

  @Test
  void aPatchIsRefusedWhenTheFieldChangedUpstreamButNotWhenOtherFieldsDid() throws Exception {
    write(
        mine,
        "patch/mail.archive.toml",
        """
        [[change]]
        path = "trigger.filter"
        expect = "event.data.subject.startsWith('[list]')"
        value = "true"
        """);
    // another field changes upstream: the patch still applies
    write(base, "rules/mail.archive.toml", ARCHIVE.replace("local-write", "destructive"));
    assertThat(resolve().ruleset().rules().get(0).effect().id()).isEqualTo("destructive");

    // the patched field changes upstream: refused
    write(base, "rules/mail.archive.toml", ARCHIVE.replace("[list]", "[lists]"));
    assertThat(problemsOf())
        .contains("patch/mail.archive.toml")
        .contains("change of 'trigger.filter' has changed since this patch was written")
        .contains("it is \"event.data.subject.startsWith('[lists]')\"");
  }

  @Test
  void aPatchWithoutExpectNeedsTheFieldToBeAbsent() throws Exception {
    write(mine, "patch/mail.spam.toml", "[[change]]\npath = \"description\"\nvalue = \"Spam\"\n");
    assertThat(resolve().ruleset().rules().get(1).description()).contains("Spam");

    write(
        mine,
        "patch/mail.archive.toml",
        "[[change]]\npath = \"trigger.filter\"\nvalue = \"true\"\n");
    assertThat(problemsOf())
        .contains("is already set to \"event.data")
        .contains("write the old value as 'expect'");
  }

  @Test
  void aPatchCanRemoveFields() throws Exception {
    write(
        mine,
        "patch/mail.archive.toml",
        """
        [[change]]
        path = "trigger.filter"
        expect = "event.data.subject.startsWith('[list]')"
        remove = true
        """);
    assertThat(((Trigger.OnEvent) resolve().ruleset().rules().get(0).trigger()).filter()).isEmpty();
  }

  @Test
  void aPatchThatBreaksTheRuleOrNamesNothingIsAnError() throws Exception {
    write(
        mine,
        "patch/mail.spam.toml",
        "[[change]]\npath = \"effect\"\nexpect = \"local-write\"\nvalue = \"bogus\"\n");
    assertThat(problemsOf()).contains("unknown effect 'bogus'");

    write(
        mine,
        "patch/mail.spam.toml",
        "[[change]]\npath = \"action.nope.capability\"\nvalue = \"x\"\n");
    assertThat(problemsOf()).contains("the rule has no action 'nope'");

    write(mine, "patch/mail.spam.toml", "[[change]]\npath = \"colour\"\nvalue = \"x\"\n");
    assertThat(problemsOf()).contains("unknown key 'colour'");

    write(
        mine,
        "patch/mail.spam.toml",
        "[[change]]\npath = \"id\"\nexpect = \"mail.spam\"\nvalue = \"x\"\n");
    assertThat(problemsOf()).contains("cannot be changed");

    write(
        mine,
        "patch/mail.spam.toml",
        "[[change]]\npath = \"description\"\nvalue = \"x\"\nremove = true\n");
    assertThat(problemsOf()).contains("give exactly one of 'value' and 'remove = true'");

    write(mine, "patch/mail.spam.toml", "based_on = \"x\"\n");
    assertThat(problemsOf())
        .contains("'based_on' must look like sha256")
        .contains("at least one [[change]]");
  }

  @Test
  void disableRemovesTheRuleButKeepsItInTheViewAndBlocksLaterLayers() throws Exception {
    write(
        mine,
        "disable/mail.spam.toml",
        "based_on = \"" + hashOf("mail.spam") + "\"\nreason = \"my spam filter does this\"\n");
    Effective e = resolve();
    assertThat(e.ruleset().rules()).extracting(Rule::id).containsExactly("mail.archive");
    assertThat(e.disabled())
        .containsExactly(
            new LayerStack.Disabled(
                "mail.spam", "mine", java.util.Optional.of("my spam filter does this")));

    Path third = dir.resolve("third");
    write(third, "ruleset.toml", "format_version = 1\nname = \"third\"\n");
    write(third, "patch/mail.spam.toml", "[[change]]\npath = \"description\"\nvalue = \"x\"\n");
    assertThatThrownBy(
            () ->
                LayerStack.resolve(
                    List.of(
                        new Source("mail", base),
                        new Source("mine", mine),
                        new Source("third", third)),
                    Map.of()))
        .hasMessageContaining("was disabled by 'mine'");
  }

  @Test
  void aLayerMayDoOneThingToARule() throws Exception {
    write(mine, "disable/mail.spam.toml", "based_on = \"" + hashOf("mail.spam") + "\"\n");
    write(mine, "patch/mail.spam.toml", "[[change]]\npath = \"description\"\nvalue = \"x\"\n");
    assertThat(problemsOf()).contains("more than one operation on rule 'mail.spam'");
  }

  @Test
  void allProblemsOfAllLayersAreReportedTogether() throws Exception {
    write(mine, "rules/mail.spam.toml", SPAM);
    write(mine, "disable/mail.nothing.toml", "based_on = \"sha256:" + "0".repeat(64) + "\"\n");
    write(mine, "patches/x.toml", "x = 1\n");
    String problems = problemsOf();
    assertThat(problems).contains("unknown directory");
    write(mine, "patches/x.toml", "x = 1\n");
    Files.delete(mine.resolve("patches/x.toml"));
    Files.delete(mine.resolve("patches"));
    problems = problemsOf();
    assertThat(problems).contains("already exists").contains("cannot disable rule 'mail.nothing'");
  }

  @Test
  void parametersMergeAcrossLayersAndTheLocalConfigurationWins() throws Exception {
    write(
        mine,
        "ruleset.toml",
        """
        format_version = 1
        name = "mine"

        [param.newsletter_folder]
        type = "string"
        default = "Lists"

        [param.extra]
        type = "int"
        default = 3
        """);
    Effective e = resolve();
    assertThat(e.ruleset().params())
        .containsExactlyInAnyOrder(
            new ParamDecl(
                "newsletter_folder",
                it.uhde.praksis.capability.ValueType.STRING,
                java.util.Optional.of("Lists")),
            new ParamDecl(
                "extra", it.uhde.praksis.capability.ValueType.INT, java.util.Optional.of(3L)));

    Effective local =
        LayerStack.resolve(
            List.of(new Source("mail", base), new Source("mine", mine)),
            Map.of("newsletter_folder", "Local"));
    assertThat(local.ruleset().params())
        .contains(
            new ParamDecl(
                "newsletter_folder",
                it.uhde.praksis.capability.ValueType.STRING,
                java.util.Optional.of("Local")));

    assertThatThrownBy(
            () ->
                LayerStack.resolve(
                    List.of(new Source("mail", base)), Map.of("nope", "x", "newsletter_folder", 5)))
        .hasMessageContaining("param.nope")
        .hasMessageContaining("is not a string");

    write(
        mine,
        "ruleset.toml",
        "format_version = 1\nname = \"mine\"\n[param.newsletter_folder]\ntype = \"int\"\n");
    assertThat(problemsOf()).contains("type int differs from string in the layers below");
  }

  @Test
  void theHashIgnoresFormattingAndKeyOrder() throws Exception {
    String reordered =
        """
        # a comment
        needs  = [ "mail.move" ]
        effect = "local-write"
        id     = "mail.archive"

        [trigger]
        filter = "event.data.subject.startsWith('[list]')"
        type   = "mail.received"
        kind   = "event"

        [[action]]
        capability = "mail.move"
        args = { folder = { cel = "params.newsletter_folder" }, path = { cel = "event.data.path" } }
        id = "move"
        """;
    String before = hashOf("mail.archive");
    write(base, "rules/mail.archive.toml", reordered);
    assertThat(hashOf("mail.archive")).isEqualTo(before).matches("sha256:[0-9a-f]{64}");
    write(base, "rules/mail.archive.toml", reordered.replace("[list]", "[lists]"));
    assertThat(hashOf("mail.archive")).isNotEqualTo(before);
  }
}
