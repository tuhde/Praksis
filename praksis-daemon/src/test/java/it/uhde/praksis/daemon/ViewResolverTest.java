// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.engine.AccessPolicy;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Response.ViewComponent;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ViewResolverTest {
  @TempDir Path dir;

  private static final Instant NOW = Instant.parse("2026-02-03T04:05:06Z");

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

  private final List<String> reads = new ArrayList<>();
  private final List<String> denied = new ArrayList<>();
  private boolean allow = true;
  private boolean fail;

  private final Capability list =
      new Capability() {
        @Override
        public String name() {
          return "mail.list";
        }

        @Override
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args)
            throws it.uhde.praksis.capability.CapabilityException {
          reads.add((String) args.get("folder"));
          if (fail) {
            throw new it.uhde.praksis.capability.CapabilityException("server is down", true);
          }
          return Map.of(
              "items",
              List.of(
                  Map.of(
                      "id",
                      "m1",
                      "from",
                      "a@x",
                      "when",
                      NOW,
                      "raw",
                      new byte[] {1, 2},
                      "unread",
                      true),
                  Map.of(
                      "id",
                      "m2",
                      "from",
                      "b@x",
                      "when",
                      NOW,
                      "raw",
                      new byte[] {3},
                      "unread",
                      false)));
        }
      };

  private ViewResolver resolver(String view, String command) throws IOException, Exception {
    Path layer = dir.resolve("l" + System.nanoTime());
    Files.createDirectories(layer.resolve("views"));
    Files.createDirectories(layer.resolve("rules"));
    Files.writeString(
        layer.resolve("ruleset.toml"),
        "format_version = 1\nname = \"t\"\n[param.limit]\ntype = \"int\"\ndefault = 5\n");
    Files.writeString(layer.resolve("views/v.toml"), view);
    if (command != null) {
      Files.writeString(layer.resolve("rules/mail.file.toml"), command);
    }
    LayerStack.Effective e =
        LayerStack.resolve(List.of(new LayerStack.Source("mine", layer)), Map.of());
    return new ViewResolver(
        e.ruleset(),
        Map.of("v", "mine"),
        CATALOG,
        Expressions.compiler(),
        Map.of("mail.list", list),
        (rule, capability, args) ->
            allow
                ? AccessPolicy.Decision.allow()
                : AccessPolicy.Decision.deny("layer 'mine' has no grant"),
        (time, rule, capability, args, allowed, error) -> {
          if (!allowed) {
            denied.add(rule + " " + capability + " " + error.orElse(""));
          }
        },
        Clock.fixed(NOW, ZoneOffset.UTC));
  }

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

  private static final String VIEW =
      """
      title = "Inbox"
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
      type = "text"
      value = { cel = "view.folder + ' (' + string(size(source.messages.items)) + ')'" }
      style = "title"

      [[component]]
      type = "table"
      rows = { cel = "source.messages.items" }
      empty = "No mail"
      columns = [
        { title = "From", value = { cel = "item.from" } },
        { title = "When", value = { cel = "item.when" }, format = "datetime" },
        { title = "Raw", value = { cel = "item.raw" } },
        { title = "Limit", value = { cel = "params.limit" } },
      ]
      select = { param = "message", value = { cel = "item.id" } }
      [[component.row_action]]
      label = "Archive"
      command = "mail.file"
      args = { message = { cel = "item.id" } }
      confirm = "Sure?"

      [[component]]
      type = "button"
      label = "Archive selected"
      command = "mail.file"
      args = { message = { cel = "view.message" } }
      enabled = { cel = "view.message != ''" }

      [[component]]
      type = "form"
      command = "mail.file"
      [component.defaults]
      message = { cel = "view.message" }
      """;

  @Test
  void aViewBecomesAPlainDescriptionWithItsDataAndItsActions() throws Exception {
    Response r = resolver(VIEW, COMMAND).resolve("v", Map.of("message", "m2"), 1);

    Response.ResolvedView view = (Response.ResolvedView) r;
    assertThat(view.title()).isEqualTo("Inbox");
    assertThat(view.params())
        .extracting(Response.ViewList.Param::value)
        .containsExactly("INBOX", "m2");
    assertThat(view.warnings()).isEmpty();
    assertThat(reads).containsExactly("INBOX");

    ViewComponent heading = view.components().get(0);
    assertThat(heading.text()).isEqualTo("INBOX (2)");
    assertThat(heading.style()).isEqualTo("title");

    ViewComponent table = view.components().get(1);
    assertThat(table.error()).isNull();
    assertThat(table.columns())
        .extracting(ViewComponent.Column::title)
        .containsExactly("From", "When", "Raw", "Limit");
    assertThat(table.rows()).hasSize(2);
    ViewComponent.Row first = table.rows().get(0);
    assertThat(first.cells()).containsExactly("a@x", "2026-02-03T04:05:06Z", "AQI=", 5L);
    assertThat(first.select().param()).isEqualTo("message");
    assertThat(first.select().value()).isEqualTo("m1");
    assertThat(first.actions())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.command()).isEqualTo("mail.file");
              assertThat(a.args()).containsEntry("message", "m1");
              assertThat(a.confirm()).isEqualTo("Sure?");
            });

    ViewComponent button = view.components().get(2);
    assertThat(button.enabled()).isTrue();
    assertThat(button.actions().get(0).args()).containsEntry("message", "m2");

    ViewComponent.Form form = view.components().get(3).form();
    assertThat(form.title()).isEqualTo("File a mail");
    assertThat(form.fields())
        .extracting(ViewComponent.FormField::name)
        .containsExactly("message", "folder");
    assertThat(form.fields().get(0).value()).isEqualTo("m2");
    assertThat(form.fields().get(1).value()).isEqualTo("Archive");

    // without a selection the button is off
    Response.ResolvedView unselected =
        (Response.ResolvedView) resolver(VIEW, COMMAND).resolve("v", Map.of(), 1);
    assertThat(unselected.components().get(2).enabled()).isFalse();
  }

  @Test
  void theStateOfTheViewIsCheckedAndDrivesTheSources() throws Exception {
    ViewResolver resolver = resolver(VIEW, COMMAND);
    resolver.resolve("v", Map.of("folder", "Archive"), 1);
    assertThat(reads).containsExactly("Archive");
    assertThat(resolver.resolve("v", Map.of("nope", 1), 1)).isInstanceOf(Response.Error.class);
    assertThat(resolver.resolve("v", Map.of("folder", 3), 1)).isInstanceOf(Response.Error.class);
    assertThat(resolver.resolve("other", Map.of(), 1)).isInstanceOf(Response.Error.class);
  }

  @Test
  void aViewForANewerSchemaIsReportedNotShown() throws Exception {
    Response r = resolver("schema_version = 2\n" + VIEW, COMMAND).resolve("v", Map.of(), 1);
    assertThat(((Response.Error) r).message()).contains("UI schema 2").contains("up to 1");
    assertThat(resolver("schema_version = 2\n" + VIEW, COMMAND).resolve("v", Map.of(), 0))
        .isInstanceOf(Response.ResolvedView.class);
  }

  @Test
  void aSourceThatNoGrantCoversOrThatFailsIsReportedAndTheComponentsSaySo() throws Exception {
    allow = false;
    Response.ResolvedView refused =
        (Response.ResolvedView) resolver(VIEW, COMMAND).resolve("v", Map.of(), 1);
    assertThat(reads).isEmpty();
    assertThat(refused.warnings())
        .singleElement()
        .satisfies(w -> assertThat(w).contains("no grant"));
    assertThat(denied).singleElement().satisfies(d -> assertThat(d).startsWith("view:v mail.list"));
    assertThat(refused.components().get(1).error()).isNotNull();
    assertThat(refused.components().get(0).error()).isNotNull();
    assertThat(refused.components().get(2).enabled())
        .isFalse(); // a part that does not need the source works

    allow = true;
    fail = true;
    Response.ResolvedView down =
        (Response.ResolvedView) resolver(VIEW, COMMAND).resolve("v", Map.of(), 1);
    assertThat(down.warnings())
        .singleElement()
        .satisfies(w -> assertThat(w).contains("server is down"));
  }

  @Test
  void aListShowsATitleAndASubtitleAndADetailShowsFields() throws Exception {
    String view =
        """
        title = "L"
        [source.messages]
        capability = "mail.list"
        args = { folder = "INBOX" }
        [[component]]
        type = "list"
        items = { cel = "source.messages.items" }
        title = { cel = "item.from" }
        subtitle = { cel = "item.id" }
        [[component]]
        type = "detail"
        fields = [
          { label = "First", value = { cel = "source.messages.items[0].from" } },
          { label = "Count", value = { cel = "size(source.messages.items)" }, format = "number" },
        ]
        """;
    Response.ResolvedView r =
        (Response.ResolvedView) resolver(view, null).resolve("v", Map.of(), 1);
    assertThat(r.components().get(0).error()).isNull();
    assertThat(r.components().get(0).rows().get(1).cells()).containsExactly("b@x", "m2");
    assertThat(r.components().get(1).fields())
        .extracting(ViewComponent.Field::value)
        .containsExactly("a@x", 2L);
    assertThat(r.components().get(1).fields().get(1).format()).isEqualTo("number");
  }

  @Test
  void aSourceThatIsNotWantedIsNotReadAndAPartThatIsNotVisibleIsLeftOut() throws Exception {
    String view =
        """
        title = "Reader"
        [param.message]
        type = "string"
        default = ""
        [source.messages]
        capability = "mail.list"
        args = { folder = "INBOX" }
        [source.current]
        capability = "mail.list"
        args = { folder = "Selected" }
        when = { cel = "view.message != ''" }

        [[component]]
        type = "table"
        width = 320
        rows = { cel = "source.messages.items" }
        columns = [ { title = "From", value = { cel = "item.from" } } ]
        emphasis = { cel = "item.unread" }
        select = { param = "message", value = { cel = "item.id" }, clear = ["other"] }

        [[component]]
        type = "text"
        visible = { cel = "view.message != ''" }
        value = { cel = "'Open: ' + view.message + ' (' + string(size(source.current.items)) + ')'" }
        """;
    ViewResolver resolver = resolver(view, null);

    // nothing selected: the second source is not read, and the text is left out
    Response.ResolvedView none = (Response.ResolvedView) resolver.resolve("v", Map.of(), 1);
    assertThat(reads).containsExactly("INBOX");
    assertThat(none.warnings()).isEmpty();
    assertThat(none.components())
        .singleElement()
        .satisfies(
            table -> {
              assertThat(table.type()).isEqualTo("table");
              assertThat(table.width()).isEqualTo(320);
              assertThat(table.rows()).extracting(r -> r.emphasis()).containsExactly(true, false);
              assertThat(table.rows().get(0).select().clear()).containsExactly("other");
            });

    // something selected: both are read and the text shows
    Response.ResolvedView some =
        (Response.ResolvedView) resolver.resolve("v", Map.of("message", "m1"), 1);
    assertThat(reads).containsExactly("INBOX", "INBOX", "Selected");
    assertThat(some.components()).hasSize(2);
    assertThat(some.components().get(1).text()).isEqualTo("Open: m1 (2)");
    assertThat(some.components().get(1).width()).isNull();
  }
}
