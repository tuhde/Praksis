// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.search.HeaderTerm;
import java.util.Map;
import java.util.Optional;
import org.eclipse.angus.mail.imap.AppendUID;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * Moves a mail to another folder of the same account.
 *
 * <p>The server's MOVE is used if it has it. Otherwise the mail is copied and marked deleted, and
 * removed with {@code UID EXPUNGE} (UIDPLUS), which touches no other mail; a server with neither is
 * refused rather than risk expunging mails of the user's that are marked deleted.
 *
 * <p>The call is idempotent: if the mail is no longer in its folder but a mail with the same
 * Message-ID is in the target folder, the call succeeds and names that mail. That makes a repeat
 * after a crash harmless. A mail without a Message-ID header cannot be found again this way.
 *
 * <p>Folder names use {@code /} between levels; the server's own separator is used on the wire.
 */
public final class ImapMove implements Capability {
  /** Switches for tests: pretend the server lacks MOVE or UIDPLUS to exercise the fallbacks. */
  boolean useMove = true;

  boolean useUidplus = true;

  private ImapSettings settings;
  private Connection connection;

  @Override
  public String name() {
    return "mail.move";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = ImapSettings.parse(config);
    connection = new Connection(settings);
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    if (connection != null) {
      connection.useCredentials(credentials);
    }
  }

  @Override
  public synchronized void close() {
    if (connection != null) {
      connection.close();
    }
  }

  @Override
  public synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("mail.move is not configured", false);
    }
    String target = (String) args.get("folder");
    MessageId id =
        MessageId.parse((String) args.get("message"))
            .orElseThrow(
                () ->
                    new CapabilityException(
                        "'" + args.get("message") + "' is not the id of an IMAP mail", false));
    try {
      return move(context, id, target);
    } catch (MessagingException e) {
      connection.close(); // the next call opens a new connection
      throw new CapabilityException("imap " + settings.account() + ": " + e.getMessage(), true, e);
    }
  }

  private Map<String, Object> move(CapabilityContext context, MessageId id, String targetName)
      throws MessagingException, CapabilityException {
    var store = connection.store();
    IMAPFolder source = (IMAPFolder) store.getFolder(id.folder());
    source.open(Folder.READ_WRITE);
    try {
      IMAPFolder target = (IMAPFolder) store.getFolder(serverName(targetName, source));
      Message message =
          source.getUIDValidity() == id.validity() ? source.getMessageByUID(id.uid()) : null;

      if (message == null) {
        Optional<MessageId> already = find(target, id.messageId());
        if (already.isPresent()) {
          return Map.of("message", already.get().encode());
        }
        throw new CapabilityException(
            "mail not found: it is not in " + id.folder() + " any more and not in " + targetName,
            false);
      }
      if (context.dryRun()) {
        return Map.of("message", new MessageId(targetName, 0, 0, id.messageId()).encode());
      }
      if (!target.exists()) {
        target.create(Folder.HOLDS_MESSAGES);
      }
      AppendUID[] moved;
      if (useMove && store.hasCapability("MOVE")) {
        moved = source.moveUIDMessages(new Message[] {message}, target);
      } else if (useUidplus && store.hasCapability("UIDPLUS")) {
        moved = source.copyUIDMessages(new Message[] {message}, target);
        message.setFlag(Flags.Flag.DELETED, true);
        source.expunge(new Message[] {message});
      } else {
        throw new CapabilityException(
            "the server supports neither MOVE nor UIDPLUS, so a mail cannot be moved safely",
            false);
      }
      // The server names the new place of the mail in its answer. Searching the folder for it
      // would read every mail of a folder that grows with each move.
      if (moved != null && moved.length > 0 && moved[0] != null) {
        return Map.of(
            "message",
            new MessageId(targetName, moved[0].uidvalidity, moved[0].uid, id.messageId()).encode());
      }
      return Map.of(
          "message",
          find(target, id.messageId())
              .orElse(new MessageId(targetName, 0, 0, id.messageId()))
              .encode());
    } finally {
      try {
        source.close(false);
      } catch (MessagingException e) {
        connection.close();
      }
    }
  }

  /** The mail with this Message-ID in the folder, if the folder exists and has one. */
  private Optional<MessageId> find(IMAPFolder folder, String messageId) throws MessagingException {
    if (messageId.isEmpty() || !folder.exists()) {
      return Optional.empty();
    }
    folder.open(Folder.READ_ONLY);
    try {
      Message[] hits = folder.search(new HeaderTerm("Message-ID", messageId));
      if (hits.length == 0) {
        return Optional.empty();
      }
      return Optional.of(
          new MessageId(
              display(folder),
              folder.getUIDValidity(),
              folder.getUID(hits[hits.length - 1]),
              messageId));
    } finally {
      folder.close(false);
    }
  }

  /** The folder name as a rule writes it: levels separated by {@code /}. */
  private static String display(IMAPFolder folder) throws MessagingException {
    char separator = folder.getSeparator();
    return separator == '/' ? folder.getFullName() : folder.getFullName().replace(separator, '/');
  }

  /** The folder name as the server wants it. */
  private static String serverName(String name, IMAPFolder reference) throws MessagingException {
    char separator = reference.getSeparator();
    return separator == '/' || separator == 0 ? name : name.replace('/', separator);
  }
}
