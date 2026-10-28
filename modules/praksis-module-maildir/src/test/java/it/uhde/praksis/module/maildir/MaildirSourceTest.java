// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MaildirSourceTest {
  @TempDir Path root;

  private final List<Event> events = new CopyOnWriteArrayList<>();

  @BeforeEach
  void setUp() throws IOException {
    for (String d : new String[] {"new", "cur", "tmp"}) {
      Files.createDirectories(root.resolve(d));
    }
  }

  private MaildirSource source() throws Exception {
    MaildirSource source = new MaildirSource();
    source.configure(Map.of("root", root.toString(), "poll_interval_ms", 10L));
    source.start(events::add);
    return source;
  }

  private Path mail(String name, String content) throws IOException {
    Path file = root.resolve("new").resolve(name);
    Files.writeString(file, content);
    return file;
  }

  private static void await(BooleanSupplier condition) throws InterruptedException {
    long end = System.nanoTime() + 5_000_000_000L;
    while (!condition.getAsBoolean()) {
      if (System.nanoTime() > end) {
        throw new AssertionError("timed out");
      }
      Thread.sleep(5);
    }
  }

  @Test
  void announcesMailsAsEventsWithTheirHeaders() throws Exception {
    mail(
        "1700000000.M1P2.host",
        "From: Anna <anna@example.org>\nTo: me@example.org\n"
            + "Subject: =?UTF-8?B?R3LDvMOfZQ==?=\nMessage-ID: <abc@example.org>\n"
            + "Cc: bob@example.org\nDate: Tue, 27 Oct 2026 10:00:00 +0100\n"
            + "List-Id: <news.lists.example.org>\nPrecedence: bulk\nX-Spam-Flag: YES\n\nHello\n");
    MaildirSource source = source();
    try {
      await(() -> events.size() == 1);
    } finally {
      source.stop();
    }
    Event e = events.get(0);
    assertThat(e.type()).isEqualTo("mail.received");
    assertThat(e.source()).isEqualTo("maildir");
    assertThat(e.target()).isEqualTo("mail:1700000000.M1P2.host");
    assertThat(e.key()).contains("maildir:1700000000.M1P2.host");
    assertThat(e.depth()).isZero();
    assertThat(e.data())
        .containsEntry("message", root.resolve("new/1700000000.M1P2.host").toString())
        .containsEntry("folder", "INBOX")
        .containsEntry("from", "Anna <anna@example.org>")
        .containsEntry("to", "me@example.org")
        .containsEntry("subject", "Grüße")
        .containsEntry("message_id", "<abc@example.org>")
        .containsEntry("cc", "bob@example.org")
        .containsEntry("date", "Tue, 27 Oct 2026 10:00:00 +0100")
        .containsEntry("list_id", "<news.lists.example.org>")
        .containsEntry("precedence", "bulk")
        .containsEntry("x_spam_flag", "YES")
        .containsEntry("size", Files.size(root.resolve("new/1700000000.M1P2.host")));
  }

  @Test
  void picksUpLaterMailsAndAnnouncesEachOnlyOnce() throws Exception {
    mail("a", "Subject: a\n\n");
    MaildirSource source = source();
    try {
      await(() -> events.size() == 1);
      mail("b", "Subject: b\n\n");
      mail(".tmp-not-a-mail", "x");
      await(() -> events.size() == 2);
      Thread.sleep(100); // many more polls
    } finally {
      source.stop();
    }
    assertThat(events)
        .extracting(e -> Path.of((String) e.data().get("message")).getFileName().toString())
        .containsExactly("a", "b");
  }

  @Test
  void aRestartAnnouncesWhatIsStillThereWithTheSameKey() throws Exception {
    mail("a", "Subject: a\n\n");
    MaildirSource first = source();
    await(() -> events.size() == 1);
    first.stop();
    MaildirSource second = source();
    try {
      await(() -> events.size() == 2);
    } finally {
      second.stop();
    }
    assertThat(events.get(1).key()).isEqualTo(events.get(0).key());
    assertThat(events.get(1).id()).isNotEqualTo(events.get(0).id());
  }

  @Test
  void aMovedMailIsNotAnnouncedAgainEvenWhenItReturnsWithFlagsSuffix() throws Exception {
    Path file = mail("a", "Subject: a\n\n");
    MaildirSource source = source();
    try {
      await(() -> events.size() == 1);
      Files.move(file, root.resolve("cur/a:2,S"));
      Thread.sleep(100);
    } finally {
      source.stop();
    }
    assertThat(events).hasSize(1);
  }

  @Test
  void failsToStartWithoutSettingsOrANewDirectory() throws Exception {
    assertThatThrownBy(() -> new MaildirSource().start(events::add))
        .hasMessageContaining("not configured");
    MaildirSource bad = new MaildirSource();
    bad.configure(Map.of("root", root.resolve("new").toString()));
    assertThatThrownBy(() -> bad.start(events::add)).hasMessageContaining("no 'new' directory");
    assertThatThrownBy(() -> new MaildirSource().configure(Map.of("root", "/does/not/exist")))
        .hasMessageContaining("is not a directory");
    assertThatThrownBy(
            () ->
                new MaildirSource()
                    .configure(Map.of("root", root.toString(), "poll_interval_ms", 0L)))
        .hasMessageContaining("poll_interval_ms");
  }
}
