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

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void auditLinesKeepTheirArgumentsAndOrder() throws Exception {
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      store.audit(
          AuditEntry.call(
              T0,
              "mail.10-spam",
              "mail.move",
              Map.of("folder", "Spam", "n", 3L),
              true,
              Optional.empty()));
      store.audit(
          AuditEntry.call(
              T0.plusSeconds(1),
              "mail.10-spam",
              "mail.move",
              Map.of("folder", "Trash"),
              false,
              Optional.of("no grant")));
      store.audit(
          new AuditEntry(
              T0.plusSeconds(2),
              AuditEntry.Kind.POLICY,
              Optional.empty(),
              "policy.toml",
              true,
              "{\"sha256\":\"x\"}",
              Optional.empty()));

      List<AuditEntry> all = store.recentAudit(10);
      assertThat(all)
          .extracting(AuditEntry::subject)
          .containsExactly("mail.move", "mail.move", "policy.toml");
      assertThat(all.get(0).detail()).contains("\"folder\":\"Spam\"").contains("\"n\":3");
      assertThat(all.get(1).allowed()).isFalse();
      assertThat(all.get(1).error()).contains("no grant");
      assertThat(store.recentAudit(1))
          .extracting(AuditEntry::kind)
          .containsExactly(AuditEntry.Kind.POLICY);
      assertThat(store.lastAudit(AuditEntry.Kind.CALL).orElseThrow().allowed()).isFalse();
      assertThat(store.lastAudit(AuditEntry.Kind.GRANT)).isEmpty();
    }
  }

  @Test
  void missedEventsAreRememberedOncePerRuleAndTrimmed() throws Exception {
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      Instant cutoff = T0.minusSeconds(1);
      store.addMissed(T0, "a", "e1", cutoff, 3);
      store.addMissed(T0.plusSeconds(1), "a", "e2", cutoff, 3);
      store.addMissed(T0.plusSeconds(2), "a", "e2", cutoff, 3); // same pair again
      store.addMissed(T0.plusSeconds(2), "b", "e2", cutoff, 3);
      assertThat(store.missed("a")).containsExactly("e1", "e2");
      assertThat(store.missedCounts()).containsExactly(Map.entry("a", 2), Map.entry("b", 1));

      // the cap keeps the newest
      store.addMissed(T0.plusSeconds(3), "a", "e3", cutoff, 3);
      store.addMissed(T0.plusSeconds(4), "a", "e4", cutoff, 3);
      assertThat(store.missed("a")).containsExactly("e2", "e3", "e4");

      // the age limit drops what is older than the cutoff, for that rule only
      store.addMissed(T0.plusSeconds(5), "a", "e5", T0.plusSeconds(3), 10);
      assertThat(store.missed("a")).containsExactly("e3", "e4", "e5");
      assertThat(store.missed("b")).containsExactly("e2");

      store.clearMissed("a");
      assertThat(store.missed("a")).isEmpty();
      assertThat(store.missedCounts()).containsExactly(Map.entry("b", 1));
    }
  }

  @Test
  void aStoredEventCanBeFoundById() throws Exception {
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      store.append(event("e1", Optional.empty(), Map.of("subject", "hi")));
      store.markDone("e1");
      assertThat(store.event("e1").orElseThrow().data()).containsEntry("subject", "hi");
      assertThat(store.event("nope")).isEmpty();
    }
  }

  @Test
  void aDatabaseOfTheFirstVersionGainsTheNewTables() throws Exception {
    Path file = dir.resolve("praksis.db");
    try (Store store = Store.open(file)) {
      store.append(event("e1", Optional.empty(), Map.of()));
    }
    try (var c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + file);
        var s = c.createStatement()) {
      s.execute("DROP TABLE audit");
      s.execute("DROP TABLE missed");
      s.execute("DROP TABLE approval");
      s.execute("PRAGMA user_version = 1");
    }
    try (Store store = Store.open(file)) {
      assertThat(store.pending()).hasSize(1);
      assertThat(store.recentAudit(5)).isEmpty();
      assertThat(store.missedCounts()).isEmpty();
    }
  }

  @Test
  void approvalRequestsAreOpenedOncePerRuleAndAnswered() throws Exception {
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      Approval first =
          store.openApproval(
              T0, "mail.10-spam", "mail", "mail.move", Map.of("folder", "Trash"), "e1");
      assertThat(first.state()).isEqualTo(Approval.State.PENDING);
      assertThat(first.args()).containsEntry("folder", "Trash");

      // the rule already has an open request
      Approval again =
          store.openApproval(
              T0.plusSeconds(1),
              "mail.10-spam",
              "mail",
              "mail.move",
              Map.of("folder", "Other"),
              "e2");
      assertThat(again.id()).isEqualTo(first.id());
      Approval other =
          store.openApproval(
              T0, "mail.20-lists", "mail", "mail.move", Map.of("folder", "Lists"), "e3");
      assertThat(other.id()).isNotEqualTo(first.id());

      assertThat(store.approvals(java.util.Set.of(Approval.State.PENDING))).hasSize(2);
      store.answer(first.id(), Approval.State.DENIED, T0.plusSeconds(5));
      Approval denied = store.approval(first.id()).orElseThrow();
      assertThat(denied.state()).isEqualTo(Approval.State.DENIED);
      assertThat(denied.state().disables()).isTrue();
      assertThat(denied.answered()).contains(T0.plusSeconds(5));
      // a refused request still counts as open for the rule
      assertThat(store.openApproval(T0, "mail.10-spam", "mail", "mail.move", Map.of(), "e4").id())
          .isEqualTo(first.id());

      store.answer(first.id(), Approval.State.ALLOWED_ALWAYS, T0.plusSeconds(6));
      assertThat(store.approval(first.id()).orElseThrow().state().disables()).isFalse();
      // the rule can be denied again later and gets a new request
      assertThat(store.openApproval(T0, "mail.10-spam", "mail", "mail.move", Map.of(), "e5").id())
          .isGreaterThan(other.id());
      assertThat(store.approval(999)).isEmpty();
    }
  }

  @Test
  void oneMissedEventCanBeRemoved() throws Exception {
    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      store.addMissed(T0, "a", "e1", T0.minusSeconds(1), 10);
      store.addMissed(T0, "a", "e2", T0.minusSeconds(1), 10);
      store.removeMissed("a", "e1");
      assertThat(store.missed("a")).containsExactly("e2");
    }
  }
}
