// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailGetAndFoldersTest {
  @TempDir Path root;

  private MailGet get;
  private MailFolders folders;

  private static final CapabilityContext CONTEXT =
      new CapabilityContext() {
        @Override
        public boolean dryRun() {
          return false;
        }

        @Override
        public String idempotencyKey() {
          return "k";
        }

        @Override
        public Optional<Event> event() {
          return Optional.empty();
        }
      };

  @BeforeEach
  void setUp() throws Exception {
    for (String d : new String[] {"new", "cur", "tmp", ".Archive/cur", ".Archive.2024/cur"}) {
      Files.createDirectories(root.resolve(d));
    }
    get = new MailGet();
    get.configure(Map.of("root", root.toString()));
    folders = new MailFolders();
    folders.configure(Map.of("root", root.toString()));
  }

  private Path mail(String dir, String name, String text) throws Exception {
    Path file = root.resolve(dir).resolve(name);
    Files.writeString(file, text);
    return file;
  }

  @Test
  @SuppressWarnings("unchecked")
  void theFoldersAreListedWithWhatIsUnreadAndWhatIsThere() throws Exception {
    mail("new", "1.a", "Subject: a\n\nx\n");
    mail("cur", "2.b:2,S", "Subject: b\n\nx\n"); // seen
    mail("cur", "3.c:2,", "Subject: c\n\nx\n"); // no flags: unread
    mail(".Archive/cur", "4.d:2,S", "Subject: d\n\nx\n");

    List<Map<String, Object>> list =
        (List<Map<String, Object>>) folders.invoke(CONTEXT, Map.of()).get("folders");

    assertThat(list)
        .extracting(f -> f.get("name"))
        .containsExactly("INBOX", "Archive", "Archive/2024");
    assertThat(list.get(0)).containsEntry("unread", 2L).containsEntry("total", 3L);
    assertThat(list.get(1)).containsEntry("unread", 0L).containsEntry("total", 1L);
    assertThat(list.get(2)).containsEntry("total", 0L);
  }

  @Test
  @SuppressWarnings("unchecked")
  void aMailIsReadForShowing() throws Exception {
    Path file =
        mail(
            "cur",
            "1.a:2,S",
            """
            From: Ann <ann@example.org>
            To: me@example.org
            Subject: =?UTF-8?B?R3LDvMOfZQ==?=
            Date: Sat, 31 Jan 2026 10:20:30 +0100
            Message-ID: <1@example.org>
            Content-Type: multipart/mixed; boundary=b

            --b
            Content-Type: text/plain; charset=utf-8

            Hello Bob
            --b
            Content-Type: text/plain; name="notes.txt"
            Content-Disposition: attachment; filename="notes.txt"

            some notes
            --b--
            """);

    Map<String, Object> mail = get.invoke(CONTEXT, Map.of("message", file.toString()));

    assertThat(mail)
        .containsEntry("from", "Ann <ann@example.org>")
        .containsEntry("subject", "Grüße")
        .containsEntry("message_id", "<1@example.org>")
        .containsEntry("time", Instant.parse("2026-01-31T09:20:30Z"));
    assertThat((String) mail.get("text")).startsWith("Hello Bob");
    assertThat((List<Map<String, Object>>) mail.get("attachments"))
        .singleElement()
        .satisfies(
            a -> assertThat(a).containsEntry("name", "notes.txt").containsEntry("size", 10L));
  }

  @Test
  void aMailOutsideTheMaildirOrThatDoesNotExistIsRefused() throws Exception {
    Path outside = Files.createTempFile("outside", ".eml");
    assertThatThrownBy(() -> get.invoke(CONTEXT, Map.of("message", outside.toString())))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("not a mail in the Maildir");
    assertThatThrownBy(
            () -> get.invoke(CONTEXT, Map.of("message", root.resolve("cur/none").toString())))
        .hasMessageContaining("mail not found");
    assertThatThrownBy(() -> new MailGet().invoke(CONTEXT, Map.of("message", "x")))
        .hasMessageContaining("not configured");
  }
}
