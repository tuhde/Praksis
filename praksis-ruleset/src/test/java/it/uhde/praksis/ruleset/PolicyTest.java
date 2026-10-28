// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PolicyTest {
  @TempDir Path dir;

  private Policy load(String toml) throws Exception {
    Path file = dir.resolve("policy.toml");
    Files.writeString(file, toml);
    return Policy.load(file);
  }

  private static ModuleCatalog catalog() {
    CapabilityDescriptor move =
        new CapabilityDescriptor(
            "mail.move",
            EffectClass.LOCAL_WRITE,
            true,
            List.of(
                new FieldDescriptor("message", ValueType.STRING, true, false),
                new FieldDescriptor("folder", ValueType.STRING, true, true),
                new FieldDescriptor("limit", ValueType.INT, false, true)),
            List.of());
    return new ModuleCatalog(
        List.of(
            new ModuleDescriptor(
                "mail", "1", List.of(), List.of(move), List.of(), List.of("mail.inbox"))));
  }

  private static final String SORT =
      """
      format_version = 1

      [[grant]]
      id = "mail-sort"
      layer = "mail"
      capability = "mail.move"
      note = "sort incoming mail"
      [grant.args]
      folder = { one_of = ["Spam", "Lists", "Bulk"] }

      [[grant]]
      id = "archive"
      layer = "mine"
      capability = "mail.move"
      [grant.args]
      folder = { prefix = "Archive/" }
      """;

  @Test
  void aMissingFileAllowsNothing() throws Exception {
    Policy policy = Policy.load(dir.resolve("none.toml"));
    assertThat(policy.grants()).isEmpty();
    assertThat(policy.find("mail", "mail.move", Map.of("folder", "Spam"))).isEmpty();
  }

  @Test
  void aGrantCoversOnlyItsLayerCapabilityAndArguments() throws Exception {
    Policy policy = load(SORT);
    assertThat(policy.grants()).extracting(Grant::id).containsExactly("mail-sort", "archive");
    assertThat(policy.grants().get(0).note()).contains("sort incoming mail");

    assertThat(policy.find("mail", "mail.move", Map.of("folder", "Spam", "message", "m")))
        .map(Grant::id)
        .contains("mail-sort");
    assertThat(policy.find("mail", "mail.move", Map.of("folder", "Trash"))).isEmpty();
    // the private layer does not inherit the upstream layer's grant, and the other way round
    assertThat(policy.find("mine", "mail.move", Map.of("folder", "Spam"))).isEmpty();
    assertThat(policy.find("mine", "mail.move", Map.of("folder", "Archive/2026")))
        .map(Grant::id)
        .contains("archive");
    assertThat(policy.find("mail", "mail.move", Map.of("folder", "Archive/2026"))).isEmpty();
    assertThat(policy.find("mail", "mail.delete", Map.of("folder", "Spam"))).isEmpty();
    // a constrained argument that is missing is not covered
    assertThat(policy.find("mail", "mail.move", Map.of("message", "m"))).isEmpty();
  }

  @Test
  void matchersCompareNumbersAndStringsExactly() {
    assertThat(new ArgMatcher.Equals(5L).matches(5)).isTrue();
    assertThat(new ArgMatcher.Equals(5L).matches("5")).isFalse();
    assertThat(new ArgMatcher.Equals("a").matches("A")).isFalse();
    assertThat(new ArgMatcher.OneOf(List.of("a", 2L)).matches(2)).isTrue();
    assertThat(new ArgMatcher.Prefix("Archive/").matches("Archive")).isFalse();
    assertThat(new ArgMatcher.Prefix("Archive/").matches(3L)).isFalse();
    assertThat(new ArgMatcher.OneOf(List.of("a", 2L)).describe()).isEqualTo("one_of = [\"a\", 2]");
  }

  @Test
  void structuralMistakesAreAllReported() {
    assertThatThrownBy(
            () ->
                load(
                    """
                    format_version = 1
                    colour = "red"

                    [[grant]]
                    id = "A b"
                    layer = "mail"

                    [[grant]]
                    id = "twice"
                    layer = "mail"
                    capability = "mail.move"
                    [grant.args]
                    folder = { prefix = "" }
                    other = { equals = "x", one_of = ["y"] }
                    third = { regex = ".*" }

                    [[grant]]
                    id = "twice"
                    layer = "mail"
                    capability = "mail.move"
                    """))
        .isInstanceOf(RulesetException.class)
        .hasMessageContaining("unknown key 'colour'")
        .hasMessageContaining("'id' must be lower case")
        .hasMessageContaining("missing 'capability'")
        .hasMessageContaining("'prefix' must be a non-empty string")
        .hasMessageContaining("exactly one of")
        .hasMessageContaining("unknown key 'regex'")
        .hasMessageContaining("grant id 'twice' is used twice");
  }

  @Test
  void aMissingOrWrongFormatVersionIsAnError() {
    assertThatThrownBy(() -> load("[[grant]]\nid = \"a\"\nlayer = \"l\"\ncapability = \"c\"\n"))
        .hasMessageContaining("missing 'format_version'");
    assertThatThrownBy(() -> load("format_version = 2\n"))
        .hasMessageContaining("unsupported 'format_version'");
  }

  @Test
  void checkAgainstTheModulesAndLayers() throws Exception {
    assertThat(load(SORT).check(catalog(), Set.of("mail", "mine"))).isEmpty();

    Policy bad =
        load(
            """
            format_version = 1

            [[grant]]
            id = "a"
            layer = "nowhere"
            capability = "mail.move"

            [[grant]]
            id = "b"
            layer = "mail"
            capability = "mail.copy"

            [[grant]]
            id = "c"
            layer = "mail"
            capability = "mail.move"
            [grant.args]
            message = { equals = "m" }
            folder = { prefix = "A" }
            limit = { prefix = "1" }
            colour = { equals = "red" }
            """);
    assertThat(bad.check(catalog(), Set.of("mail")))
        .extracting(Problem::toString)
        .containsExactlyInAnyOrder(
            "policy.toml: grant 'a': no layer named 'nowhere'",
            "policy.toml: grant 'b': no capability named 'mail.copy'",
            "policy.toml: grant 'c': parameter 'message' cannot be restricted by a grant",
            "policy.toml: grant 'c': 'prefix' only works on strings: 'limit'",
            "policy.toml: grant 'c': mail.move has no parameter 'colour'");
  }

  @Test
  void credentialBindingsAreReadAndCheckedAgainstTheModules() throws Exception {
    Policy policy =
        load(
            """
            format_version = 1

            [credential."imap:work"]
            command = ["pass", "show", "mail/work"]
            capabilities = ["mail.inbox", "mail.move"]
            note = "the work mailbox"
            """);
    CredentialBinding b = policy.credential("imap:work").orElseThrow();
    assertThat(b.command()).containsExactly("pass", "show", "mail/work");
    assertThat(b.components()).containsExactly("mail.inbox", "mail.move");
    assertThat(b.note()).contains("the work mailbox");
    assertThat(policy.credential("nope")).isEmpty();
    assertThat(policy.grants()).isEmpty();
    assertThat(policy.check(catalog(), Set.of())).isEmpty();

    Policy bad =
        load(
            """
            format_version = 1

            [credential.other]
            command = ["x"]
            capabilities = ["mail.copy", "mail.inbox"]
            """);
    assertThat(bad.check(catalog(), Set.of()))
        .extracting(Problem::toString)
        .containsExactly(
            "policy.toml: credential 'other': no capability or event source named 'mail.copy'");
  }

  @Test
  void credentialMistakesAreAllReported() {
    assertThatThrownBy(
            () ->
                load(
                    """
                    format_version = 1

                    [credential."Bad Name"]
                    command = ["x"]
                    capabilities = ["a"]

                    [credential.no-command]
                    capabilities = ["a"]

                    [credential.no-capabilities]
                    command = ["x"]

                    [credential.blank]
                    command = ["x", " "]
                    capabilities = ["a"]
                    colour = "red"
                    """))
        .isInstanceOf(RulesetException.class)
        .hasMessageContaining("the name must be lower case")
        .hasMessageContaining("'command' must be a list with the command")
        .hasMessageContaining("'capabilities' must list the capabilities")
        .hasMessageContaining("unknown key 'colour'");
  }

  @Test
  void trustAndApprovalRulesAreReadAndStrictByDefault() throws Exception {
    Path file = dir.resolve("policy.toml");
    Files.writeString(file, "format_version = 1\n");
    Policy plain = Policy.load(file);
    assertThat(plain.trust().humanKeys()).isEmpty();
    assertThat(plain.approval()).isEqualTo(ApprovalRules.strict());

    Files.writeString(
        file,
        """
        format_version = 1
        [trust]
        human_keys = ["D2C152E2F6E3A4A6", "SHA256:abcDEF"]
        [approval]
        shadow_first = false
        """);
    Policy set = Policy.load(file);
    assertThat(set.trust().humanKeys()).containsExactly("D2C152E2F6E3A4A6", "SHA256:abcDEF");
    assertThat(set.approval().shadowFirst()).isFalse();
    assertThat(set.approval().widening()).isTrue();

    Files.writeString(file, "format_version = 1\n[approval]\nnope = true\n");
    assertThatThrownBy(() -> Policy.load(file)).isInstanceOf(RulesetException.class);
  }

  @Test
  void aSignerIsTheUsersIfItsKeyIsListed() {
    Trust trust = new Trust(List.of("D2C152E2F6E3A4A6", "SHA256:AbC", "short"));
    assertThat(trust.isHuman("D2C152E2F6E3A4A6")).isTrue();
    assertThat(trust.isHuman("00000000000000000000D2C152E2F6E3A4A6"))
        .isTrue(); // fingerprint ends in the key id
    assertThat(trust.isHuman("SHA256:AbC")).isTrue();
    assertThat(trust.isHuman("sha256:abc")).isTrue();
    assertThat(trust.isHuman("xxxSHA256:AbC")).isFalse();
    assertThat(trust.isHuman("xxshort")).isFalse(); // too short to be matched by its end
    assertThat(trust.isHuman("")).isFalse();
    assertThat(Trust.none().isHuman("D2C152E2F6E3A4A6")).isFalse();
  }
}
