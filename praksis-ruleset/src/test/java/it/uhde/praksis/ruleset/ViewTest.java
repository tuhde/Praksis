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
import it.uhde.praksis.ruleset.LayerStack.Source;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewTest {
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
                          "mail.list",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(new FieldDescriptor("folder", ValueType.STRING, true, true)),
                          List.of(new FieldDescriptor("items", ValueType.LIST, true, false))),
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
      [[trigger.param]]
      name = "message"
      type = "string"
      [[trigger.param]]
      name = "folder"
      type = "string"
      default = "Archive"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = { cel = "event.data.folder" } }
      """;

  static final String INBOX =
      """
      title = "Inbox"
      description = "The mail in a folder."

      [param.folder]
      type = "string"
      default = "INBOX"

      [param.message]
      type = "string"
      default = ""

      [source.messages]
      capability = "mail.list"
      args = { folder = { cel = "view.folder" } }

      [[component]]
      type = "row"

      [[component.children]]
      type = "table"
      rows = { cel = "source.messages.items" }
      empty = "No mail"
      columns = [
        { title = "From", value = { cel = "item.from" } },
        { title = "Date", value = { cel = "item.date" }, format = "datetime" },
      ]
      select = { param = "message", value = { cel = "item.id" } }

      [[component.children.row_action]]
      label = "Archive"
      command = "mail.file"
      args = { message = { cel = "item.id" } }
      confirm = "Move it?"

      [[component.children]]
      type = "column"

      [[component.children.children]]
      type = "text"
      value = { cel = "'Selected: ' + view.message" }
      style = "title"

      [[component.children.children]]
      type = "button"
      label = "Archive this"
      command = "mail.file"
      args = { message = { cel = "view.message" } }
      enabled = { cel = "view.message != ''" }
      """;

  private Path layer(String name, Map<String, String> views, String... rules) throws IOException {
    Path layer = dir.resolve(name);
    Files.createDirectories(layer.resolve("rules"));
    Files.createDirectories(layer.resolve("views"));
    Files.writeString(
        layer.resolve("ruleset.toml"), "format_version = 1\nname = \"" + name + "\"\n");
    for (String rule : rules) {
      Files.writeString(layer.resolve("rules/mail.file.toml"), rule);
    }
    for (var v : views.entrySet()) {
      Files.writeString(layer.resolve("views/" + v.getKey() + ".toml"), v.getValue());
    }
    return layer;
  }

  private List<Problem> problems(String view) throws Exception {
    Path l = layer("one" + System.nanoTime(), Map.of("mail.inbox", view), COMMAND);
    Ruleset ruleset = LayerStack.resolve(List.of(new Source("one", l)), Map.of()).ruleset();
    return new RulesetValidator(Expressions.compiler(), CATALOG).validate(ruleset);
  }

  @Test
  void aViewHasParametersSourcesAndComponents() throws Exception {
    Path l = layer("one", Map.of("mail.inbox", INBOX), COMMAND);
    LayerStack.Effective e = LayerStack.resolve(List.of(new Source("one", l)), Map.of());

    View view = e.ruleset().views().get(0);
    assertThat(view.id()).isEqualTo("mail.inbox");
    assertThat(view.schema()).isEqualTo(1);
    assertThat(view.params()).extracting(ParamDecl::name).containsExactly("folder", "message");
    assertThat(view.sources()).containsOnlyKeys("messages");
    View.Component row = view.components().get(0);
    assertThat(row.type()).isEqualTo("row");
    View.Component table = row.children().get(0);
    assertThat(table.columns()).hasSize(2);
    assertThat(table.actions()).extracting(View.Action::command).containsExactly("mail.file");
    assertThat(table.select().get().param()).isEqualTo("message");
    assertThat(row.children().get(1).children())
        .extracting(View.Component::type)
        .containsExactly("text", "button");
    assertThat(e.viewOrigins())
        .singleElement()
        .satisfies(o -> assertThat(o.owner()).isEqualTo("one"));
    assertThat(view.hash()).startsWith("sha256:");
    assertThat(problems(INBOX)).isEmpty();
  }

  @Test
  void whatCannotWorkIsReported() throws Exception {
    assertThat(problems("schema_version = 2\n" + INBOX))
        .anyMatch(p -> p.message().contains("UI schema 2"));
    assertThat(
            problems(
                INBOX
                    .replace("mail.list", "mail.move")
                    .replace("{ cel = \"view.folder\" }", "\"x\"")))
        .anyMatch(p -> p.message().contains("a view may only read"));
    assertThat(problems(INBOX.replace("capability = \"mail.list\"", "capability = \"mail.nope\"")))
        .anyMatch(p -> p.message().contains("unknown capability"));
    assertThat(problems(INBOX.replace("param = \"message\"", "param = \"other\"")))
        .anyMatch(p -> p.message().contains("select sets 'other'"));
    assertThat(problems(INBOX.replace("command = \"mail.file\"", "command = \"mail.nope\"")))
        .anyMatch(p -> p.message().contains("there is no command 'mail.nope'"));
    assertThat(
            problems(
                INBOX.replace("args = { message = { cel = \"item.id\" } }", "args = { nope = 1 }")))
        .anyMatch(p -> p.message().contains("the command has no such argument"))
        .anyMatch(p -> p.message().contains("needs the argument 'message'"));
    assertThat(problems(INBOX.replace("view.message != ''", "other.x")))
        .anyMatch(p -> p.location().contains("enabled") || p.message().contains("other"));
    // item exists only in the rows of a table or a list
    assertThat(problems(INBOX.replace("'Selected: ' + view.message", "item.id")))
        .anyMatch(p -> p.message().contains("item"));
  }

  @Test
  void aFileWithMistakesIsRefusedWithAllOfThem() throws Exception {
    Path l =
        layer(
            "bad",
            Map.of("mail.inbox", INBOX.replace("type = \"row\"", "type = \"cube\"")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("bad", l)), Map.of()))
        .hasMessageContaining("unknown component type 'cube'");
    Path none = layer("none", Map.of("mail.inbox", "title = \"x\"\n"), COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("none", none)), Map.of()))
        .hasMessageContaining("at least one [[component]]");
  }

  @Test
  void aHigherLayerReplacesAViewOnlyByNamingItsHash() throws Exception {
    Path low = layer("low", Map.of("mail.inbox", INBOX), COMMAND);
    String hash =
        LayerStack.resolve(List.of(new Source("low", low)), Map.of())
            .ruleset()
            .views()
            .get(0)
            .hash();
    String mine = INBOX.replace("title = \"Inbox\"", "title = \"My inbox\"");

    Path same = layer("same", Map.of("mail.inbox", mine));
    assertThatThrownBy(
            () ->
                LayerStack.resolve(
                    List.of(new Source("low", low), new Source("same", same)), Map.of()))
        .hasMessageContaining("already exists")
        .hasMessageContaining(hash);

    Path replacing = layer("top", Map.of("mail.inbox", "based_on = \"" + hash + "\"\n" + mine));
    LayerStack.Effective e =
        LayerStack.resolve(List.of(new Source("low", low), new Source("top", replacing)), Map.of());
    assertThat(e.ruleset().views())
        .singleElement()
        .satisfies(v -> assertThat(v.title()).isEqualTo("My inbox"));
    assertThat(e.viewOrigins())
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.layer()).isEqualTo("low");
              assertThat(o.owner()).isEqualTo("top");
            });

    Path stale =
        layer(
            "stale", Map.of("mail.inbox", "based_on = \"sha256:" + "0".repeat(64) + "\"\n" + mine));
    assertThatThrownBy(
            () ->
                LayerStack.resolve(
                    List.of(new Source("low", low), new Source("stale", stale)), Map.of()))
        .hasMessageContaining("has changed since this replacement was written");
    Path nothing =
        layer(
            "nothing",
            Map.of("other", "based_on = \"" + hash + "\"\n" + mine.replace("mail.inbox", "other")));
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("nothing", nothing)), Map.of()))
        .hasMessageContaining("there is no view");
  }
}
