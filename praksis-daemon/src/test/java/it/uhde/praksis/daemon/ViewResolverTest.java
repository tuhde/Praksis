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
                          "mail.get",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(new FieldDescriptor("message", ValueType.STRING, true, false)),
                          List.of(
                              new FieldDescriptor("blocks", ValueType.LIST, true, false),
                              new FieldDescriptor("blocked", ValueType.INT, true, false))),
                      new CapabilityDescriptor(
                          "contacts.search",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(new FieldDescriptor("query", ValueType.STRING, true, false)),
                          List.of(new FieldDescriptor("items", ValueType.LIST, true, false))),
                      new CapabilityDescriptor(
                          "speech.listen",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(
                              new FieldDescriptor("seconds", ValueType.INT, false, false),
                              new FieldDescriptor("profile", ValueType.STRING, false, true)),
                          List.of(new FieldDescriptor("text", ValueType.STRING, true, false))),
                      new CapabilityDescriptor(
                          "mail.attachment",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(
                              new FieldDescriptor("message", ValueType.STRING, true, false),
                              new FieldDescriptor("name", ValueType.STRING, true, false)),
                          List.of(
                              new FieldDescriptor("content", ValueType.BYTES, true, false),
                              new FieldDescriptor("type", ValueType.STRING, true, false))),
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

  /** What a mail module answers for the HTML of a mail: blocks, some of them hostile. */
  private final Capability get =
      new Capability() {
        @Override
        public String name() {
          return "mail.get";
        }

        @Override
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args) {
          gets.add((String) args.get("message"));
          Map<String, Object> bad = new java.util.LinkedHashMap<>();
          bad.put("kind", "script"); // not a kind
          bad.put("runs", List.of(Map.of("text", "evil")));
          return Map.of(
              "blocks",
              List.of(
                  Map.of(
                      "kind",
                      "heading",
                      "level",
                      2L,
                      "runs",
                      List.of(Map.of("text", "Title", "bold", true)),
                      "children",
                      List.of()),
                  bad,
                  Map.of(
                      "kind",
                      "paragraph",
                      "runs",
                      List.of(
                          Map.of("text", "ok ", "link", "https://ok.example/x"),
                          Map.of("text", "bad link", "link", "javascript:alert(1)"),
                          Map.of("text", "spaced", "link", "https://a b"),
                          Map.of("text", "pic", "image", "http://insecure.example/p.png"),
                          Map.of("text", "pic2", "image", "https://t.example/p.png"),
                          Map.of("nottext", 1)),
                      "children",
                      List.of())),
              "blocked",
              2L);
        }
      };

  private final List<String> gets = new ArrayList<>();
  private final Map<String, Object> asked = new java.util.LinkedHashMap<>();
  private final List<String> allowedCalls = new ArrayList<>();

  /** What an attachment answers: the bytes, whatever its name says. */
  private final Capability attachment =
      new Capability() {
        @Override
        public String name() {
          return "mail.attachment";
        }

        @Override
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args) {
          asked.putAll(args);
          return Map.of("content", new byte[] {1, 2, 3, 4}, "type", "application/pdf");
        }
      };

  private final List<String> searched = new ArrayList<>();
  private final List<Map<String, Object>> listened = new ArrayList<>();
  private volatile String said = "invoices from anna";

  /** The microphone: says what the test says that was said. */
  private final Capability microphone =
      new Capability() {
        @Override
        public String name() {
          return "speech.listen";
        }

        @Override
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args) {
          listened.add(args);
          return Map.of("text", said);
        }
      };

  /** The address book: matches by the start of the name. */
  private final Capability contacts =
      new Capability() {
        @Override
        public String name() {
          return "contacts.search";
        }

        @Override
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args) {
          searched.add((String) args.get("query"));
          List<Object> items = new ArrayList<>();
          for (int i = 0; i < 30; i++) {
            items.add(
                Map.of(
                    "name",
                    "Anna " + i,
                    "address",
                    "anna" + i + "@example.org",
                    "count",
                    (long) i));
          }
          return Map.of("items", items);
        }
      };

  private ViewResolver resolver(String view, String command) throws IOException, Exception {
    return resolverWith(view, command == null ? Map.of() : Map.of("mail.file", command));
  }

  private ViewResolver resolverWith(String view, Map<String, String> commands)
      throws IOException, Exception {
    Path layer = dir.resolve("l" + System.nanoTime());
    Files.createDirectories(layer.resolve("views"));
    Files.createDirectories(layer.resolve("rules"));
    Files.writeString(
        layer.resolve("ruleset.toml"),
        "format_version = 1\nname = \"t\"\n[param.limit]\ntype = \"int\"\ndefault = 5\n");
    Files.writeString(layer.resolve("views/v.toml"), view);
    for (var rule : commands.entrySet()) {
      Files.writeString(layer.resolve("rules/" + rule.getKey() + ".toml"), rule.getValue());
    }
    LayerStack.Effective e =
        LayerStack.resolve(List.of(new LayerStack.Source("mine", layer)), Map.of());
    return new ViewResolver(
        e.ruleset(),
        Map.of("v", "mine"),
        CATALOG,
        Expressions.compiler(),
        Map.of(
            "mail.list", list,
            "mail.get", get,
            "mail.attachment", attachment,
            "contacts.search", contacts,
            "speech.listen", microphone),
        (rule, capability, args) ->
            allow
                ? AccessPolicy.Decision.allow()
                : AccessPolicy.Decision.deny("layer 'mine' has no grant"),
        (time, rule, capability, args, allowed, error) -> {
          if (!allowed) {
            denied.add(rule + " " + capability + " " + error.orElse(""));
          } else {
            allowedCalls.add(rule + " " + capability + " " + args);
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

  @Test
  void aClickCanStartACommandAButtonCanSetStateAndAnInputShowsItsParameter() throws Exception {
    String view =
        """
        title = "Reader"
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
        args = { folder = "INBOX" }

        [[component]]
        type = "list"
        items = { cel = "source.messages.items" }
        title = { cel = "item.from" }
        select = { param = "message", value = { cel = "item.id" }, then = { command = "mail.file", args = { message = { cel = "item.id" } } } }

        [[component]]
        type = "button"
        label = "More"
        sets = { param = "limit", value = { cel = "view.limit + 100" }, clear = ["message"] }

        [[component]]
        type = "input"
        param = "query"
        placeholder = "Search"
        """;
    Response.ResolvedView r =
        (Response.ResolvedView)
            resolver(view, COMMAND).resolve("v", Map.of("limit", 200L, "query", "inv"), 1);

    assertThat(r.components().get(0).rows().get(0).select().then())
        .satisfies(
            a -> {
              assertThat(a.command()).isEqualTo("mail.file");
              assertThat(a.args()).containsEntry("message", "m1");
            });
    ViewComponent more = r.components().get(1);
    assertThat(more.actions()).isEmpty();
    assertThat(more.select().param()).isEqualTo("limit");
    assertThat(more.select().value()).isEqualTo(300L);
    assertThat(more.select().clear()).containsExactly("message");
    ViewComponent input = r.components().get(2);
    assertThat(input.type()).isEqualTo("input");
    assertThat(input.text()).isEqualTo("inv");
    assertThat(input.label()).isEqualTo("Search");
    assertThat(input.select().param()).isEqualTo("query");
  }

  private static final String RICH =
      """
      title = "Reader"
      [param.message]
      type = "string"
      default = "m1"
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
      rows = { cel = "[{'name': 'report.pdf'}]" }
      columns = [ { title = "Name", value = { cel = "item.name" } } ]
      [[component.row_action]]
      label = "Save"
      download = { capability = "mail.attachment", args = { message = { cel = "view.message" }, name = { cel = "item.name" } }, filename = { cel = "item.name" } }
      """;

  @Test
  void richTextIsDescribedWithOnlyWhatIsSafeAndAnythingElseIsDropped() throws Exception {
    Response.ResolvedView r =
        (Response.ResolvedView) resolver(RICH, null).resolve("v", Map.of(), 1);

    ViewComponent rich = r.components().get(0);
    assertThat(rich.error()).isNull();
    assertThat(rich.rich().blocked()).isEqualTo(2);
    assertThat(rich.rich().imagesParam()).isEqualTo("images");
    // the block of an unknown kind is gone, and so is the run that has no text
    assertThat(rich.rich().blocks())
        .extracting(b -> b.kind())
        .containsExactly("heading", "paragraph");
    assertThat(rich.rich().blocks().get(0).level()).isEqualTo(2);
    assertThat(rich.rich().blocks().get(0).runs().get(0).bold()).isTrue();
    var runs = rich.rich().blocks().get(1).runs();
    assertThat(runs)
        .extracting(x -> x.text())
        .containsExactly("ok ", "bad link", "spaced", "pic", "pic2");
    // links only http, https, mailto; images only https; no white space in an address
    assertThat(runs)
        .extracting(x -> x.link())
        .containsExactly("https://ok.example/x", null, null, null, null);
    assertThat(runs)
        .extracting(x -> x.image())
        .containsExactly(null, null, null, null, "https://t.example/p.png");
  }

  @Test
  void aHostileValueCannotMakeADescriptionLargerThanTheLimitsOrDeeper() {
    List<Object> deep = List.of(Map.of("kind", "paragraph", "runs", List.of(Map.of("text", "x"))));
    for (int i = 0; i < 40; i++) {
      deep = List.of(Map.of("kind", "quote", "children", deep));
    }
    ViewComponent.Rich cut =
        RichText.convert(deep, 0L, null).blocks().isEmpty()
            ? null
            : RichText.convert(deep, 0L, null);
    int depth = 0;
    var level = cut.blocks();
    while (!level.isEmpty()) {
      depth++;
      level = level.get(0).children();
    }
    assertThat(depth).isLessThanOrEqualTo(RichText.MAX_DEPTH + 1);

    List<Object> many = new ArrayList<>();
    for (int i = 0; i < RichText.MAX_BLOCKS + 100; i++) {
      many.add(Map.of("kind", "rule"));
    }
    assertThat(RichText.convert(many, 0L, null).blocks()).hasSize(RichText.MAX_BLOCKS);
    String big = "x".repeat(RichText.MAX_CHARS + 10);
    var one =
        RichText.convert(
            List.of(Map.of("kind", "paragraph", "runs", List.of(Map.of("text", big)))), 0L, null);
    assertThat(one.blocks().get(0).runs().get(0).text()).hasSize(RichText.MAX_CHARS);
    assertThat(RichText.convert("not a list", "no", null).blocks()).isEmpty();
    assertThat(RichText.convert(null, -5L, null).blocked()).isZero();
  }

  @Test
  void aDownloadIsDescribedWithItsArgumentsAndNothingIsReadUntilAPersonAsks() throws Exception {
    Response.ResolvedView r =
        (Response.ResolvedView) resolver(RICH, null).resolve("v", Map.of(), 1);

    var action = r.components().get(1).rows().get(0).actions().get(0);
    assertThat(action.download().capability()).isEqualTo("mail.attachment");
    assertThat(action.download().args())
        .containsEntry("message", "m1")
        .containsEntry("name", "report.pdf");
    assertThat(action.download().filename()).isEqualTo("report.pdf");
    assertThat(asked).isEmpty();
  }

  @Test
  void fetchReadsTheAttachmentUnderTheGrantsOfTheViewAndKeepsTheFileAway() throws Exception {
    ViewResolver resolver = resolver(RICH, null);
    Path downloads = dir.resolve("downloads");

    Response answer =
        resolver.fetch(
            "v",
            "mail.attachment",
            Map.of("message", "m1", "name", "report.pdf"),
            "../../etc/Rep:ort.pdf",
            downloads);

    assertThat(answer).isInstanceOf(Response.Fetched.class);
    Response.Fetched fetched = (Response.Fetched) answer;
    assertThat(fetched.name()).isEqualTo("Rep_ort.pdf");
    assertThat(fetched.type()).isEqualTo("application/pdf");
    assertThat(fetched.size()).isEqualTo(4);
    Path file = Path.of(fetched.path());
    assertThat(file.getParent()).isEqualTo(downloads);
    assertThat(file.getFileName().toString()).doesNotContain("report").doesNotContain("Rep");
    assertThat(Files.readAllBytes(file)).containsExactly(1, 2, 3, 4);
    assertThat(java.nio.file.Files.getPosixFilePermissions(file))
        .containsExactlyInAnyOrder(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
    assertThat(java.nio.file.Files.getPosixFilePermissions(downloads))
        .containsExactlyInAnyOrder(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE);
    assertThat(allowedCalls).anyMatch(c -> c.startsWith("view:v mail.attachment"));
  }

  @Test
  void fetchRefusesWhatTheViewDoesNotOfferWhatTheGrantsDoNotCoverAndBadArguments()
      throws Exception {
    ViewResolver resolver = resolver(RICH, null);
    Path downloads = dir.resolve("d2");

    assertThat(resolver.fetch("nope", "mail.attachment", Map.of(), "x", downloads))
        .isEqualTo(new Response.Error("there is no view 'nope'"));
    // the view has no download of this capability: it cannot be used to read anything else
    assertThat(resolver.fetch("v", "mail.list", Map.of("folder", "INBOX"), "x", downloads))
        .isEqualTo(new Response.Error("view 'v' has no download of 'mail.list'"));
    assertThat(reads).isEmpty();
    assertThat(resolver.fetch("v", "mail.attachment", Map.of("nope", "x"), "x", downloads))
        .isInstanceOf(Response.Error.class);

    allow = false;
    Response denied =
        resolver.fetch(
            "v", "mail.attachment", Map.of("message", "m1", "name", "a"), "x", downloads);
    assertThat(denied).isEqualTo(new Response.Error("layer 'mine' has no grant"));
    assertThat(asked).isEmpty();
    assertThat(this.denied.toString()).contains("view:v mail.attachment");
    assertThat(downloads).doesNotExist();
  }

  @Test
  void oldFilesInTheDownloadsDirectoryAreRemoved() throws Exception {
    ViewResolver resolver = resolver(RICH, null);
    Path downloads = Files.createDirectories(dir.resolve("d3"));
    Path old = Files.write(downloads.resolve("old.part"), new byte[] {9});
    Files.setLastModifiedTime(
        old, java.nio.file.attribute.FileTime.from(NOW.minusSeconds(2 * 3600)));
    Path recent = Files.write(downloads.resolve("recent.part"), new byte[] {9});
    Files.setLastModifiedTime(recent, java.nio.file.attribute.FileTime.from(NOW.minusSeconds(60)));

    resolver.fetch("v", "mail.attachment", Map.of("message", "m1", "name", "a"), "a", downloads);

    assertThat(old).doesNotExist();
    assertThat(recent).exists();
  }

  @Test
  void namesAreCleanedOfAnythingThatIsNotAName() {
    assertThat(Downloads.cleanName("report.pdf")).isEqualTo("report.pdf");
    assertThat(Downloads.cleanName("../../etc/passwd")).isEqualTo("passwd");
    assertThat(Downloads.cleanName("C:\\Users\\x\\a.txt")).isEqualTo("a.txt");
    assertThat(Downloads.cleanName(".bashrc")).isEqualTo("bashrc");
    assertThat(Downloads.cleanName("a\nb\u0000c.txt")).isEqualTo("a_b_c.txt");
    assertThat(Downloads.cleanName("   ")).isEqualTo("download");
    assertThat(Downloads.cleanName(null)).isEqualTo("download");
    assertThat(Downloads.cleanName("x".repeat(300) + ".pdf")).hasSize(120).endsWith(".pdf");
  }

  private static final String WRITE =
      """
      id = "mail.write"
      effect = "local-write"
      needs = ["mail.move"]
      [trigger]
      kind = "command"
      title = "Write"
      [[trigger.param]]
      name = "to"
      type = "string"
      default = ""
      widget = "recipients"
      [[trigger.param]]
      name = "text"
      type = "string"
      default = ""
      widget = "multiline"
      [[trigger.param]]
      name = "attachments"
      type = "list"
      default = []
      widget = "files"
      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.text" }, folder = "Drafts" }
      """;

  private static final String DRAFT =
      WRITE
          .replace("mail.write", "mail.draft")
          .replace("title = \"Write\"", "title = \"Save a draft\"");

  private static final String COMPOSE =
      """
      title = "Write"
      [param.reply_to]
      type = "string"
      default = ""
      [[component]]
      type = "form"
      command = "mail.write"
      submit_label = "Send"
      also = [ { label = "Save draft", command = "mail.draft" } ]
      lookup = { capability = "contacts.search", arg = "query" }
      [component.defaults]
      to = { cel = "view.reply_to" }
      """;

  @Test
  void aFormTellsHowToDrawItsFieldsWhichOtherButtonsItHasAndWhereSuggestionsComeFrom()
      throws Exception {
    ViewResolver resolver = resolverWith(COMPOSE, Map.of("mail.write", WRITE, "mail.draft", DRAFT));

    Response.ResolvedView r =
        (Response.ResolvedView) resolver.resolve("v", Map.of("reply_to", "anna@example.org"), 1);

    ViewComponent.Form form = r.components().get(0).form();
    assertThat(form.command()).isEqualTo("mail.write");
    assertThat(form.submit()).isEqualTo("Send");
    assertThat(form.fields())
        .extracting(ViewComponent.FormField::widget)
        .containsExactly("recipients", "multiline", "files");
    assertThat(form.fields().get(0).value()).isEqualTo("anna@example.org");
    assertThat(form.others()).containsExactly(new ViewComponent.Submit("Save draft", "mail.draft"));
    assertThat(form.lookup()).isEqualTo(new ViewComponent.Lookup("contacts.search", "query"));
    assertThat(searched).isEmpty(); // nothing is looked up until a person types
  }

  @Test
  void aLookupAsksTheCapabilityOfTheViewUnderItsGrantsAndNothingElse() throws Exception {
    ViewResolver resolver = resolverWith(COMPOSE, Map.of("mail.write", WRITE, "mail.draft", DRAFT));

    Response answer = resolver.lookup("v", "contacts.search", Map.of("query", "ann"));

    Response.Suggestions suggestions = (Response.Suggestions) answer;
    assertThat(suggestions.items()).hasSize(20); // at most
    assertThat(suggestions.items().get(0)).containsEntry("address", "anna0@example.org");
    assertThat(searched).containsExactly("ann");

    // the view cannot be used to ask anything else, or about views that do not exist
    assertThat(resolver.lookup("v", "mail.list", Map.of("folder", "INBOX")))
        .isEqualTo(new Response.Error("view 'v' has no lookup of 'mail.list'"));
    assertThat(resolver.lookup("nope", "contacts.search", Map.of()))
        .isEqualTo(new Response.Error("there is no view 'nope'"));
    assertThat(resolver.lookup("v", "contacts.search", Map.of("nope", "x")))
        .isInstanceOf(Response.Error.class);
    assertThat(reads).isEmpty();

    allow = false;
    assertThat(resolver.lookup("v", "contacts.search", Map.of("query", "x")))
        .isEqualTo(new Response.Error("layer 'mine' has no grant"));
    assertThat(searched).containsExactly("ann");
    assertThat(this.denied).anyMatch(d -> d.startsWith("view:v contacts.search"));
  }

  @Test
  void aSourceCanUseTheOnesBeforeItAndAnActionOpensAViewWithItsState() throws Exception {
    String view =
        """
        title = "Reply"
        [param.m]
        type = "string"
        default = "m1"
        [source.first]
        capability = "mail.get"
        args = { message = { cel = "view.m" } }
        [source.second]
        capability = "mail.get"
        args = { message = { cel = "'blocks:' + string(size(source.first.blocks))" } }

        [[component]]
        type = "button"
        label = "Reply"
        opens = { view = "v", params = { m = { cel = "view.m + '!'" } } }
        """;

    Response.ResolvedView r =
        (Response.ResolvedView) resolver(view, null).resolve("v", Map.of(), 1);

    assertThat(gets).containsExactly("m1", "blocks:3"); // in the order of the file
    assertThat(r.warnings()).isEmpty();
    ViewComponent.Action action = r.components().get(0).actions().get(0);
    assertThat(action.opens()).isEqualTo(new ViewComponent.Opens("v", Map.of("m", "m1!")));
    assertThat(action.command()).isEmpty();
  }

  @Test
  void rowsCarryWhatIsDraggedAndWhereItCanBeDroppedAndPartsCarryTheirKeys() throws Exception {
    String view =
        """
        title = "Mail"
        [param.q]
        type = "string"
        default = ""
        [source.messages]
        capability = "mail.list"
        args = { folder = "INBOX" }

        [[component]]
        type = "table"
        rows = { cel = "source.messages.items" }
        columns = [ { title = "From", value = { cel = "item.from" } } ]
        drag = { cel = "item.id" }

        [[component]]
        type = "list"
        items = { cel = "[{'name': 'Archive'}, {'name': 'Lists'}]" }
        title = { cel = "item.name" }
        drop = { command = "mail.file", param = "message", args = { folder = { cel = "item.name" } } }

        [[component]]
        type = "button"
        label = "Archive"
        command = "mail.file"
        args = { message = "x" }
        key = "a"
        width = 90

        [[component]]
        type = "input"
        param = "q"
        key = "/"
        """;

    Response.ResolvedView r =
        (Response.ResolvedView) resolver(view, COMMAND).resolve("v", Map.of(), 1);

    var messages = r.components().get(0);
    assertThat(messages.rows()).extracting(x -> x.drag()).containsExactly("m1", "m2");
    assertThat(messages.rows().get(0).drop()).isNull();
    var folders = r.components().get(1);
    assertThat(folders.rows()).extracting(x -> x.drag()).containsOnlyNulls();
    assertThat(folders.rows().get(1).drop())
        .isEqualTo(new ViewComponent.Drop("mail.file", "message", Map.of("folder", "Lists")));
    assertThat(r.components().get(2).key()).isEqualTo("a");
    assertThat(r.components().get(2).width()).isEqualTo(90);
    assertThat(r.components().get(3).key()).isEqualTo("/");
  }

  @Test
  void aRichTextWithAWidthKeepsItsText() throws Exception {
    String view =
        """
        title = "Reader"
        [param.message]
        type = "string"
        default = "m1"
        [source.current]
        capability = "mail.get"
        args = { message = { cel = "view.message" } }

        [[component]]
        type = "richtext"
        blocks = { cel = "source.current.blocks" }
        width = 400
        """;
    Response.ResolvedView r =
        (Response.ResolvedView) resolver(view, null).resolve("v", Map.of(), 1);
    assertThat(r.components().get(0).width()).isEqualTo(400);
    assertThat(r.components().get(0).rich().blocks()).isNotEmpty();
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
      key = "/"
      width = 40
      dictate = { capability = "speech.listen", args = { seconds = 6, profile = "en" } }
      """;

  @Test
  void anInputWithAMicrophoneSaysSoAndNothingListensUntilAPersonPressesTheButton()
      throws Exception {
    ViewResolver resolver = resolverWith(SEARCH, Map.of());

    Response.ResolvedView r = (Response.ResolvedView) resolver.resolve("v", Map.of(), 1);

    assertThat(r.components().get(0).dictate())
        .isEqualTo(new ViewComponent.Dictate("speech.listen"));
    assertThat(listened).isEmpty();
  }

  @Test
  void dictatingListensWithTheArgumentsOfTheViewUnderItsGrantsAndEveryUseIsAudited()
      throws Exception {
    ViewResolver resolver = resolverWith(SEARCH, Map.of());

    Response answer = resolver.dictate("v", "speech.listen");

    assertThat(answer).isEqualTo(new Response.Dictated("invoices from anna", true));
    assertThat(listened).containsExactly(Map.of("seconds", 6L, "profile", "en"));
    assertThat(allowedCalls).anyMatch(c -> c.startsWith("view:v speech.listen"));

    said = "  ";
    assertThat(resolver.dictate("v", "speech.listen"))
        .isEqualTo(new Response.Dictated("  ", false));

    // the view cannot be used to listen with anything else, or for views that do not exist
    assertThat(resolver.dictate("v", "mail.list"))
        .isEqualTo(new Response.Error("view 'v' has no microphone for 'mail.list'"));
    assertThat(resolver.dictate("nope", "speech.listen"))
        .isEqualTo(new Response.Error("there is no view 'nope'"));
    assertThat(listened).hasSize(2);

    allow = false;
    assertThat(resolver.dictate("v", "speech.listen"))
        .isEqualTo(new Response.Error("layer 'mine' has no grant"));
    assertThat(listened).hasSize(2);
    assertThat(this.denied).anyMatch(d -> d.startsWith("view:v speech.listen"));
  }
}
