// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Commands against the runtime image: a rule that only a person starts, through the CLI. */
class CommandsIT {
  private static final String COMMAND =
      """
      id = "mail.file"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "command"
      title = "File a mail"
      description = "Moves one mail to a folder."

      [[trigger.param]]
      name = "message"
      type = "string"
      label = "The mail"

      [[trigger.param]]
      name = "folder"
      type = "string"
      default = "Archive"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = { cel = "event.data.folder" } }
      """;

  private static Path mailInCur(Harness h, String name) throws Exception {
    Path mail = h.mail.resolve("cur").resolve(name);
    Files.writeString(mail, "From: a@example.org\nSubject: x\n\nbody\n");
    return mail;
  }

  @Test
  void aCommandIsListedStartedByAPersonAndHeldToTheGrantsLikeAnyRule() throws Exception {
    try (Harness h = new Harness()) {
      Files.writeString(h.home.resolve("ruleset/rules/mail.file.toml"), COMMAND);
      Process daemon = h.startDaemon();

      String listed = h.cli("commands").output();
      assertThat(listed)
          .contains("mail.file  File a mail  [local-write, layer ruleset]")
          .contains("Moves one mail to a folder.")
          .contains("message (string), required  The mail")
          .contains("folder (string), default Archive");

      // a person starts it; the default fills the folder
      Path mail = mailInCur(h, "1.a:2,");
      Harness.CliResult done = h.cli("run", "mail.file", "message=" + mail);
      assertThat(done.exit()).as(done.output()).isZero();
      assertThat(done.output()).contains("Started mail.file").contains("OK").contains("move:");
      assertThat(h.names(".Archive/cur")).containsExactly("1.a:2,");

      // what is wrong with the call is said before anything runs
      Harness.CliResult missing = h.cli("run", "mail.file");
      assertThat(missing.exit()).isEqualTo(1);
      assertThat(missing.output()).contains("missing required field 'message'");
      assertThat(h.cli("run", "mail.file", "nope=1").exit()).isEqualTo(2);
      assertThat(h.cli("run", "mail.nope").exit()).isEqualTo(1);

      // a folder that no grant covers: the command is denied and disabled, like any rule
      Path other = mailInCur(h, "2.b:2,");
      Harness.CliResult denied = h.cli("run", "mail.file", "message=" + other, "folder=Spam");
      assertThat(denied.exit()).as(denied.output()).isEqualTo(1);
      assertThat(denied.output()).contains("DENIED");
      assertThat(h.names(".Spam/cur")).isEmpty();
      assertThat(h.cli("commands").output()).contains("DISABLED");
      assertThat(h.cli("approvals").output()).contains("mail.file");

      // every start is on the record, with the client
      String audit = h.cli("audit", "-n", "50").output();
      assertThat(audit).containsPattern("COMMAND +mail\\.file +mail\\.file ");
      assertThat(audit).contains("\"client\":\"praksis-cli\"").contains("Spam");
      h.stop(daemon);
    }
  }
}
