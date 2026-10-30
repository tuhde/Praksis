// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MailMoveTest {
  @TempDir Path root;

  private MailMove move;

  private static CapabilityContext context(boolean dryRun) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dryRun;
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
    for (String d : new String[] {"new", "cur", "tmp"}) {
      Files.createDirectories(root.resolve(d));
    }
    move = new MailMove();
    move.configure(Map.of("root", root.toString()));
  }

  private Path mail(String dir, String name) throws IOException {
    Path file = root.resolve(dir).resolve(name);
    Files.writeString(file, "Subject: x\n\nbody\n");
    return file;
  }

  private Map<String, Object> call(Path path, String folder, boolean dryRun)
      throws CapabilityException {
    return move.invoke(context(dryRun), Map.of("message", path.toString(), "folder", folder));
  }

  @Test
  void movesANewMailIntoTheCurDirectoryOfAnotherFolder() throws Exception {
    Path file = mail("new", "1700000000.M1P2.host");
    Path expected = root.resolve(".Archive/cur/1700000000.M1P2.host:2,");
    assertThat(call(file, "Archive", false)).containsEntry("message", expected.toString());
    assertThat(file).doesNotExist();
    assertThat(expected).exists();
    assertThat(root.resolve(".Archive/new")).isDirectory();
    assertThat(root.resolve(".Archive/tmp")).isDirectory();
  }

  @Test
  void keepsTheFlagsOfTheMail() throws Exception {
    Path file = mail("cur", "1700000000.M1P2.host:2,RS");
    call(file, "Archive.2024", false);
    assertThat(root.resolve(".Archive.2024/cur/1700000000.M1P2.host:2,RS")).exists();
  }

  @Test
  void aRepeatedMoveSucceedsAndReportsTheSamePath() throws Exception {
    Path file = mail("new", "1700000000.M1P2.host");
    Map<String, Object> first = call(file, "Archive", false);
    assertThat(call(file, "Archive", false)).isEqualTo(first);
  }

  @Test
  void aMissingMailThatIsNotInTheTargetFolderFails() {
    assertThatThrownBy(() -> call(root.resolve("new/gone"), "Archive", false))
        .isInstanceOf(CapabilityException.class)
        .hasMessageContaining("mail not found");
  }

  @Test
  void aDryRunChangesNothing() throws Exception {
    Path file = mail("new", "1700000000.M1P2.host");
    Map<String, Object> result = call(file, "Archive", true);
    assertThat(file).exists();
    assertThat(root.resolve(".Archive")).doesNotExist();
    assertThat(result.get("message").toString()).endsWith(".Archive/cur/1700000000.M1P2.host:2,");
  }

  @Test
  void movesBetweenFoldersAndBackToTheInbox() throws Exception {
    Path file = mail("new", "m1");
    Path inArchive = Path.of((String) call(file, "Archive", false).get("message"));
    Path back = Path.of((String) call(inArchive, "INBOX", false).get("message"));
    assertThat(back).isEqualTo(root.resolve("cur/m1:2,"));
    assertThat(back).exists();
  }

  @Test
  void refusesPathsOutsideTheMaildir() throws Exception {
    Path outside = Files.createDirectories(root.resolveSibling("elsewhere-" + root.getFileName()));
    Path secret = Files.writeString(outside.resolve("secret"), "x");
    assertThatThrownBy(() -> call(secret, "Archive", false))
        .hasMessageContaining("is not a mail in the Maildir");
    assertThatThrownBy(() -> call(root.resolve("new/../../x"), "Archive", false))
        .hasMessageContaining("is not a mail in the Maildir");
    assertThatThrownBy(() -> call(root.resolve("tmp/m"), "Archive", false))
        .hasMessageContaining("is not a mail in the Maildir");
  }

  @Test
  void refusesFolderNamesThatCouldEscape() throws Exception {
    Path file = mail("new", "m1");
    for (String bad :
        new String[] {"", "../x", "/a", "a/", "a//b", "a/../b", ".hidden", "a..b", "a\\b"}) {
      assertThatThrownBy(() -> call(file, bad, false))
          .as(bad)
          .hasMessageContaining("invalid mail folder name");
    }
    assertThat(file).exists();
  }

  @Test
  void needsSettings() {
    assertThatThrownBy(() -> new MailMove().invoke(context(false), Map.of()))
        .hasMessageContaining("not configured");
    assertThatThrownBy(() -> new MailMove().configure(Map.of()))
        .hasMessageContaining("'root' is required");
    assertThatThrownBy(() -> new MailMove().configure(Map.of("root", root.toString(), "x", 1)))
        .hasMessageContaining("unknown maildir setting 'x'");
  }
}
