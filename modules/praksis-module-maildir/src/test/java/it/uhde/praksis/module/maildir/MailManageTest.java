// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailManageTest {
  @TempDir Path root;

  private static CapabilityContext context(boolean dry) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dry;
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
  }

  @BeforeEach
  void setUp() throws Exception {
    for (String d : new String[] {"new", "cur", "tmp", ".Trash/cur"}) {
      Files.createDirectories(root.resolve(d));
    }
  }

  private <T extends Capability> T make(T capability) throws Exception {
    capability.configure(Map.of("root", root.toString()));
    return capability;
  }

  private Path mail(String dir, String name, String from, String subject, String body, long age)
      throws Exception {
    Path file = root.resolve(dir).resolve(name);
    Files.writeString(
        file, "From: " + from + "\nSubject: " + subject + "\nDate: d\n\n" + body + "\n");
    Files.setLastModifiedTime(
        file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z").minusSeconds(age)));
    return file;
  }

  private Map<String, Object> call(Capability c, Object... kv) throws Exception {
    Map<String, Object> args = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      args.put((String) kv[i], kv[i + 1]);
    }
    return c.invoke(context(false), args);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> answer) {
    return (List<Map<String, Object>>) answer.get("items");
  }

  @Test
  void markingReadMovesNewToCurWithTheSeenFlagAndTheOldNameStillFindsTheMail() throws Exception {
    Path file = mail("new", "1.a.host", "a@x", "s", "b", 0);
    MailMark mark = make(new MailMark());

    String now = (String) call(mark, "message", file.toString(), "seen", true).get("message");

    assertThat(Path.of(now)).isEqualTo(root.resolve("cur/1.a.host:2,S")).exists();
    assertThat(file).doesNotExist();
    // the id from before is out of date, and still works
    String starred =
        (String) call(mark, "message", file.toString(), "flagged", true).get("message");
    assertThat(Path.of(starred)).isEqualTo(root.resolve("cur/1.a.host:2,FS")).exists();
    // repeating changes nothing and is no error
    assertThat(call(mark, "message", starred, "flagged", true)).containsEntry("message", starred);
    String unread = (String) call(mark, "message", starred, "seen", false).get("message");
    assertThat(Path.of(unread)).isEqualTo(root.resolve("cur/1.a.host:2,F"));
    assertThat(items(call(make(new MailList()), "folder", "INBOX")).get(0))
        .containsEntry("unread", true)
        .containsEntry("starred", true);
  }

  @Test
  void aDryRunMarksNothing() throws Exception {
    Path file = mail("new", "1.a.host", "a@x", "s", "b", 0);
    make(new MailMark()).invoke(context(true), Map.of("message", file.toString(), "seen", true));
    assertThat(file).exists();
  }

  @Test
  void onlyMailsOfTheMaildirCanBeTouched() throws Exception {
    Path outside = Files.writeString(root.resolve("tmp/x"), "x");
    assertThatThrownBy(
            () -> call(make(new MailMark()), "message", outside.toString(), "seen", true))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("is not a mail in the Maildir");
    assertThatThrownBy(() -> call(make(new MailDelete()), "message", outside.toString()))
        .isInstanceOf(CapabilityException.class);
    assertThatThrownBy(
            () -> call(make(new MailMark()), "message", root.resolve("cur/none").toString()))
        .hasMessageContaining("mail not found");
  }

  @Test
  void deleteRemovesTheFileAndAGoneMailIsNotAnError() throws Exception {
    Path file = mail("cur", "2.b.host:2,S", "a@x", "s", "b", 0);
    MailDelete delete = make(new MailDelete());
    assertThat(delete.invoke(context(true), Map.of("message", file.toString())))
        .containsEntry("deleted", true);
    assertThat(file).exists();
    assertThat(call(delete, "message", file.toString())).containsEntry("deleted", true);
    assertThat(file).doesNotExist();
    assertThat(call(delete, "message", file.toString())).containsEntry("deleted", false);
  }

  @Test
  void listPagesAndSorts() throws Exception {
    mail("cur", "1.a.h:2,S", "carol@x", "Bravo", "b", 30);
    mail("cur", "2.a.h:2,S", "alice@x", "Charlie", "b", 20);
    mail("cur", "3.a.h:2,S", "bob@x", "alpha", "b", 10);
    MailList list = make(new MailList());

    assertThat(items(call(list, "folder", "INBOX", "limit", 2, "offset", 1)))
        .extracting(m -> m.get("subject"))
        .containsExactly("Charlie", "Bravo");
    assertThat(items(call(list, "folder", "INBOX", "sort", "subject")))
        .extracting(m -> m.get("subject"))
        .containsExactly("alpha", "Bravo", "Charlie");
    assertThat(items(call(list, "folder", "INBOX", "sort", "from", "reverse", true)))
        .extracting(m -> m.get("from"))
        .containsExactly("carol@x", "bob@x", "alice@x");
    assertThat(items(call(list, "folder", "INBOX", "reverse", true)))
        .extracting(m -> m.get("subject"))
        .containsExactly("Bravo", "Charlie", "alpha");
    assertThat(items(call(list, "folder", "INBOX", "offset", 99))).isEmpty();
    assertThat(call(list, "folder", "INBOX", "limit", 1)).containsEntry("count", 3L);
    assertThatThrownBy(() -> call(list, "folder", "INBOX", "sort", "nope"))
        .hasMessageContaining("sort must be");
  }

  @Test
  void searchLooksInTheHeadersOrTheBodyAndIgnoresTheCase() throws Exception {
    mail("cur", "1.a.h:2,S", "carol@x", "Invoice March", "nothing", 30);
    mail("cur", "2.a.h:2,S", "Dave@x", "Hello", "the INVOICE is attached", 20);
    mail("new", "3.a.h", "bob@x", "Other", "other", 10);
    MailSearch search = make(new MailSearch());

    assertThat(items(call(search, "folder", "INBOX", "query", "invoice")))
        .extracting(m -> m.get("from"))
        .containsExactly("Dave@x", "carol@x");
    assertThat(items(call(search, "folder", "INBOX", "query", "invoice", "in", "subject")))
        .extracting(m -> m.get("from"))
        .containsExactly("carol@x");
    assertThat(items(call(search, "folder", "INBOX", "query", "invoice", "in", "body")))
        .extracting(m -> m.get("from"))
        .containsExactly("Dave@x");
    assertThat(items(call(search, "folder", "INBOX", "query", "dave", "in", "from"))).hasSize(1);
    assertThat(call(search, "folder", "INBOX", "query", "invoice", "limit", 1))
        .containsEntry("count", 2L);
    assertThat(items(call(search, "folder", "INBOX", "query", "zzz"))).isEmpty();
    assertThatThrownBy(() -> call(search, "folder", "INBOX", "query", "x", "in", "nope"))
        .hasMessageContaining("in must be");
  }

  @Test
  void anAttachmentComesBackWithItsContentUnlessItIsTooBig() throws Exception {
    Path file = root.resolve("cur/4.a.h:2,S");
    Files.writeString(
        file,
        """
        From: a@x
        Subject: s
        MIME-Version: 1.0
        Content-Type: multipart/mixed; boundary="b"

        --b
        Content-Type: text/plain

        see attached
        --b
        Content-Type: application/octet-stream; name="data.bin"
        Content-Disposition: attachment; filename="data.bin"
        Content-Transfer-Encoding: base64

        AAECAwQF
        --b--
        """);
    MailAttachment attachment = make(new MailAttachment());

    Map<String, Object> got = call(attachment, "message", file.toString(), "name", "data.bin");
    assertThat((byte[]) got.get("content")).containsExactly(0, 1, 2, 3, 4, 5);
    assertThat(got).containsEntry("size", 6L).containsEntry("name", "data.bin");
    assertThatThrownBy(
            () -> call(attachment, "message", file.toString(), "name", "data.bin", "max_bytes", 3))
        .hasMessageContaining("more than the limit of 3");
    assertThatThrownBy(() -> call(attachment, "message", file.toString(), "name", "nope"))
        .hasMessageContaining("no attachment named 'nope'");
  }
}
