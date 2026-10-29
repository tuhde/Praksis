// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.mail.html;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ThreadsTest {
  private static Map<String, Object> mail(
      String subject, String id, String replyTo, String refs, int minute) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("subject", subject);
    m.put("message_id", id);
    m.put("in_reply_to", replyTo);
    m.put("references", refs);
    m.put("time", Instant.parse("2026-01-01T10:00:00Z").plusSeconds(60L * minute));
    return m;
  }

  private static List<String> subjects(List<Map<String, Object>> items) {
    return items.stream().map(m -> (String) m.get("subject")).toList();
  }

  @Test
  void aConversationIsKeptTogetherOldestFirstAndOrderedByItsNewestMail() {
    List<Map<String, Object>> newestFirst =
        List.of(
            mail("other", "<z@x>", "", "", 50),
            mail("Re: Re: lunch", "<c@x>", "<b@x>", "<a@x> <b@x>", 40),
            mail("single", "<s@x>", "", "", 30),
            mail("Re: lunch", "<b@x>", "<a@x>", "<a@x>", 20),
            mail("lunch", "<a@x>", "", "", 10));

    List<Map<String, Object>> out = Threads.arrange(newestFirst);

    // the lunch conversation has the newest mail after "other", so it comes second; its mails
    // follow
    // each other, oldest first
    assertThat(subjects(out))
        .containsExactly("other", "lunch", "Re: lunch", "Re: Re: lunch", "single");
    assertThat(out.get(1))
        .containsEntry("depth", 0L)
        .containsEntry("thread_size", 3L)
        .containsEntry("thread", "<a@x>");
    assertThat(out.get(2)).containsEntry("depth", 1L).containsEntry("thread", "<a@x>");
    assertThat(out.get(3)).containsEntry("depth", 2L);
    assertThat(out.get(0)).containsEntry("depth", 0L).containsEntry("thread_size", 1L);
    assertThat(out.get(0).keySet()).doesNotContain("in_reply_to", "references");
  }

  @Test
  void aConversationWhoseStartIsNotInTheListStartsAtTheOldestMailThatIs() {
    List<Map<String, Object>> window =
        List.of(
            mail("Re: Re: lunch", "<c@x>", "<b@x>", "<a@x> <b@x>", 40),
            mail("Re: lunch", "<b@x>", "<a@x>", "<a@x>", 20));

    List<Map<String, Object>> out = Threads.arrange(window);

    assertThat(subjects(out)).containsExactly("Re: lunch", "Re: Re: lunch");
    assertThat(out.get(0)).containsEntry("depth", 0L).containsEntry("thread", "<a@x>");
    assertThat(out.get(1)).containsEntry("depth", 1L).containsEntry("thread", "<a@x>");
  }

  @Test
  void mailsWithOnlyReferencesAndMailsOfTheSameRootBelongTogether() {
    List<Map<String, Object>> window =
        List.of(
            mail("second", "<2@x>", "", "<root@x> <1@x>", 30),
            mail("first", "<1@x>", "", "<root@x>", 20));
    List<Map<String, Object>> out = Threads.arrange(window);
    assertThat(subjects(out)).containsExactly("first", "second");
    assertThat(out).extracting(m -> m.get("thread")).containsOnly("<root@x>");
    assertThat(out.get(1)).containsEntry("depth", 1L); // its parent is the last of References
  }

  @Test
  void badInputIsNoReasonToFail() {
    // a loop of answers, a mail that answers itself, no ids, and ids that are not ids
    List<Map<String, Object>> weird =
        List.of(
            mail("a", "<a@x>", "<b@x>", "", 10),
            mail("b", "<b@x>", "<a@x>", "", 20),
            mail("self", "<s@x>", "<s@x>", "<s@x>", 30),
            mail("no id", "", "", "", 40),
            mail("no id either", "", "", "", 50),
            mail("junk", "not an id", "also not", "x y z", 60));
    List<Map<String, Object>> out = Threads.arrange(weird);
    assertThat(out).hasSize(6);
    assertThat(out).allSatisfy(m -> assertThat((Long) m.get("depth")).isBetween(0L, 3L));
    assertThat(Threads.arrange(List.of())).isEmpty();
  }

  @Test
  void aLongChainIsFlattenedAtThreeLevels() {
    List<Map<String, Object>> chain = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      chain.add(mail("m" + i, "<" + i + "@x>", i == 0 ? "" : "<" + (i - 1) + "@x>", "", i));
    }
    List<Map<String, Object>> out = Threads.arrange(chain);
    assertThat(out)
        .extracting(m -> m.get("depth"))
        .containsExactly(0L, 1L, 2L, 3L, 3L, 3L, 3L, 3L, 3L, 3L);
    assertThat(out).allSatisfy(m -> assertThat(m).containsEntry("thread_size", 10L));
  }
}
