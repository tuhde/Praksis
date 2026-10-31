// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.LogEntry;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoryTest {
  @TempDir Path dir;

  private Store store;
  private History history;
  private final Instant now = Instant.now();

  @BeforeEach
  void setUp() throws Exception {
    store = Store.open(dir.resolve("praksis.db"));
    history = new History(store, null);
  }

  @AfterEach
  void tearDown() throws Exception {
    store.close();
  }

  private Event event(String id, String type, Instant time, Map<String, Object> data) {
    return new Event(
        id, type, "test", time, "t", Optional.of("key-" + id), Optional.empty(), 0, data);
  }

  /** A mail that the rule 10-spam moved, and one that nothing handled. */
  private void history() throws Exception {
    Instant t = now.minus(2, ChronoUnit.HOURS);
    store.append(
        event(
            "e1",
            "mail.received",
            t,
            Map.of("from", "Anna <anna@example.org>", "subject", "Lunch?", "size", 1200)));
    store.append(
        event(
            "e2",
            "mail.received",
            t.plusSeconds(60),
            Map.of("from", "Bob <bob@example.org>", "subject", "Minutes")));
    store.append(
        event(
            "old",
            "mail.received",
            now.minus(30, ChronoUnit.DAYS),
            Map.of("from", "anna@example.org", "subject", "Ancient")));
    store.append(event("c1", "command.mail.trash", t.plusSeconds(120), Map.of("message", "m9")));
    store.log(
        new LogEntry(
            t.plusMillis(100),
            "e1",
            Optional.of("mail.10-spam"),
            Optional.of("move"),
            LogEntry.Outcome.OK,
            "mail.move"));
    store.log(
        new LogEntry(
            t.plusMillis(200),
            "e1",
            Optional.of("mail.10-spam"),
            Optional.empty(),
            LogEntry.Outcome.OK,
            "stop"));
    store.markDone("e1");
    store.markDone("e2");
    store.markDone("c1");
    store.markDone("old");
    store.audit(
        AuditEntry.call(
            t.plusMillis(150),
            "mail.10-spam",
            "mail.move",
            Map.of("message", "m1", "folder", "Spam"),
            true,
            Optional.empty()));
    // another rule's call at another time must not be taken for this event's
    store.audit(
        AuditEntry.call(
            t.plusSeconds(500),
            "mail.10-spam",
            "mail.move",
            Map.of("message", "m5", "folder", "Spam"),
            true,
            Optional.empty()));
    store.audit(
        AuditEntry.call(
            t.plusMillis(160),
            "mail.99-other",
            "mail.move",
            Map.of("message", "mX"),
            true,
            Optional.empty()));
    store.audit(
        AuditEntry.call(
            t.plusSeconds(61),
            "mail.20-lists",
            "mail.move",
            Map.of("folder", "Lists"),
            false,
            Optional.of("layer 'mine' has no grant")));
  }

  @Test
  @SuppressWarnings("unchecked")
  void eventsAreFoundByATextAndATypeAndSayWhatTheRulesDidWithThem() throws Exception {
    history();

    Map<String, Object> found = history.find(Map.of("text", "ANNA"));
    List<Map<String, Object>> items = (List<Map<String, Object>>) found.get("items");
    assertThat(items).hasSize(1); // the one of 30 days ago is too old for the default week
    assertThat(items.get(0)).containsEntry("event", "e1").containsEntry("type", "mail.received");
    assertThat((Map<String, Object>) items.get(0).get("data"))
        .containsEntry("subject", "Lunch?")
        .containsEntry("size", 1200L);
    assertThat(items.get(0).get("what_happened").toString())
        .contains("mail.10-spam/move OK: mail.move")
        .contains("mail.10-spam OK: stop");

    assertThat(
            ((List<?>) history.find(Map.of("text", "anna", "since_hours", 24 * 60)).get("items")))
        .hasSize(2);
    assertThat(((List<?>) history.find(Map.of("type", "command.mail.trash")).get("items")))
        .hasSize(1);
    assertThat(((List<?>) history.find(Map.of("text", "nobody")).get("items"))).isEmpty();
    // an event that is still being handled is not history yet
    store.append(
        event(
            "busy",
            "command.agent.ask",
            now,
            Map.of("question", "why was the mail from Anna moved?")));
    assertThat(((List<?>) history.find(Map.of("text", "anna")).get("items")))
        .extracting(i -> ((Map<String, Object>) i).get("event"))
        .containsExactly("e1");
    List<Map<String, Object>> newestFirst =
        (List<Map<String, Object>>)
            history.find(Map.of("type", "mail.received", "limit", 1)).get("items");
    assertThat(newestFirst).extracting(m -> m.get("event")).containsExactly("e2");
    assertThat(newestFirst.get(0).get("what_happened")).isEqualTo("no rule handled it");
    assertThat(found.get("report").toString())
        .startsWith("1 event(s) found")
        .contains("- event e1 mail.received at")
        .contains("from=Anna <anna@example.org>")
        .contains("=> mail.10-spam/move OK: mail.move");
    assertThatThrownBy(() -> history.find(Map.of("limit", 0))).hasMessageContaining("from 1 to 50");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aTraceHasTheStepsTheCallsOfThoseRulesInThatTimeAndNothingElse() throws Exception {
    history();

    Map<String, Object> trace = history.trace(Map.of("event", "e1"));

    assertThat((Map<String, Object>) trace.get("event"))
        .containsEntry("id", "e1")
        .containsEntry("source", "test");
    assertThat((List<Map<String, Object>>) trace.get("steps"))
        .extracting(s -> s.get("rule") + "/" + s.get("step") + " " + s.get("outcome"))
        .containsExactly("mail.10-spam/move OK", "mail.10-spam/ OK");
    List<Map<String, Object>> calls = (List<Map<String, Object>>) trace.get("calls");
    assertThat(calls).hasSize(1);
    assertThat(calls.get(0))
        .containsEntry("rule", "mail.10-spam")
        .containsEntry("capability", "mail.move")
        .containsEntry("allowed", true);
    assertThat(calls.get(0).get("arguments").toString()).contains("Spam").contains("m1");
    assertThat((List<Map<String, Object>>) trace.get("rules"))
        .extracting(r -> r.get("rule"))
        .containsExactly("mail.10-spam");
    assertThat(trace.get("report").toString())
        .contains("Event e1 mail.received from test")
        .contains("subject=Lunch?")
        .contains("- mail.10-spam/move OK: mail.move")
        .contains("- mail.10-spam called mail.move with")
        .contains("(allowed)")
        .contains("- mail.10-spam of layer");
    assertThatThrownBy(() -> history.trace(Map.of("event", "nope")))
        .hasMessageContaining("there is no event 'nope'");
    assertThatThrownBy(() -> history.trace(Map.of())).hasMessageContaining("'event' is required");
  }

  @Test
  @SuppressWarnings("unchecked")
  void aSummaryCountsWhatHappenedInTheLastHours() throws Exception {
    history();

    Map<String, Object> summary = history.summary(Map.of());

    assertThat((Map<String, Object>) summary.get("events"))
        .containsEntry("mail.received", 2L) // the old one is outside the week
        .containsEntry("command.mail.trash", 1L);
    assertThat((List<Map<String, Object>>) summary.get("rules"))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r)
                    .containsEntry("rule", "mail.10-spam")
                    .containsEntry("ok", 2L)
                    .containsEntry("failed", 0L));
    assertThat((Map<String, Object>) summary.get("calls")).containsEntry("mail.move", 3L);
    assertThat((List<Map<String, Object>>) summary.get("refused"))
        .singleElement()
        .satisfies(
            r ->
                assertThat(r)
                    .containsEntry("rule", "mail.20-lists")
                    .containsEntry("reason", "layer 'mine' has no grant"));
    assertThat(summary.get("report").toString())
        .contains("Events: command.mail.trash 1, mail.received 2")
        .contains("- mail.10-spam: ok 2, failed 0")
        .contains("Capability calls: mail.move 3")
        .contains("- mail.20-lists asked for mail.move: layer 'mine' has no grant");
    // a short window sees none of it
    assertThat((Map<String, Object>) history.summary(Map.of("since_hours", 1)).get("events"))
        .isEmpty();
  }
}
