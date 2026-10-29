// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailListTest {
  @TempDir Path root;

  private MailList list;

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
    for (String d : new String[] {"new", "cur", "tmp", ".Archive/cur"}) {
      Files.createDirectories(root.resolve(d));
    }
    list = new MailList();
    list.configure(Map.of("root", root.toString()));
  }

  private void mail(String dir, String name, String subject, long age) throws Exception {
    Path file = root.resolve(dir).resolve(name);
    Files.writeString(file, "From: a@example.org\nSubject: " + subject + "\nDate: d\n\nbody\n");
    Files.setLastModifiedTime(
        file, FileTime.from(Instant.parse("2026-01-01T00:00:00Z").minusSeconds(age)));
  }

  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> items(String folder, Object limit) throws Exception {
    Map<String, Object> args = new java.util.HashMap<>(Map.of("folder", folder));
    if (limit != null) {
      args.put("limit", limit);
    }
    return (List<Map<String, Object>>) list.invoke(CONTEXT, args).get("items");
  }

  @Test
  void listsTheMailsOfAFolderNewestFirstWithWhatAListShows() throws Exception {
    mail("new", "1.a", "old", 100);
    mail("cur", "2.b:2,S", "newer", 10);
    mail("new", "3.c", "newest", 1);
    mail(".Archive/cur", "4.d:2,", "archived", 5);

    List<Map<String, Object>> inbox = items("INBOX", null);

    assertThat(inbox).extracting(i -> i.get("subject")).containsExactly("newest", "newer", "old");
    assertThat(inbox.get(0))
        .containsEntry("from", "a@example.org")
        .containsEntry("new", true)
        .containsEntry("id", root.resolve("new/3.c").toString());
    assertThat(inbox.get(1)).containsEntry("new", false);
    assertThat((Long) inbox.get(0).get("size")).isPositive();
    assertThat(items("Archive", null))
        .extracting(i -> i.get("subject"))
        .containsExactly("archived");
    assertThat(list.invoke(CONTEXT, Map.of("folder", "INBOX")).get("count")).isEqualTo(3L);
  }

  @Test
  void aLimitCutsTheListButNotTheCount() throws Exception {
    for (int i = 0; i < 5; i++) {
      mail("cur", i + ".x:2,", "m" + i, i);
    }
    assertThat(items("INBOX", 2L)).hasSize(2);
    assertThat(list.invoke(CONTEXT, Map.of("folder", "INBOX", "limit", 2L)).get("count"))
        .isEqualTo(5L);
  }

  @Test
  void aMissingFolderIsEmptyAndABadNameIsRefused() throws Exception {
    assertThat(items("Nowhere", null)).isEmpty();
    assertThatThrownBy(() -> items("../x", null))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("invalid mail folder name");
    assertThatThrownBy(() -> new MailList().invoke(CONTEXT, Map.of("folder", "INBOX")))
        .hasMessageContaining("not configured");
  }
}
