// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Response;
import org.junit.jupiter.api.Test;

class TopicsTest {
  @Test
  void theFirstAskGetsEveryTopicAndLaterOnesOnlyWhatChanged() throws Exception {
    Topics topics = new Topics();
    Response.ChangeList first = topics.changes(0, 0);
    assertThat(first.topics()).containsExactlyElementsOf(Topics.ALL);

    topics.bump("log");
    topics.bump("approvals");
    topics.bump("log");
    Response.ChangeList next = topics.changes(first.counter(), 0);
    assertThat(next.topics()).containsExactly("log", "approvals");
    assertThat(next.counter()).isGreaterThan(first.counter());
    assertThat(topics.changes(next.counter(), 0).topics()).isEmpty();
  }

  @Test
  void aRequestWaitsForTheNextChangeAndGivesUpAfterTheTimeout() throws Exception {
    Topics topics = new Topics();
    long counter = topics.changes(0, 0).counter();
    long start = System.nanoTime();
    assertThat(topics.changes(counter, 100).topics()).isEmpty();
    assertThat(System.nanoTime() - start).isGreaterThanOrEqualTo(90_000_000L);

    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                return;
              }
              topics.bump("shadow");
            });
    start = System.nanoTime();
    assertThat(topics.changes(counter, 10_000).topics()).containsExactly("shadow");
    assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
  }

  @Test
  void closingLetsEveryoneGo() throws Exception {
    Topics topics = new Topics();
    long counter = topics.changes(0, 0).counter();
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                Thread.sleep(50);
              } catch (InterruptedException e) {
                return;
              }
              topics.close();
            });
    long start = System.nanoTime();
    assertThat(topics.changes(counter, 10_000).topics()).isEmpty();
    assertThat(System.nanoTime() - start).isLessThan(5_000_000_000L);
  }
}
