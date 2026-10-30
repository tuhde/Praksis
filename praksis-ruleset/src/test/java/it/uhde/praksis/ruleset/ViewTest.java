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
                          "mail.get",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(new FieldDescriptor("message", ValueType.STRING, true, false)),
                          List.of(
                              new FieldDescriptor("blocks", ValueType.LIST, true, false),
                              new FieldDescriptor("blocked", ValueType.INT, true, false))),
                      new CapabilityDescriptor(
                          "mail.attachment",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(
                              new FieldDescriptor("message", ValueType.STRING, true, false),
                              new FieldDescriptor("name", ValueType.STRING, true, false)),
                          List.of(new FieldDescriptor("content", ValueType.BYTES, true, false))),
                      new CapabilityDescriptor(
                          "speech.listen",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(
                              new FieldDescriptor("seconds", ValueType.INT, false, false),
                              new FieldDescriptor("profile", ValueType.STRING, false, true)),
                          List.of(new FieldDescriptor("text", ValueType.STRING, true, false))),
                      new CapabilityDescriptor(
                          "contacts.search",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(new FieldDescriptor("query", ValueType.STRING, true, false)),
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

  @Test
  void sourcesPartsAndRowsCanBeConditionalSizedAndEmphasized() throws Exception {
    String view =
        """
        title = "Reader"
        [param.folder]
        type = "string"
        default = "INBOX"
        [param.message]
        type = "string"
        default = ""
        [source.current]
        capability = "mail.list"
        args = { folder = { cel = "view.folder" } }
        when = { cel = "view.message != ''" }

        [[component]]
        type = "list"
        width = 200
        items = { cel = "source.current.items" }
        title = { cel = "item.subject" }
        emphasis = { cel = "item.unread" }
        select = { param = "message", value = { cel = "item.id" }, clear = ["folder"] }

        [[component]]
        type = "text"
        visible = { cel = "view.message != ''" }
        value = "x"
        """;
    assertThat(problems(view)).isEmpty();
    Path l = layer("c" + System.nanoTime(), Map.of("mail.inbox", view), COMMAND);
    View v = LayerStack.resolve(List.of(new Source("c", l)), Map.of()).ruleset().views().get(0);
    assertThat(v.sources().get("current").when()).isPresent();
    assertThat(v.components().get(0).values()).containsKeys("width", "emphasis");
    assertThat(v.components().get(0).select().get().clear()).containsExactly("folder");
    assertThat(v.components().get(1).values()).containsKey("visible");

    assertThat(
            problems(
                view.replace(
                    "visible = { cel = \"view.message != ''\" }",
                    "visible = { cel = \"other.x\" }")))
        .anyMatch(p -> p.message().contains("other"));
    assertThat(
            problems(
                view.replace(
                    "when = { cel = \"view.message != ''\" }", "when = { cel = \"nope.x\" }")))
        .anyMatch(p -> p.location().contains("when"));
    String broken = view.replace("width = 200", "width = 5");
    Path bad = layer("w" + System.nanoTime(), Map.of("mail.inbox", broken), COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("w", bad)), Map.of()))
        .hasMessageContaining("'width' must be a whole number of pixels");
  }

  @Test
  void aClickCanStartACommandAButtonCanOnlySetStateAndAnInputIsBoundToAParameter()
      throws Exception {
    String view =
        """
        title = "Reader"
        [param.folder]
        type = "string"
        default = "INBOX"
        [param.message]
        type = "string"
        default = ""
        [param.limit]
        type = "int"
        default = 100
        [param.query]
        type = "string"
        default = ""
        [source.messages]
        capability = "mail.list"
        args = { folder = { cel = "view.folder" } }

        [[component]]
        type = "list"
        items = { cel = "source.messages.items" }
        title = { cel = "item.subject" }
        select = { param = "message", value = { cel = "item.id" }, then = { command = "mail.file", args = { message = { cel = "item.id" } } } }

        [[component]]
        type = "button"
        label = "More"
        sets = { param = "limit", value = { cel = "view.limit + 100" } }

        [[component]]
        type = "input"
        param = "query"
        placeholder = "Search"
        """;
    assertThat(problems(view)).isEmpty();
    Path l = layer("t" + System.nanoTime(), Map.of("mail.inbox", view), COMMAND);
    View v = LayerStack.resolve(List.of(new Source("t", l)), Map.of()).ruleset().views().get(0);
    View.Select select = v.components().get(0).select().get();
    assertThat(select.then().get().command()).isEqualTo("mail.file");
    assertThat(select.then().get().args()).containsKey("message");
    View.Component more = v.components().get(1);
    assertThat(more.select().get().param()).isEqualTo("limit");
    assertThat(more.actions()).isEmpty();
    assertThat(v.components().get(2).values()).containsKeys("param", "placeholder");

    assertThat(
            problems(
                view.replace("then = { command = \"mail.file\"", "then = { command = \"nope\"")))
        .anyMatch(p -> p.message().contains("there is no command 'nope'"));
    assertThat(problems(view.replace("param = \"limit\"", "param = \"other\"")))
        .anyMatch(p -> p.message().contains("sets 'other'"));
    assertThat(problems(view.replace("param = \"query\"", "param = \"other\"")))
        .anyMatch(p -> p.message().contains("input is bound to 'other'"));
    assertThat(
            problems(view.replace("clear", "clear").replace("then =", "clear = [\"zzz\"], then =")))
        .anyMatch(p -> p.message().contains("clear names 'zzz'"));
    Path neither =
        layer(
            "n" + System.nanoTime(),
            Map.of(
                "mail.inbox",
                view.replace(
                    "sets = { param = \"limit\", value = { cel = \"view.limit + 100\" } }", "")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("n", neither)), Map.of()))
        .hasMessageContaining("missing 'command'");
    Path bad =
        layer(
            "u" + System.nanoTime(),
            Map.of("mail.inbox", view.replace("param = \"query\"\n", "")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("u", bad)), Map.of()))
        .hasMessageContaining("missing 'param'");
  }

  private static final String READER =
      """
      title = "Reader"
      [param.message]
      type = "string"
      default = ""
      [param.images]
      type = "bool"
      default = false
      [source.current]
      capability = "mail.get"
      args = { message = { cel = "view.message" } }

      [[component]]
      type = "richtext"
      blocks = { cel = "source.current.blocks" }
      blocked = { cel = "source.current.blocked" }
      images_param = "images"

      [[component]]
      type = "table"
      rows = { cel = "[{'name': 'a.pdf'}]" }
      columns = [ { title = "Name", value = { cel = "item.name" } } ]
      [[component.row_action]]
      label = "Save"
      download = { capability = "mail.attachment", args = { message = { cel = "view.message" }, name = { cel = "item.name" } }, filename = { cel = "item.name" } }
      """;

  @Test
  void richTextAndDownloadsAreParsedAndChecked() throws Exception {
    assertThat(problems(READER)).isEmpty();
    Path l = layer("r" + System.nanoTime(), Map.of("mail.inbox", READER), COMMAND);
    View v = LayerStack.resolve(List.of(new Source("r", l)), Map.of()).ruleset().views().get(0);
    assertThat(v.components().get(0).type()).isEqualTo("richtext");
    assertThat(v.components().get(0).values()).containsKeys("blocks", "blocked", "images_param");
    View.Download download = v.components().get(1).actions().get(0).download().get();
    assertThat(download.capability()).isEqualTo("mail.attachment");
    assertThat(download.args()).containsKeys("message", "name");

    // what cannot work is said
    assertThat(problems(READER.replace("images_param = \"images\"", "images_param = \"nope\"")))
        .anyMatch(p -> p.message().contains("images_param names 'nope'"));
    assertThat(
            problems(
                READER.replace("capability = \"mail.attachment\"", "capability = \"mail.move\"")))
        .anyMatch(p -> p.message().contains("a download may only read"))
        .anyMatch(p -> p.message().contains("does not answer with 'content'"));
    assertThat(
            problems(
                READER.replace("capability = \"mail.attachment\"", "capability = \"mail.get\"")))
        .anyMatch(p -> p.message().contains("does not answer with 'content'"));
    assertThat(
            problems(
                READER.replace("capability = \"mail.attachment\"", "capability = \"mail.nope\"")))
        .anyMatch(p -> p.message().contains("unknown capability 'mail.nope'"));
    assertThat(
            problems(
                READER.replace(
                    "name = { cel = \"item.name\" } }, filename", "nope = 1 }, filename")))
        .anyMatch(p -> p.message().contains("no such parameter"))
        .anyMatch(p -> p.message().contains("missing required argument 'name'"));
    assertThat(
            problems(
                READER.replace(
                    "filename = { cel = \"item.name\" }", "filename = { cel = \"nope.x\" }")))
        .anyMatch(p -> p.location().contains("filename"));
    // an action downloads or runs a command, and a download needs its parts
    Path both =
        layer(
            "b" + System.nanoTime(),
            Map.of(
                "mail.inbox",
                READER.replace("label = \"Save\"", "label = \"Save\"\ncommand = \"mail.file\"")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("b", both)), Map.of()))
        .hasMessageContaining("not both");
    Path nameless =
        layer(
            "n" + System.nanoTime(),
            Map.of("mail.inbox", READER.replace(", filename = { cel = \"item.name\" }", "")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("n", nameless)), Map.of()))
        .hasMessageContaining("missing 'filename'");
    Path blocks =
        layer(
            "c" + System.nanoTime(),
            Map.of(
                "mail.inbox", READER.replace("blocks = { cel = \"source.current.blocks\" }", "")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("c", blocks)), Map.of()))
        .hasMessageContaining("missing 'blocks'");
  }

  private static final String WRITE_COMMAND =
      """
      id = "mail.write"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "command"
      title = "Write"
      [[trigger.param]]
      name = "message"
      type = "string"
      widget = "multiline"
      [[trigger.param]]
      name = "to"
      type = "string"
      default = ""
      widget = "recipients"
      [[trigger.param]]
      name = "attachments"
      type = "list"
      default = []
      widget = "files"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Drafts" }
      """;

  private static final String DRAFT_COMMAND =
      """
      id = "mail.draft"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "command"
      title = "Save a draft"
      [[trigger.param]]
      name = "message"
      type = "string"
      [[trigger.param]]
      name = "to"
      type = "string"
      default = ""

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = "Drafts" }
      """;

  private static final String COMPOSE =
      """
      title = "Write"
      [[component]]
      type = "form"
      command = "mail.write"
      submit_label = "Send"
      also = [ { label = "Save draft", command = "mail.draft" } ]
      lookup = { capability = "contacts.search", arg = "query" }
      """;

  /** A layer with this view and these rules, each in a file of its own. */
  private Path layerOf(String view, String write, String draft) throws Exception {
    Path l = layer("w" + System.nanoTime(), Map.of("mail.inbox", view));
    Files.writeString(l.resolve("rules/mail.write.toml"), write);
    if (draft != null) {
      Files.writeString(l.resolve("rules/mail.draft.toml"), draft);
    }
    return l;
  }

  private List<Problem> composeProblems(String view, String draft) throws Exception {
    Path l = layerOf(view, WRITE_COMMAND, draft);
    Ruleset ruleset = LayerStack.resolve(List.of(new Source("w", l)), Map.of()).ruleset();
    return new RulesetValidator(Expressions.compiler(), CATALOG).validate(ruleset);
  }

  @Test
  void aFormCanHaveMoreButtonsAndALookupAndParametersHaveWidgets() throws Exception {
    assertThat(composeProblems(COMPOSE, DRAFT_COMMAND)).isEmpty();
    Path l = layerOf(COMPOSE, WRITE_COMMAND, DRAFT_COMMAND);
    var ruleset = LayerStack.resolve(List.of(new Source("w", l)), Map.of()).ruleset();
    View.Component form = ruleset.views().get(0).components().get(0);
    assertThat(form.actions()).extracting(View.Action::command).containsExactly("mail.draft");
    assertThat(form.actions()).extracting(View.Action::label).containsExactly("Save draft");
    assertThat(form.values()).containsKeys("lookup.capability", "lookup.arg");
    var write = ruleset.rules().stream().filter(r -> r.id().equals("mail.write")).findFirst().get();
    assertThat(((Trigger.OnCommand) write.trigger()).params())
        .extracting(p -> p.widget().orElse(""))
        .containsExactly("multiline", "recipients", "files");

    // what cannot work is said
    assertThat(
            composeProblems(
                COMPOSE.replace("command = \"mail.draft\"", "command = \"mail.nope\""),
                DRAFT_COMMAND))
        .anyMatch(p -> p.message().contains("there is no command 'mail.nope'"));
    String needsMore =
        DRAFT_COMMAND.replace(
            "name = \"to\"\ntype = \"string\"\ndefault = \"\"",
            "name = \"nope\"\ntype = \"string\"");
    assertThat(composeProblems(COMPOSE, needsMore))
        .anyMatch(p -> p.message().contains("needs 'nope' (string), which the form does not have"));
    assertThat(composeProblems(COMPOSE.replace("contacts.search", "mail.move"), DRAFT_COMMAND))
        .anyMatch(p -> p.message().contains("'mail.move' does not only read"))
        .anyMatch(p -> p.message().contains("no text argument 'query'"))
        .anyMatch(p -> p.message().contains("does not answer with 'items'"));
    assertThat(composeProblems(COMPOSE.replace("contacts.search", "mail.nope"), DRAFT_COMMAND))
        .anyMatch(p -> p.message().contains("unknown capability 'mail.nope'"));
    assertThat(composeProblems(COMPOSE.replace("arg = \"query\"", "arg = \"nope\""), DRAFT_COMMAND))
        .anyMatch(p -> p.message().contains("no text argument 'nope'"));
  }

  @Test
  void aWidgetMustSuitTheTypeAndBeKnown() throws Exception {
    Path wrong =
        layerOf(
            COMPOSE,
            WRITE_COMMAND.replace("widget = \"files\"", "widget = \"multiline\""),
            DRAFT_COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("w", wrong)), Map.of()))
        .hasMessageContaining("the widget 'multiline' is for a string");
    Path unknown =
        layerOf(
            COMPOSE,
            WRITE_COMMAND.replace("widget = \"files\"", "widget = \"sparkles\""),
            DRAFT_COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("w", unknown)), Map.of()))
        .hasMessageContaining("unknown widget 'sparkles'");
  }

  private static final String CHAIN =
      """
      title = "T"
      [param.m]
      type = "string"
      default = ""
      [source.first]
      capability = "mail.get"
      args = { message = { cel = "view.m" } }
      [source.second]
      capability = "mail.get"
      args = { message = { cel = "string(source.first.blocked)" } }
      when = { cel = "view.m != ''" }

      [[component]]
      type = "button"
      label = "Reply"
      opens = { view = "mail.inbox", params = { m = { cel = "view.m" } } }

      [[component]]
      type = "table"
      rows = { cel = "[{'id': 'x'}]" }
      columns = [ { title = "Id", value = { cel = "item.id" } } ]
      [[component.row_action]]
      label = "Open"
      opens = { view = "mail.inbox", params = { m = { cel = "item.id" } } }
      """;

  @Test
  void aSourceMayUseTheOnesBeforeItAndAnActionCanOpenAnotherViewWithState() throws Exception {
    assertThat(problems(CHAIN)).isEmpty();
    Path l = layer("o" + System.nanoTime(), Map.of("mail.inbox", CHAIN), COMMAND);
    View v = LayerStack.resolve(List.of(new Source("o", l)), Map.of()).ruleset().views().get(0);
    assertThat(v.sources().keySet()).containsExactly("first", "second"); // in the order written
    View.Opens opens = v.components().get(0).actions().get(0).opens().get();
    assertThat(opens.view()).isEqualTo("mail.inbox");
    assertThat(opens.params()).containsKey("m");
    assertThat(v.components().get(1).actions().get(0).opens()).isPresent();

    assertThat(
            problems(
                CHAIN.replace(
                    "view = \"mail.inbox\", params = { m = { cel = \"view.m\" } }",
                    "view = \"mail.nope\", params = {}")))
        .anyMatch(p -> p.message().contains("there is no view 'mail.nope'"));
    assertThat(
            problems(
                CHAIN.replace("params = { m = { cel = \"view.m\" } }", "params = { other = 1 }")))
        .anyMatch(p -> p.message().contains("has no parameter 'other'"));
    assertThat(
            problems(
                CHAIN.replace(
                    "params = { m = { cel = \"item.id\" } }",
                    "params = { m = { cel = \"nope.id\" } }")))
        .anyMatch(p -> p.location().contains("opens"));
    assertThat(
            problems(CHAIN.replace("string(source.first.blocked)", "string(other.first.blocked)")))
        .anyMatch(p -> p.message().contains("other"));
    Path both =
        layer(
            "p" + System.nanoTime(),
            Map.of(
                "mail.inbox",
                CHAIN.replace("label = \"Reply\"", "label = \"Reply\"\ncommand = \"mail.file\"")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("p", both)), Map.of()))
        .hasMessageContaining("only one");
  }

  private static final String DRAGGING =
      """
      title = "Mail"
      [param.folder]
      type = "string"
      default = "INBOX"
      [source.messages]
      capability = "mail.list"
      args = { folder = { cel = "view.folder" } }

      [[component]]
      type = "table"
      rows = { cel = "source.messages.items" }
      columns = [ { title = "From", value = { cel = "item.from" } } ]
      drag = { cel = "item.id" }

      [[component]]
      type = "list"
      items = { cel = "[{'name': 'Archive'}]" }
      title = { cel = "item.name" }
      drop = { command = "mail.file", param = "message", args = { folder = { cel = "item.name" } } }

      [[component]]
      type = "button"
      label = "Archive"
      command = "mail.file"
      args = { message = "x" }
      key = "a"

      [[component]]
      type = "input"
      param = "folder"
      key = "/"
      """;

  @Test
  void rowsCanBeDraggedAndAcceptDropsAndButtonsAndInputsHaveKeys() throws Exception {
    assertThat(problems(DRAGGING)).isEmpty();
    Path l = layer("d" + System.nanoTime(), Map.of("mail.inbox", DRAGGING), COMMAND);
    View v = LayerStack.resolve(List.of(new Source("d", l)), Map.of()).ruleset().views().get(0);
    assertThat(v.components().get(0).values()).containsKey("drag");
    assertThat(v.components().get(1).values())
        .containsKeys("drop.command", "drop.param", "drop.arg.folder");
    assertThat(v.components().get(2).values().get("key")).isEqualTo(new Arg.Literal("a"));
    assertThat(v.components().get(3).values().get("key")).isEqualTo(new Arg.Literal("/"));

    // what cannot work is said
    assertThat(
            problems(
                DRAGGING.replace(
                    "command = \"mail.file\", param", "command = \"mail.nope\", param")))
        .anyMatch(p -> p.message().contains("there is no command 'mail.nope'"));
    assertThat(problems(DRAGGING.replace("param = \"message\"", "param = \"other\"")))
        .anyMatch(p -> p.message().contains("has no parameter 'other'"));
    // what the dragged value does not fill, the row must
    assertThat(problems(DRAGGING.replace("param = \"message\"", "param = \"folder\"")))
        .anyMatch(p -> p.message().contains("needs the argument 'message'"));
    assertThat(
            problems(
                DRAGGING.replace(
                    "args = { folder = { cel = \"item.name\" } }",
                    "args = { message = \"x\", folder = \"y\" }")))
        .anyMatch(p -> p.message().contains("is the dragged value and cannot be given too"));
    assertThat(
            problems(
                DRAGGING.replace("drag = { cel = \"item.id\" }", "drag = { cel = \"nope.id\" }")))
        .anyMatch(p -> p.message().contains("nope"));
    assertThat(problems(DRAGGING.replace("key = \"/\"", "key = \"a\"")))
        .anyMatch(p -> p.message().contains("the key 'a' is used by 2 parts"));
    Path badKey =
        layer(
            "k" + System.nanoTime(),
            Map.of("mail.inbox", DRAGGING.replace("key = \"a\"", "key = \"ab\"")),
            COMMAND);
    assertThatThrownBy(() -> LayerStack.resolve(List.of(new Source("k", badKey)), Map.of()))
        .hasMessageContaining("key must be one character or one of");
  }

  @Test
  void aKeyMayBeSharedByPartsThatAreShownOneAtATime() throws Exception {
    String toggle =
        """
        title = "Toggle"
        [param.on]
        type = "bool"
        default = false
        [[component]]
        type = "button"
        label = "Turn on"
        sets = { param = "on", value = true }
        key = "t"
        visible = { cel = "!view.on" }
        [[component]]
        type = "button"
        label = "Turn off"
        sets = { param = "on", value = false }
        key = "t"
        visible = { cel = "view.on" }
        """;
    assertThat(problems(toggle)).isEmpty();
    // but one that is always there and another with the same key are two things on one key
    assertThat(problems(toggle.replace("visible = { cel = \"view.on\" }", "")))
        .anyMatch(p -> p.message().contains("the key 't' is used by 2 parts"))
        .anyMatch(p -> p.message().contains("only allowed if each of them has 'visible'"));
  }

  private static final String SEARCH =
      """
      title = "Search"
      [param.query]
      type = "string"
      default = ""

      [[component]]
      type = "input"
      param = "query"
      placeholder = "Search"
      dictate = { capability = "speech.listen", args = { seconds = 6, profile = "en" }, requires_modules = ["speech"] }
      """;

  private List<Problem> searchProblems(String view) throws Exception {
    Path l = layer("s" + System.nanoTime(), Map.of("search", view));
    Ruleset ruleset = LayerStack.resolve(List.of(new Source("s", l)), Map.of()).ruleset();
    return new RulesetValidator(Expressions.compiler(), CATALOG).validate(ruleset);
  }

  @Test
  void anInputCanHaveAMicrophoneThatNeedsAReadingCapabilityThatAnswersWithText() throws Exception {
    assertThat(searchProblems(SEARCH)).isEmpty();
    Path l = layer("m", Map.of("search", SEARCH));
    var effective = LayerStack.resolve(List.of(new Source("m", l)), Map.of());
    View view = effective.ruleset().views().get(0);
    assertThat(view.components().get(0).values())
        .containsKeys("dictate.capability", "dictate.arg.seconds", "dictate.arg.profile");

    // it is a requirement of the view like a source: the grants decide, and can limit the profile
    assertThat(Permissions.of(effective, CATALOG).requirements())
        .anyMatch(
            r -> r.capability().equals("speech.listen") && "en".equals(r.known().get("profile")));

    // without the module the microphone goes and the input stays
    View without = view.available(java.util.Set.of()).orElseThrow();
    assertThat(without.components().get(0).values().keySet())
        .noneMatch(k -> k.startsWith("dictate."))
        .contains("param");
    assertThat(
            view.available(java.util.Set.of("speech")).orElseThrow().components().get(0).values())
        .containsKey("dictate.capability");

    // what cannot work is said
    assertThat(searchProblems(SEARCH.replace("speech.listen", "mail.move")))
        .anyMatch(p -> p.message().contains("'mail.move' does not only read"))
        .anyMatch(p -> p.message().contains("does not answer with 'text'"));
    assertThat(searchProblems(SEARCH.replace("speech.listen", "mail.list")))
        .anyMatch(p -> p.message().contains("does not answer with 'text'"));
    assertThat(searchProblems(SEARCH.replace("speech.listen", "speech.nope")))
        .anyMatch(p -> p.message().contains("unknown capability 'speech.nope'"));
    assertThat(searchProblems(SEARCH.replace("seconds = 6", "minutes = 6")))
        .anyMatch(p -> p.message().contains("has no argument 'minutes'"));
    assertThat(searchProblems(SEARCH.replace("seconds = 6", "seconds = \"six\"")))
        .anyMatch(p -> p.message().contains("argument 'seconds' must be a int"));
    assertThatThrownBy(
            () -> searchProblems(SEARCH.replace("seconds = 6", "seconds = { cel = \"1\" }")))
        .hasMessageContaining("is a value, not an expression");
  }

  @Test
  void aButtonCanOpenAScreenOfTheFrontendByItsNameAndNothingElse() throws Exception {
    String view =
        CHAIN.replace(
            "opens = { view = \"mail.inbox\", params = { m = { cel = \"view.m\" } } }",
            "opens = { screen = \"agent\" }");
    assertThat(problems(view)).isEmpty();
    Path l = layer("sc" + System.nanoTime(), Map.of("mail.inbox", view), COMMAND);
    View v = LayerStack.resolve(List.of(new Source("sc", l)), Map.of()).ruleset().views().get(0);
    assertThat(v.components().get(0).actions().get(0).opens().get().screen()).contains("agent");

    assertThat(problems(view.replace("screen = \"agent\"", "screen = \"nowhere\"")))
        .anyMatch(p -> p.message().contains("there is no screen 'nowhere'"));
    assertThatThrownBy(
            () ->
                problems(
                    view.replace(
                        "screen = \"agent\"", "screen = \"agent\", view = \"mail.inbox\"")))
        .hasMessageContaining("'screen' is used alone");
  }
}
