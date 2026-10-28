// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.daemon.Tiers.Tier;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.ApprovalRules;
import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.LayerStack;
import it.uhde.praksis.ruleset.ModuleCatalog;
import it.uhde.praksis.ruleset.Permissions;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.ruleset.Trust;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TiersTest {
  @TempDir Path dir;

  private static final String KEY = "SHA256:mine";

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
                  List.of(),
                  List.of())));

  private int counter;

  /** A ruleset of one layer: id, effect, capability and folder per rule. */
  private LayerStack.Effective ruleset(String... rules) throws Exception {
    Path layer = dir.resolve("l" + (++counter));
    Files.createDirectories(layer.resolve("rules"));
    Files.writeString(layer.resolve("ruleset.toml"), "format_version = 1\nname = \"t\"\n");
    for (int i = 0; i < rules.length; i += 4) {
      write(layer, rules[i], rules[i + 1], rules[i + 2], rules[i + 3]);
    }
    return LayerStack.resolve(List.of(new LayerStack.Source("mine", layer)), Map.of());
  }

  private static void write(Path layer, String id, String effect, String capability, String folder)
      throws IOException {
    Files.writeString(
        layer.resolve("rules/" + id + ".toml"),
        """
        id = "%s"
        effect = "%s"
        needs = ["%s"]

        [trigger]
        kind = "event"
        type = "mail.received"

        [[action]]
        id = "a"
        capability = "%s"
        args = { message = { cel = "event.data.message" }%s }
        """
            .formatted(
                id,
                effect,
                capability,
                capability,
                capability.equals("mail.move") ? ", folder = \"" + folder + "\"" : ""));
  }

  /** Every rule was last changed by a commit that is signed by the key, or not signed at all. */
  private static Provenance by(String signer) {
    return new Provenance(
        "mine",
        "rules/x.toml",
        Optional.of(
            new GitSource.Change(
                "a".repeat(40),
                "Test",
                "t@example.org",
                Instant.EPOCH,
                "s",
                "",
                signer == null ? "N" : "G",
                signer == null ? "" : signer)));
  }

  private static Policy policy(ApprovalRules rules, String... folders) {
    return new Policy(
        List.of(
            new Grant(
                "g",
                "mine",
                "mail.move",
                Map.of("folder", new ArgMatcher.OneOf(List.of((Object[]) folders))),
                Optional.empty())),
        List.of(),
        new Trust(List.of(KEY)),
        rules);
  }

  private Tiers.Verdict decide(
      LayerStack.Effective now,
      String nowSigner,
      LayerStack.Effective next,
      String nextSigner,
      Policy policy) {
    Permissions.Needs before = Permissions.of(now, CATALOG);
    Permissions.Needs after = Permissions.of(next, CATALOG);
    return Tiers.decide(
        now,
        next,
        o -> by(nowSigner),
        o -> by(nextSigner),
        after,
        Permissions.diff(before, after),
        policy);
  }

  @Test
  void aNewRuleWithinTheGrantsRunsInShadowModeFirst() throws Exception {
    var now = ruleset("a", "local-write", "mail.move", "Lists");
    var next =
        ruleset("a", "local-write", "mail.move", "Lists", "b", "local-write", "mail.move", "Lists");
    Tiers.Verdict v = decide(now, null, next, null, policy(ApprovalRules.strict(), "Lists"));
    assertThat(v.tier()).isEqualTo(Tier.SHADOW);
    assertThat(v.decisions())
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.rule()).isEqualTo("b");
              assertThat(d.change()).isEqualTo("new");
            });
    // the user may turn shadow mode off for such changes
    assertThat(
            decide(
                    now,
                    null,
                    next,
                    null,
                    policy(new ApprovalRules(true, true, true, false), "Lists"))
                .tier())
        .isEqualTo(Tier.AUTOMATIC);
  }

  @Test
  void anUnchangedRuleNeedsNothing() throws Exception {
    var now = ruleset("a", "local-write", "mail.move", "Lists");
    var same = ruleset("a", "local-write", "mail.move", "Lists");
    Tiers.Verdict v = decide(now, KEY, same, null, policy(ApprovalRules.strict(), "Lists"));
    assertThat(v.decisions()).isEmpty();
    assertThat(v.tier()).isEqualTo(Tier.AUTOMATIC);
  }

  @Test
  void accessNoGrantCoversNeedsApproval() throws Exception {
    var now = ruleset("a", "local-write", "mail.move", "Lists");
    var next = ruleset("a", "local-write", "mail.move", "Trash");
    Tiers.Verdict v = decide(now, null, next, null, policy(ApprovalRules.strict(), "Lists"));
    assertThat(v.tier()).isEqualTo(Tier.APPROVAL);
    assertThat(v.decisions().get(0).reasons().get(0))
        .contains("needs access that no grant covers")
        .contains("Trash");
    assertThat(
            decide(
                    now,
                    null,
                    next,
                    null,
                    policy(new ApprovalRules(false, true, true, true), "Lists"))
                .tier())
        .isEqualTo(Tier.SHADOW);
  }

  @Test
  void aDestructiveRuleNeedsApprovalWhateverTheGrants() throws Exception {
    var now = ruleset("a", "local-write", "mail.move", "Lists");
    var next =
        ruleset("a", "local-write", "mail.move", "Lists", "b", "destructive", "mail.delete", "");
    Tiers.Verdict v = decide(now, null, next, null, policy(ApprovalRules.strict(), "Lists"));
    assertThat(v.tier()).isEqualTo(Tier.APPROVAL);
    assertThat(v.decisions().get(0).reasons()).contains("effect class 'destructive'");
  }

  @Test
  void onlyTheUsersOwnSignatureMakesARuleTheUsers() throws Exception {
    var now = ruleset("a", "local-write", "mail.move", "Lists");
    var changed = ruleset("a", "local-write", "mail.move", "Lists/2");
    Policy wide = policy(ApprovalRules.strict(), "Lists", "Lists/2");
    // the rule was the user's; an unsigned change to it needs approval
    var unsigned = decide(now, KEY, changed, null, wide).decisions().get(0);
    assertThat(unsigned.tier()).isEqualTo(Tier.APPROVAL);
    assertThat(unsigned.reasons()).anyMatch(r -> r.contains("changes a rule you wrote"));
    // a signature of someone else's key is no better
    assertThat(decide(now, KEY, changed, "SHA256:other", wide).decisions().get(0).tier())
        .isEqualTo(Tier.APPROVAL);
    // signed by the user: only the usual tier
    assertThat(decide(now, KEY, changed, KEY, wide).decisions().get(0).tier())
        .isEqualTo(Tier.SHADOW);
    // a rule nobody vouched for may be changed by anyone
    assertThat(decide(now, null, changed, null, wide).decisions().get(0).tier())
        .isEqualTo(Tier.SHADOW);
    // the user can give this protection up
    assertThat(
            decide(
                    now,
                    KEY,
                    changed,
                    null,
                    policy(new ApprovalRules(true, true, false, true), "Lists", "Lists/2"))
                .tier())
        .isEqualTo(Tier.SHADOW);
  }

  @Test
  void removingARuleTheUserWroteNeedsApproval() throws Exception {
    var now =
        ruleset("a", "local-write", "mail.move", "Lists", "b", "local-write", "mail.move", "Lists");
    var next = ruleset("b", "local-write", "mail.move", "Lists");
    Policy strict = policy(ApprovalRules.strict(), "Lists");
    Tiers.Verdict v = decide(now, KEY, next, KEY, strict);
    assertThat(v.tier()).isEqualTo(Tier.APPROVAL);
    assertThat(v.decisions().get(0).change()).isEqualTo("removed");
    assertThat(decide(now, null, next, null, strict).tier()).isEqualTo(Tier.AUTOMATIC);
  }
}
