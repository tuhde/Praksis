// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.Event;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StoreTest {
  @TempDir Path dir;

  private static Event event(String id, Optional<String> key, Map<String, Object> data) {
    return new Event(
        id,
        "mail.received",
        "maildir",
        Instant.parse("2026-01-02T03:04:05.123456789Z"),
        "mailbox:work",
        key,
        Optional.empty(),
        0,
        data);
  }

  @Test
  void eventsKeepTheirTypesThroughTheDatabase() throws Exception {
    Map<String, Object> data =
        Map.of(
            "subject",
            "hi",
            "size",
            12L,
            "ratio",
            0.5,
            "seen",
            true,
            "when",
            Instant.parse("2026-01-01T00:00:00Z"),
            "raw",
            new byte[] {1, 2, 3},
            "headers",
            Map.of("List-Id", "x"),
            "tags",
            List.of("a", 2L));
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      assertThat(store.append(event("e1", Optional.of("k1"), data))).isTrue();
      Event back = store.pending().get(0);
      assertThat(back.time()).isEqualTo(Instant.parse("2026-01-02T03:04:05.123456789Z"));
      assertThat(back.key()).contains("k1");
      assertThat(back.data()).containsEntry("size", 12L).containsEntry("ratio", 0.5);
      assertThat(back.data().get("when")).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
      assertThat((byte[]) back.data().get("raw")).containsExactly(1, 2, 3);
      assertThat(back.data()).containsEntry("headers", Map.of("List-Id", "x"));
      assertThat(back.data()).containsEntry("tags", List.of("a", 2L));
    }
  }

  @Test
  void duplicateIdsAndKeysAreIgnored() throws Exception {
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      assertThat(store.append(event("e1", Optional.of("k"), Map.of()))).isTrue();
      assertThat(store.append(event("e1", Optional.empty(), Map.of()))).isFalse();
      assertThat(store.append(event("e2", Optional.of("k"), Map.of()))).isFalse();
      assertThat(store.append(event("e3", Optional.empty(), Map.of()))).isTrue();
      assertThat(store.append(event("e4", Optional.empty(), Map.of()))).isTrue();
      assertThat(store.pending()).extracting(Event::id).containsExactly("e1", "e3", "e4");
    }
  }

  @Test
  void doneEventsStayDoneAfterReopening() throws Exception {
    Path file = dir.resolve("praksis.db");
    try (Store store = Store.open(file)) {
      store.append(event("e1", Optional.of("k"), Map.of()));
      store.append(event("e2", Optional.empty(), Map.of()));
      store.markDone("e1");
    }
    try (Store store = Store.open(file)) {
      assertThat(store.pending()).extracting(Event::id).containsExactly("e2");
      assertThat(store.append(event("e9", Optional.of("k"), Map.of()))).isFalse();
    }
  }

  @Test
  void logKeepsOrderAndLimit() throws Exception {
    try (Store store = Store.open(dir.resolve("sub/dir/praksis.db"))) {
      for (int i = 0; i < 5; i++) {
        store.log(
            new LogEntry(
                Instant.EPOCH.plusSeconds(i),
                "e1",
                Optional.of("r"),
                i == 0 ? Optional.empty() : Optional.of("a"),
                LogEntry.Outcome.OK,
                "step " + i));
      }
      List<LogEntry> last = store.recentLog(2);
      assertThat(last).extracting(LogEntry::message).containsExactly("step 3", "step 4");
      assertThat(store.recentLog(10).get(0).actionId()).isEmpty();
    }
  }
}
