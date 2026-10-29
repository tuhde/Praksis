// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.ruleset.LayerStack.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A ruleset can offer more than every installation has the modules for. */
class OptionalModulesTest {
  @TempDir Path dir;

  private static final String ALWAYS =
      """
      id = "mail.always"
      effect = "local-write"
      needs = ["mail.move"]
      [trigger]
      kind = "event"
      type = "mail.received"
      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Archive" }
      """;

  private static final String SENDS =
      """
      id = "mail.sends"
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
      """;

  private static final String VIEW =
      """
      title = "Reader"
      [[component]]
      type = "column"

      [[component.children]]
      type = "text"
      value = "always"

      [[component.children]]
      type = "button"
      label = "Reply"
      command = "mail.sends"
      requires_modules = ["smtp"]

      [[component.children]]
      type = "column"
      requires_modules = ["smtp", "contacts"]
      [[component.children.children]]
      type = "text"
      value = "inside the part that is left out"
      """;

  private Path layer() throws IOException {
    Path layer = dir.resolve("l" + System.nanoTime());
    Files.createDirectories(layer.resolve("rules"));
    Files.createDirectories(layer.resolve("views"));
    Files.writeString(layer.resolve("ruleset.toml"), "format_version = 1\nname = \"l\"\n");
    Files.writeString(layer.resolve("rules/mail.always.toml"), ALWAYS);
    Files.writeString(layer.resolve("rules/mail.sends.toml"), SENDS);
    Files.writeString(layer.resolve("views/mail.reader.toml"), VIEW);
    Files.writeString(
        layer.resolve("views/mail.write.toml"),
        "requires_modules = [\"smtp\"]\ntitle = \"Write\"\n[[component]]\ntype = \"text\"\nvalue = \"x\"\n");
    return layer;
  }

  @Test
  void whatAsksForAModuleThatIsNotThereIsLeftOutAndSaidSo() throws Exception {
    Path l = layer();

    LayerStack.Effective without =
        LayerStack.resolve(List.of(new Source("l", l)), Map.of(), Set.of("maildir"));

    assertThat(without.ruleset().rules()).extracting(Rule::id).containsExactly("mail.always");
    assertThat(without.origins())
        .extracting(LayerStack.Origin::ruleId)
        .containsExactly("mail.always");
    assertThat(without.ruleset().views()).extracting(View::id).containsExactly("mail.reader");
    // the parts of the view that ask for more are gone, with what is inside them
    View.Component column = without.ruleset().views().get(0).components().get(0);
    assertThat(column.children()).extracting(View.Component::type).containsExactly("text");
    assertThat(without.viewOrigins())
        .extracting(LayerStack.ViewOrigin::viewId)
        .containsExactly("mail.reader");
    assertThat(without.skipped())
        .extracting(LayerStack.Skipped::describe)
        .containsExactly(
            "rule mail.sends needs the module smtp", "view mail.write needs the module smtp");
  }

  @Test
  void everythingIsThereWhenTheModulesAre() throws Exception {
    Path l = layer();

    LayerStack.Effective with =
        LayerStack.resolve(
            List.of(new Source("l", l)), Map.of(), Set.of("maildir", "smtp", "contacts"));

    assertThat(with.ruleset().rules())
        .extracting(Rule::id)
        .containsExactly("mail.always", "mail.sends");
    assertThat(with.ruleset().views())
        .extracting(View::id)
        .containsExactly("mail.reader", "mail.write");
    assertThat(with.ruleset().views().get(0).components().get(0).children()).hasSize(3);
    assertThat(with.skipped()).isEmpty();

    // only one of two modules asked for by a part is not enough
    LayerStack.Effective some =
        LayerStack.resolve(List.of(new Source("l", l)), Map.of(), Set.of("smtp"));
    assertThat(some.ruleset().views().get(0).components().get(0).children())
        .extracting(View.Component::type)
        .containsExactly("text", "button");
  }

  @Test
  void withoutKnowingTheModulesNothingIsLeftOut() throws Exception {
    Path l = layer();
    LayerStack.Effective all = LayerStack.resolve(List.of(new Source("l", l)), Map.of());
    assertThat(all.ruleset().rules()).hasSize(2);
    assertThat(all.ruleset().views()).hasSize(2);
    assertThat(all.skipped()).isEmpty();
  }

