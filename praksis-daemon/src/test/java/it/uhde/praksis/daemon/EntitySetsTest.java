// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.ruleset.ArgMatcher;
import it.uhde.praksis.ruleset.Grant;
import it.uhde.praksis.ruleset.Policy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EntitySetsTest {
  private static Policy policy() {
    return new Policy(
        List.of(
            new Grant(
                "docs",
                "mine",
                "entity.read",
                Map.of("type", new ArgMatcher.InSet("docs")),
                Optional.empty()),
            new Grant(
                "one",
                "mine",
                "entity.read",
                Map.of("type", new ArgMatcher.Equals("note")),
                Optional.empty())));
  }

  @Test
  void aGrantThatNamesASetCoversTypesThatTheSetGains() {
    Map<String, Set<String>> before = Map.of("docs", Set.of("note"));
    assertThat(EntitySets.growth(policy(), before, before)).isEmpty();
    assertThat(EntitySets.growth(policy(), before, Map.of("docs", Set.of("note", "memo", "tape"))))
        .containsExactly(
            "the set 'docs' gains the type 'memo': grant 'docs' (layer mine, entity.read) would cover it",
            "the set 'docs' gains the type 'tape': grant 'docs' (layer mine, entity.read) would cover it");
    // a set that is new and named by a grant is all gain; a set that shrinks or other sets are not
    assertThat(EntitySets.growth(policy(), Map.of(), Map.of("docs", Set.of("note")))).hasSize(1);
    assertThat(EntitySets.growth(policy(), before, Map.of("docs", Set.of()))).isEmpty();
    assertThat(
            EntitySets.growth(
                policy(), before, Map.of("docs", Set.of("note"), "other", Set.of("x"))))
        .isEmpty();
  }
}
