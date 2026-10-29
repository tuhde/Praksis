// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.ruleset.LayerStack.Source;
import it.uhde.praksis.ruleset.Permissions.Requirement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PermissionsTest {
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
                          "mail.attachment",
                          EffectClass.READ_ONLY,
                          true,
                          List.of(
                              new FieldDescriptor("message", ValueType.STRING, true, false),
                              new FieldDescriptor("name", ValueType.STRING, true, false)),
                          List.of(new FieldDescriptor("content", ValueType.BYTES, true, false))),
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

  private static String rule(String id, String folder) {
    return """
        id = "%s"
        effect = "local-write"
        needs = ["mail.move"]

        [trigger]
        kind = "event"
        type = "mail.received"

        [[action]]
        id = "move"
        capability = "mail.move"
        args = { message = { cel = "event.data.message" }, folder = %s }
        """
        .formatted(id, folder);
  }

  private static void write(Path root, String relative, String content) throws IOException {
    Files.createDirectories(root.resolve(relative).getParent());
    Files.writeString(root.resolve(relative), content);
  }

  private Permissions.Needs needs(Map<String, String> base, Map<String, String> top)
      throws Exception {
    Path lower = dir.resolve("base");
    Path upper = dir.resolve("top");
    write(
        lower,
        "ruleset.toml",
        "format_version = 1\nname = \"base\"\n[param.box]\ntype = \"string\"\ndefault = \"Lists\"\n");
    write(upper, "ruleset.toml", "format_version = 1\nname = \"top\"\n");
    for (var e : base.entrySet()) {
      write(lower, "rules/" + e.getKey() + ".toml", rule(e.getKey(), e.getValue()));
    }
    for (var e : top.entrySet()) {
      write(upper, "rules/" + e.getKey() + ".toml", rule(e.getKey(), e.getValue()));
    }
    var effective =
        LayerStack.resolve(List.of(new Source("base", lower), new Source("top", upper)), Map.of());
    return Permissions.of(effective, CATALOG);
  }

  @Test
  void literalsAndParametersAreKnownAndAnythingElseIsUnbounded() throws Exception {
    Permissions.Needs needs =
        needs(
            Map.of(
                "a",
                "\"Archive\"",
                "b",
                "{ cel = \"params.box\" }",
                "c",
                "{ cel = \"event.data.f\" }"),
            Map.of("d", "{ cel = \"'Spam'\" }"));
    assertThat(needs.requirements())
        .extracting(Requirement::describe)
        .containsExactlyInAnyOrder(
            "base: mail.move folder=\"Archive\"",
            "base: mail.move folder=\"Lists\"",
            "base: mail.move folder=*",
            "top: mail.move folder=\"Spam\"");
  }

  @Test
  void aGrantCoversOnlyWhatItMatchesForTheLayerThatOwnsTheRule() throws Exception {
    Permissions.Needs needs =
        needs(Map.of("a", "\"Archive\"", "c", "{ cel = \"event.data.f\" }"), Map.of());
    Policy policy =
        new Policy(
            List.of(
                new Grant(
                    "g",
                    "base",
                    "mail.move",
                    Map.of("folder", new ArgMatcher.OneOf(List.of("Archive", "Spam"))),
                    java.util.Optional.empty())));
    assertThat(Permissions.uncovered(needs.requirements(), policy))
        .extracting(Requirement::describe)
        .containsExactly("base: mail.move folder=*");
    assertThat(Permissions.uncovered(needs.requirements(), Policy.empty())).hasSize(2);
  }

  @Test
  void theDiffNamesWhatIsNewAndWhatIsGone() throws Exception {
    Permissions.Needs before = needs(Map.of("a", "\"Archive\""), Map.of());
    Path other = dir.resolve("other");
    write(other, "ruleset.toml", "format_version = 1\nname = \"x\"\n");
    write(other, "rules/a.toml", rule("a", "\"Spam\""));
    Permissions.Needs after =
        Permissions.of(LayerStack.resolve(List.of(new Source("base", other)), Map.of()), CATALOG);
    Permissions.Diff diff = Permissions.diff(before, after);
    assertThat(diff.added())
        .extracting(Requirement::describe)
        .containsExactly("base: mail.move folder=\"Spam\"");
    assertThat(diff.removed())
        .extracting(Requirement::describe)
        .containsExactly("base: mail.move folder=\"Archive\"");
    assertThat(Permissions.diff(before, before).isEmpty()).isTrue();
  }

  @Test
  void aDownloadOfAViewIsARequirementOfTheViewLikeASource() throws Exception {
    Path layer = dir.resolve("viewlayer");
    write(layer, "ruleset.toml", "format_version = 1\nname = \"v\"\n");
    write(
        layer,
        "views/v.toml",
        """
        title = "V"
        [param.message]
        type = "string"
        default = ""
        [[component]]
        type = "button"
        label = "Save"
        download = { capability = "mail.attachment", args = { message = { cel = "view.message" }, name = "a.pdf" }, filename = "a.pdf" }
        """);

    Permissions.Needs needs =
        Permissions.of(LayerStack.resolve(List.of(new Source("v", layer)), Map.of()), CATALOG);

    assertThat(needs.requirements())
        .extracting(Requirement::describe)
        .containsExactly("v: mail.attachment");
    assertThat(needs.rules().values().iterator().next()).containsExactly("view:v");
  }
}
