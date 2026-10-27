// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class EventTest {
  private static Event event(int depth, Map<String, Object> data) {
    return new Event(
        "e1",
        "mail.received",
        "maildir",
        Instant.EPOCH,
        "mail:work",
        Optional.empty(),
        Optional.empty(),
        depth,
        data);
  }

  @Test
  void dataIsCopied() {
    Map<String, Object> data = new HashMap<>();
    data.put("subject", "hi");
    Event e = event(0, data);
    data.put("subject", "changed");
    assertThat(e.data()).containsEntry("subject", "hi");
    assertThatThrownBy(() -> e.data().put("x", "y"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void negativeDepthIsRejected() {
    assertThatThrownBy(() -> event(-1, Map.of())).isInstanceOf(IllegalArgumentException.class);
  }
}
