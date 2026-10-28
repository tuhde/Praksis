// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.store.Store;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The done criterion of M1: a daemon killed with SIGKILL does not process a mail twice. */
class RestartIT {
  private static long moves(Harness h) throws Exception {
    return h.log().stream()
        .filter(l -> "move".equals(l.actionId()) && "OK".equals(l.outcome()))
        .count();
  }

  private static boolean failed(Harness h) throws Exception {
    return h.log().stream().anyMatch(l -> "FAILED".equals(l.outcome()));
  }

  @Test
  void aMailIsMovedOnceAndARestartAfterKillDoesNotMoveItAgain() throws Exception {
    try (Harness h = new Harness()) {
      Process daemon = h.startDaemon();
      h.deliver("1700000000.M1P1.host", "first");
      Harness.await("the first mail to be archived", () -> h.names(".Archive/cur").size() == 1);
      Harness.await("the move to be logged", () -> moves(h) == 1);

      h.kill(daemon);
      assertThat(daemon.exitValue()).isEqualTo(137); // 128 + SIGKILL

      // a mail that arrives while the daemon is down is handled after the restart
      h.deliver("1700000001.M1P2.host", "second");
      h.startDaemon();
      Harness.await("the second mail to be archived", () -> h.names(".Archive/cur").size() == 2);
      Thread.sleep(500); // time for any wrong second handling of the first mail

      assertThat(h.names(".Archive/cur"))
          .containsExactly("1700000000.M1P1.host:2,", "1700000001.M1P2.host:2,");
      assertThat(h.names("new")).isEmpty();
      assertThat(moves(h)).isEqualTo(2);
      assertThat(failed(h)).isFalse();
      assertThat(h.status().pending()).isZero();
    }
  }

  @Test
  void killingTheDaemonInTheMiddleOfABurstLosesNothingAndRepeatsNothing() throws Exception {
    int mails = 300;
    try (Harness h = new Harness()) {
      Process daemon = h.startDaemon();
      for (int i = 0; i < mails; i++) {
        h.deliver(String.format("17000%05d.M1P%d.host", i, i), "mail " + i);
      }
      // kill as soon as it is busy, twice, with different amounts of work done each time
      for (int round = 0; round < 2; round++) {
        int already = round;
        Harness.await("work to begin", () -> h.names(".Archive/cur").size() > already * 10);
        h.kill(daemon);
        assertThat(h.names("new").size() + h.names(".Archive/cur").size()).isEqualTo(mails);
        if (round == 0) {
          // the point of the test: the daemon really was killed with work left to do
          assertThat(h.names("new")).as("mails left when killed").isNotEmpty();
        }
        daemon = h.startDaemon();
      }
      Harness.await("every mail to be archived", () -> h.names(".Archive/cur").size() == mails);
      Harness.await("the queue to be empty", () -> h.status().pending() == 0);
      Thread.sleep(300);

      assertThat(h.names("new")).isEmpty();
      assertThat(h.names(".Archive/cur")).hasSize(mails).doesNotHaveDuplicates();
      // an event repeated after a crash finds its mail already moved and must still succeed
      assertThat(failed(h)).as(h.daemonLog()).isFalse();
      assertThat(moves(h))
          .isGreaterThanOrEqualTo(mails - 1); // log lines of killed runs may be lost
    }
  }

  @Test
  void anEventThatWasStoredButNeverFinishedIsRepeatedAndTheRepeatIsHarmless() throws Exception {
    try (Harness h = new Harness()) {
      // the state after a crash between the move and "event done": the mail is already in
      // Archive, and the event is still pending in the store
      String name = "1700000000.M1P1.host";
      Files.createDirectories(h.mail.resolve(".Archive/cur"));
      Files.writeString(h.mail.resolve(".Archive/cur").resolve(name + ":2,"), "Subject: x\n\n");
      try (Store store = Store.open(h.home.resolve("praksis.db"))) {
        store.append(
            new Event(
                "e1",
                "mail.received",
                "maildir",
                Instant.now(),
                "mail:" + name,
                Optional.of("maildir:" + name),
                Optional.empty(),
                0,
                Map.of(
                    "path",
                    h.mail.resolve("new").resolve(name).toString(),
                    "filename",
                    name,
                    "folder",
                    "INBOX",
                    "size",
                    12L)));
      }
      h.startDaemon();
      Harness.await("the event to be handled", () -> moves(h) == 1);
      assertThat(h.status().pending()).isZero();
      assertThat(failed(h)).isFalse();
      assertThat(h.names(".Archive/cur")).containsExactly(name + ":2,");
    }
  }
}
