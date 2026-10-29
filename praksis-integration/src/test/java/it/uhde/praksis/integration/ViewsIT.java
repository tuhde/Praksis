// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Views against the runtime image: data from a module, state set by the client, grants held. */
class ViewsIT {
  private static final String COMMAND =
      """
      id = "mail.file"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "command"
      title = "File a mail"
      [[trigger.param]]
      name = "message"
      type = "string"
      [[trigger.param]]
      name = "folder"
      type = "string"
      default = "Archive"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { message = { cel = "event.data.message" }, folder = { cel = "event.data.folder" } }
      """;

  private static final String VIEW =
      """
      title = "Mail"
      description = "The mail of a folder."

      [param.folder]
      type = "string"
      default = "INBOX"

      [param.message]
      type = "string"
      default = ""

      [source.messages]
      capability = "mail.list"
      args = { folder = { cel = "view.folder" } }

      [[component]]
      type = "text"
      value = { cel = "view.folder + ': ' + string(source.messages.count) + ' mail(s)'" }
      style = "title"

      [[component]]
      type = "table"
      rows = { cel = "source.messages.items" }
      empty = "No mail"
      columns = [
        { title = "From", value = { cel = "item.from" } },
        { title = "Subject", value = { cel = "item.subject" } },
      ]
      select = { param = "message", value = { cel = "item.id" } }

      [[component.row_action]]
      label = "Archive"
      command = "mail.file"
      args = { message = { cel = "item.id" } }

      [[component]]
      type = "button"
      label = "Archive the selected"
      command = "mail.file"
      args = { message = { cel = "view.message" } }
      enabled = { cel = "view.message != ''" }
      """;

  private static Path mail(Harness h, String name, String from, String subject) throws Exception {
    Path file = h.mail.resolve("cur").resolve(name);
    Files.writeString(file, "From: " + from + "\nSubject: " + subject + "\n\nbody\n");
    return file;
  }

  @Test
  void aViewShowsDataFromAModuleUnderTheGrantsOfItsLayer() throws Exception {
    try (Harness h = new Harness()) {
      Files.writeString(h.home.resolve("ruleset/rules/mail.file.toml"), COMMAND);
      Files.createDirectories(h.home.resolve("ruleset/views"));
      Files.writeString(h.home.resolve("ruleset/views/mail.inbox.toml"), VIEW);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + """

              [[grant]]
              id = "ruleset-list"
              layer = "ruleset"
              capability = "mail.list"
              [grant.args]
              folder = { one_of = ["INBOX", "Archive"] }
              """);
      Path first = mail(h, "1.a:2,", "ann@example.org", "Lunch");
      mail(h, "2.b:2,", "bob@example.org", "Minutes");
      Process daemon = h.startDaemon();

      assertThat(h.cli("views").output())
          .contains("mail.inbox  Mail  [schema 1, layer ruleset]")
          .contains("The mail of a folder.")
          .contains("folder (string), default INBOX");

      // the daemon runs the source and evaluates everything; nothing is left for the client
      String inbox = h.cli("view", "mail.inbox").output();
      assertThat(inbox)
          .contains("== Mail ==")
          .contains("INBOX: 2 mail(s)")
          .contains("| From | Subject")
          .contains("| ann@example.org | Lunch")
          .contains("| bob@example.org | Minutes")
          .contains("[select message=" + first)
          .contains("-> Archive (mail.file)")
          .contains("[Archive the selected] (disabled)");

      // the client sets the state: a selection enables the button
      String selected = h.cli("view", "mail.inbox", "message=" + first).output();
      assertThat(selected).contains("[Archive the selected]").doesNotContain("(disabled)");

      // an action of the view is a command, run like any command; the view then shows less
      Harness.CliResult filed = h.cli("run", "mail.file", "message=" + first);
      assertThat(filed.exit()).as(filed.output()).isZero();
      assertThat(h.cli("view", "mail.inbox").output())
          .contains("INBOX: 1 mail(s)")
          .doesNotContain("Lunch");
      assertThat(h.cli("view", "mail.inbox", "folder=Archive").output())
          .contains("Archive: 1 mail(s)")
          .contains("Lunch");

      // a folder that the grants of the layer do not cover: reported, and the table says why
      Harness.CliResult spam = h.cli("view", "mail.inbox", "folder=Spam");
      assertThat(spam.exit()).isZero();
      assertThat(spam.output()).contains("! source 'messages':").contains("no grant");
      assertThat(spam.output()).contains("[text:").contains("[table:");
      assertThat(h.cli("audit", "-n", "20").output())
          .containsPattern("CALL +view:mail\\.inbox +DENIED +mail\\.list");

      // wrong state, unknown view, and a client that understands only older views
      assertThat(h.cli("view", "mail.inbox", "nope=1").exit()).isEqualTo(1);
      assertThat(h.cli("view", "mail.nope").exit()).isEqualTo(1);
      assertThat(h.ask(new Request.ViewResolve("mail.inbox", Map.of(), 1)))
          .isInstanceOf(Response.ResolvedView.class);
      h.stop(daemon);
    }
  }
}
