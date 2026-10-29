// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.Event;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ImapManageTest {
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

  private Server server;
  private final List<Capability> opened = new ArrayList<>();

  @BeforeEach
  void setUp() throws Exception {
    server = new Server();
  }

  @AfterEach
  void tearDown() {
    opened.forEach(Capability::close);
    server.close();
  }

  private <T extends Capability> T make(T capability) throws Exception {
    capability.configure(server.settings());
    opened.add(capability);
    return capability;
  }

  private static Map<String, Object> args(Object... kv) {
    Map<String, Object> m = new HashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> items(Map<String, Object> answer) {
    return (List<Map<String, Object>>) answer.get("items");
  }

  private void three() throws Exception {
    server.deliver(true, "From: carol@x", "Subject: Bravo", "Message-ID: <1@x>");
    server.deliver(true, "From: alice@x", "Subject: Charlie", "Message-ID: <2@x>");
    server.deliver(false, "From: bob@x", "Subject: alpha", "Message-ID: <3@x>");
  }

  @Test
  void listPagesAndSorts() throws Exception {
    three();
    var list = make(new ImapRead.Listing());

    assertThat(items(list.invoke(context(false), args("folder", "INBOX", "limit", 2, "offset", 1))))
        .extracting(m -> m.get("subject"))
        .containsExactly("Charlie", "Bravo");
    assertThat(items(list.invoke(context(false), args("folder", "INBOX", "reverse", true))))
        .extracting(m -> m.get("subject"))
        .containsExactly("Bravo", "Charlie", "alpha");
    assertThat(items(list.invoke(context(false), args("folder", "INBOX", "sort", "subject"))))
        .extracting(m -> m.get("subject"))
        .containsExactly("alpha", "Bravo", "Charlie");
    assertThat(
            items(
                list.invoke(
                    context(false), args("folder", "INBOX", "sort", "from", "reverse", true))))
        .extracting(m -> m.get("from"))
        .containsExactly("carol@x", "bob@x", "alice@x");
    assertThat(items(list.invoke(context(false), args("folder", "INBOX", "offset", 9)))).isEmpty();
    assertThatThrownBy(() -> list.invoke(context(false), args("folder", "INBOX", "sort", "nope")))
        .hasMessageContaining("sort must be");
  }

  @Test
  void markingChangesTheFlagsOnTheServerAndTheListShowsThem() throws Exception {
    three();
    var list = make(new ImapRead.Listing());
    var mark = make(new ImapEdit.Mark());
    String unread =
        (String) items(list.invoke(context(false), args("folder", "INBOX"))).get(0).get("id");

    assertThat(mark.invoke(context(true), args("message", unread, "seen", true)))
        .containsEntry("message", unread);
    assertThat(items(list.invoke(context(false), args("folder", "INBOX"))).get(0))
        .containsEntry("unread", true);

    mark.invoke(context(false), args("message", unread, "seen", true, "flagged", true));
    assertThat(items(list.invoke(context(false), args("folder", "INBOX"))).get(0))
        .containsEntry("unread", false)
        .containsEntry("starred", true);
    mark.invoke(context(false), args("message", unread, "seen", false, "flagged", false));
    assertThat(items(list.invoke(context(false), args("folder", "INBOX"))).get(0))
        .containsEntry("unread", true)
        .containsEntry("starred", false);
  }

  @Test
  void deleteRemovesOnlyThatMailAndAGoneMailIsNotAnError() throws Exception {
    three();
    var list = make(new ImapRead.Listing());
    var delete = make(new ImapEdit.Delete());
    String id =
        (String) items(list.invoke(context(false), args("folder", "INBOX"))).get(1).get("id");

    assertThat(delete.invoke(context(true), args("message", id))).containsEntry("deleted", true);
    assertThat(items(list.invoke(context(false), args("folder", "INBOX")))).hasSize(3);
    assertThat(delete.invoke(context(false), args("message", id))).containsEntry("deleted", true);
    assertThat(items(list.invoke(context(false), args("folder", "INBOX"))))
        .extracting(m -> m.get("subject"))
        .containsExactly("alpha", "Bravo");
    assertThat(delete.invoke(context(false), args("message", id))).containsEntry("deleted", false);
    assertThatThrownBy(() -> delete.invoke(context(false), args("message", "file:/etc/passwd")))
        .hasMessageContaining("is not the id of an IMAP mail");
  }

  @Test
  void searchFindsMailsByHeaderOrBody() throws Exception {
    server.deliverRaw(
        true, "From: carol@x\nSubject: Invoice March\nMessage-ID: <1@x>\n\nnothing\n");
    server.deliverRaw(
        true, "From: dave@x\nSubject: Hello\nMessage-ID: <2@x>\n\nthe invoice is attached\n");
    server.deliverRaw(false, "From: bob@x\nSubject: Other\nMessage-ID: <3@x>\n\nother\n");
    var search = make(new ImapRead.Search());

    assertThat(items(search.invoke(context(false), args("folder", "INBOX", "query", "INVOICE"))))
        .extracting(m -> m.get("from"))
        .containsExactly("dave@x", "carol@x");
    assertThat(
            items(
                search.invoke(
                    context(false), args("folder", "INBOX", "query", "invoice", "in", "subject"))))
        .extracting(m -> m.get("from"))
        .containsExactly("carol@x");
    assertThat(
            items(
                search.invoke(
                    context(false), args("folder", "INBOX", "query", "invoice", "in", "body"))))
        .extracting(m -> m.get("from"))
        .containsExactly("dave@x");
    assertThat(search.invoke(context(false), args("folder", "INBOX", "query", "zzz")))
        .containsEntry("count", 0L);
    assertThatThrownBy(
            () -> search.invoke(context(false), args("folder", "INBOX", "query", "x", "in", "no")))
        .hasMessageContaining("in must be");
  }

  @Test
  void anAttachmentComesBackWithItsContentUnlessItIsTooBig() throws Exception {
    server.deliverRaw(
        true,
        """
        From: a@x
        Subject: s
        Message-ID: <4@x>
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
    var list = make(new ImapRead.Listing());
    var attachment = make(new ImapRead.Attachment());
    String id =
        (String) items(list.invoke(context(false), args("folder", "INBOX"))).get(0).get("id");

    Map<String, Object> got =
        attachment.invoke(context(false), args("message", id, "name", "data.bin"));
    assertThat((byte[]) got.get("content")).containsExactly(0, 1, 2, 3, 4, 5);
    assertThat(got).containsEntry("size", 6L).containsEntry("type", "application/octet-stream");
    assertThatThrownBy(
            () ->
                attachment.invoke(
                    context(false), args("message", id, "name", "data.bin", "max_bytes", 3)))
        .hasMessageContaining("limit of 3");
    assertThatThrownBy(() -> attachment.invoke(context(false), args("message", id, "name", "nope")))
        .hasMessageContaining("no attachment named 'nope'");
  }
}