  @Test
  void theHashOfAViewDoesNotDependOnWhatIsThere() throws Exception {
    Path l = layer();
    String hash =
        LayerStack.resolve(List.of(new Source("l", l)), Map.of(), Set.of("maildir"))
            .viewOrigins()
            .get(0)
            .hash();
    String full =
        LayerStack.resolve(List.of(new Source("l", l)), Map.of(), Set.of("smtp", "contacts"))
            .viewOrigins()
            .get(0)
            .hash();
    assertThat(hash).isEqualTo(full); // a replacement names the view as written, not as shown
  }

  @Test
  void aModuleListMustBeAListOfNames() throws Exception {
    Path l = layer();
    Files.writeString(l.resolve("rules/mail.sends.toml"), SENDS.replace("[\"smtp\"]", "5"));
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("l", l)), Map.of(), Set.of()))
        .isInstanceOf(RulesetException.class);
  }

  private static final String LEARNS =
      """
      id = "mail.learns"
      effect = "local-write"
      needs = ["mail.move", "contacts.add"]
      [trigger]
      kind = "event"
      type = "mail.received"
      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Archive" }
      [[action]]
      id = "learn"
      capability = "contacts.add"
      requires_modules = ["contacts"]
      args = { addresses = { cel = "event.data.from" } }
      """;

  private static final String ONLY_LEARNS =
      """
      id = "mail.only-learns"
      effect = "local-write"
      needs = ["contacts.add"]
      [trigger]
      kind = "event"
      type = "mail.received"
      [[action]]
      id = "learn"
      capability = "contacts.add"
      requires_modules = ["contacts"]
      args = { addresses = { cel = "event.data.from" } }
      """;

  private Path stepLayer() throws IOException {
    Path layer = dir.resolve("s" + System.nanoTime());
    Files.createDirectories(layer.resolve("rules"));
    Files.createDirectories(layer.resolve("views"));
    Files.writeString(layer.resolve("ruleset.toml"), "format_version = 1\nname = \"s\"\n");
    Files.writeString(layer.resolve("rules/mail.learns.toml"), LEARNS);
    Files.writeString(layer.resolve("rules/mail.only-learns.toml"), ONLY_LEARNS);
    Files.writeString(
        layer.resolve("views/compose.toml"),
        """
        title = "Write"
        [[component]]
        type = "form"
        command = "mail.learns"
        lookup = { capability = "contacts.search", arg = "query", requires_modules = ["contacts"] }
        """);
    return layer;
  }

  @Test
  void aStepThatAsksForAModuleThatIsNotThereIsLeftOutAndTheRestOfTheRuleRuns() throws Exception {
    Path l = stepLayer();

    LayerStack.Effective without =
        LayerStack.resolve(List.of(new Source("s", l)), Map.of(), Set.of("maildir"));
    LayerStack.Effective with =
        LayerStack.resolve(List.of(new Source("s", l)), Map.of(), Set.of("maildir", "contacts"));

    Rule reduced = without.ruleset().rules().get(0);
    assertThat(reduced.id()).isEqualTo("mail.learns");
    assertThat(reduced.actions()).hasSize(1);
    assertThat(reduced.needs()).containsExactly("mail.move"); // no need for what is not used
    assertThat(without.ruleset().rules()).extracting(Rule::id).containsExactly("mail.learns");
    // a rule with nothing left is not a rule
    assertThat(without.skipped())
        .extracting(LayerStack.Skipped::describe)
        .containsExactly("rule mail.only-learns needs the module contacts");

    assertThat(with.ruleset().rules())
        .extracting(Rule::id)
        .containsExactly("mail.learns", "mail.only-learns");
    assertThat(with.ruleset().rules().get(0).actions()).hasSize(2);
    assertThat(with.ruleset().rules().get(0).needs()).containsExactly("mail.move", "contacts.add");

    // what a replacement names is the rule as written, whatever is there
    assertThat(without.origins().get(0).hash()).isEqualTo(with.origins().get(0).hash());

    // the suggestions of the form go, the form stays
    View.Component formWithout = without.ruleset().views().get(0).components().get(0);
    View.Component formWith = with.ruleset().views().get(0).components().get(0);
    assertThat(formWithout.values().keySet()).noneMatch(k -> k.startsWith("lookup."));
    assertThat(formWith.values()).containsKeys("lookup.capability", "lookup.arg");
  }
}
