// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.Policy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GrantPolicyTest {
  private static final CapabilityDescriptor MOVE =
      new CapabilityDescriptor(
          "mail.move",
          EffectClass.LOCAL_WRITE,
          true,
          List.of(
              new FieldDescriptor("message", ValueType.STRING, true, false),
              new FieldDescriptor("folder", ValueType.STRING, true, true)),
          List.of());

  private final GrantPolicy policy =
      new GrantPolicy(
          new Policy(
              List.of(
                  new Grant(
                      "sort",
                      "mail",
                      "mail.move",
                      Map.of("folder", new ArgMatcher.OneOf(List.of("Spam", "Lists"))),
                      Optional.empty()))),
          Map.of("mail.10-spam", "mail", "05.vip", "mine"));

  @Test
  void allowsWhatTheLayersGrantCovers() {
    assertThat(
            policy.check("mail.10-spam", MOVE, Map.of("message", "m", "folder", "Spam")).allowed())
        .isTrue();
  }

  @Test
  void deniesOtherArgumentsAndNamesTheScopableOnesOnly() {
    var decision =
        policy.check("mail.10-spam", MOVE, Map.of("message", "secret-id", "folder", "Trash"));
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason())
        .isEqualTo("layer 'mail' has no grant for mail.move (folder=Trash)");
  }

  @Test
  void aRuleUsesOnlyTheGrantsOfItsOwnLayer() {
    var decision = policy.check("05.vip", MOVE, Map.of("message", "m", "folder", "Spam"));
    assertThat(decision.allowed()).isFalse();
    assertThat(decision.reason()).contains("layer 'mine'");
  }

  @Test
  void aRuleWithoutALayerIsDenied() {
    assertThat(policy.check("unknown", MOVE, Map.of("folder", "Spam")).reason())
        .contains("belongs to no layer");
  }
}
