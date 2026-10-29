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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Several accounts in one module: [module.maildir.work] and [module.maildir.private]. */
class AccountsTest {
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

  @TempDir Path dir;

  private Path work;
  private Path home;

  @BeforeEach
  void setUp() throws Exception {
    work = Files.createDirectories(dir.resolve("work"));
    home = Files.createDirectories(dir.resolve("private"));
    for (Path root : List.of(work, home)) {
      for (String d : new String[] {"new", "cur", "tmp"}) {
        Files.createDirectories(root.resolve(d));
      }
    }
    Files.writeString(work.resolve("cur/1.w:2,S"), "From: boss@x\nSubject: from work\n\nbody\n");
    Files.writeString(home.resolve("cur/1.h:2,S"), "From: mum@x\nSubject: from home\n\nbody\n");
  }

  private Map<String, Object> both() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("work", Map.of("root", work.toString()));
    m.put("private", Map.of("root", home.toString()));
    return m;
  }

  private <T extends Capability> T make(T capability) throws Exception {
    capability.configure(both());
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

  @Test
  void aCallWithoutAMailSaysWhichAccountOrIsToldToChoose() throws Exception {
    var list = make(new MailList());

    assertThatThrownBy(() -> list.invoke(CONTEXT, args("folder", "INBOX")))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("there are several accounts");
    assertThatThrownBy(() -> list.invoke(CONTEXT, args("folder", "INBOX", "account", "nope")))
        .hasMessageContaining("there is no account 'nope'");
    assertThat(items(list.invoke(CONTEXT, args("folder", "INBOX", "account", "work"))))
        .extracting(m -> m.get("subject"))
        .containsExactly("from work");
    assertThat(items(list.invoke(CONTEXT, args("folder", "INBOX", "account", "private"))))
        .extracting(m -> m.get("subject"))
        .containsExactly("from home");
  }

  @Test
  void aMailIsFoundInItsAccountFromItsPathAndNothingCrossesOver() throws Exception {
    var get = make(new MailGet());
    var mark = make(new MailMark());
    var move = make(new MailMove());
    String workMail = work.resolve("cur/1.w:2,S").toString();
    String homeMail = home.resolve("cur/1.h:2,S").toString();

    assertThat(get.invoke(CONTEXT, args("message", workMail)))
        .containsEntry("subject", "from work")
        .containsEntry("account", "work");
    assertThat(get.invoke(CONTEXT, args("message", homeMail))).containsEntry("account", "private");

    String marked =
        (String) mark.invoke(CONTEXT, args("message", homeMail, "seen", false)).get("message");
    assertThat(Path.of(marked)).startsWith(home);

    // a move stays in the Maildir of the mail
    String moved =
        (String)
            move.invoke(CONTEXT, args("message", workMail, "folder", "Archive")).get("message");
    assertThat(Path.of(moved)).startsWith(work.resolve(".Archive"));
    assertThat(home.resolve(".Archive")).doesNotExist();

    // a path in no Maildir of the accounts
    Path stray = Files.writeString(dir.resolve("stray"), "x");
    assertThatThrownBy(() -> get.invoke(CONTEXT, args("message", stray.toString())))
        .hasMessageContaining("is in none of the Maildirs");
  }

  @Test
  void theSourceWatchesEveryAccountAndEventsNameTheirs() throws Exception {
    Files.move(work.resolve("cur/1.w:2,S"), work.resolve("new/2.w"));
    Files.move(home.resolve("cur/1.h:2,S"), home.resolve("new/2.h"));
    List<Event> events = new CopyOnWriteArrayList<>();
    MaildirSource source = new MaildirSource();
    Map<String, Object> accounts = new LinkedHashMap<>();
    accounts.put("work", Map.of("root", work.toString(), "poll_interval_ms", 20L));
    accounts.put("private", Map.of("root", home.toString(), "poll_interval_ms", 20L));
    source.configure(accounts);
    source.start(events::add);
    try {
      long end = System.nanoTime() + 10_000_000_000L;
      while (events.size() < 2 && System.nanoTime() < end) {
        Thread.sleep(10);
      }
    } finally {
      source.stop();
    }

    assertThat(events)
        .extracting(e -> e.data().get("account"))
        .containsExactlyInAnyOrder("work", "private");
    assertThat(events)
        .extracting(e -> e.key().orElseThrow())
        .containsExactlyInAnyOrder("maildir:work:2.w", "maildir:private:2.h");
    for (Event e : events) {
      String account = (String) e.data().get("account");
      assertThat(Path.of((String) e.data().get("message")))
          .startsWith(account.equals("work") ? work : home);
    }
  }

  @Test
  void theAccountWithoutANameKeepsItsEventKeys() throws Exception {
    Files.move(work.resolve("cur/1.w:2,S"), work.resolve("new/2.w"));
    List<Event> events = new CopyOnWriteArrayList<>();
    MaildirSource source = new MaildirSource();
    source.configure(Map.of("root", work.toString(), "poll_interval_ms", 20L));
    source.start(events::add);
    try {
      long end = System.nanoTime() + 10_000_000_000L;
      while (events.isEmpty() && System.nanoTime() < end) {
        Thread.sleep(10);
      }
    } finally {
      source.stop();
    }
    assertThat(events.get(0).key()).contains("maildir:2.w");
    assertThat(events.get(0).data()).containsEntry("account", "default");
  }

  @Test
  void theAccountsAreListed() throws Exception {
    var accounts = make(new MailAccounts());
    var listed = items(accounts.invoke(CONTEXT, Map.of()));
    assertThat(listed).extracting(m -> m.get("name")).containsExactly("work", "private");
    assertThat(listed.get(0)).containsEntry("kind", "maildir").containsEntry("state", "ok");
    assertThat(listed.get(0).get("address")).isEqualTo(work.toString());
    Files.delete(home.resolve("cur/1.h:2,S"));
    Files.delete(home.resolve("cur"));
    assertThat(items(accounts.invoke(CONTEXT, Map.of())).get(1)).containsEntry("state", "error");
  }

  @Test
  void theSettingsOfSeveralAccountsAreChecked() {
    Map<String, Object> mixed = new HashMap<>(Map.of("root", work.toString()));
    mixed.put("private", Map.of("root", home.toString()));
    assertThatThrownBy(() -> new MailList().configure(mixed))
        .hasMessageContaining("outside of the accounts");
    assertThatThrownBy(
            () -> new MailList().configure(Map.of("Bad Name", Map.of("root", work.toString()))))
        .hasMessageContaining("the account name 'Bad Name'");
    assertThatThrownBy(() -> new MailList().configure(Map.of("work", Map.of("rot", "x"))))
        .hasMessageContaining("unknown maildir setting 'rot'");
  }
}
