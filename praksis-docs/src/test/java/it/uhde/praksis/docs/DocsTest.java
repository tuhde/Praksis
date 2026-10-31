// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class DocsTest {
  @Test
  void listsTheTopicsOfTheIndex() {
    assertThat(Docs.topics())
        .contains(new Docs.Topic("user/concepts", "Concepts"))
        .allSatisfy(t -> assertThat(Docs.read(t.name())).startsWith("# "));
  }

  @Test
  void findsATopicWithOrWithoutTheSuffix() {
    assertThat(Docs.find("user/rules")).isPresent();
    assertThat(Docs.find("user/rules.md")).isPresent();
    assertThat(Docs.find("nope")).isEmpty();
  }

  @Test
  void theDesignLogIsNotPartOfTheHelp() {
    assertThatThrownBy(() -> Docs.read("PLAN")).isInstanceOf(IllegalStateException.class);
  }
}
