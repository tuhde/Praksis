// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeUtility;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.angus.mail.imap.IMAPFolder;
import org.eclipse.angus.mail.imap.IMAPStore;

/**
 * The three capabilities that only read: {@code mail.folders} (every folder with its unread and its
 * total number of mails), {@code mail.list} (the newest mails of a folder, with what a list shows)
 * and {@code mail.get} (one mail for showing: headers, plain text, HTML as text and the list of
 * attachments). Nothing is changed on the server, the mails are fetched with {@code peek} so they
 * stay unread, and content that could do something is never executed here.
 */
public abstract class ImapRead implements Capability {
  private static final int DEFAULT_LIMIT = 100;
  private static final int MAX_LIMIT = 1000;

  /** The most characters of a text or HTML body that are returned. */
  private static final int MAX_BODY = 2_000_000;

  private ImapSettings settings;
  private Connection connection;

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
  public final synchronized Map<String, Object> invoke(
      CapabilityContext context, Map<String, Object> args) throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException(name() + " is not configured", false);
    }
    try {
      return read(connection.store(), args);
    } catch (MessagingException | IOException e) {
      connection.close(); // the next call opens a new connection
      throw new CapabilityException("imap " + settings.account() + ": " + e.getMessage(), true, e);
    }
  }

  abstract Map<String, Object> read(IMAPStore store, Map<String, Object> args)
      throws MessagingException, IOException, CapabilityException;

  /** The folder name as a rule writes it: levels separated by {@code /}. */
  static String display(IMAPFolder folder) throws MessagingException {
    char separator = folder.getSeparator();
    return separator == '/' || separator == 0
        ? folder.getFullName()
        : folder.getFullName().replace(separator, '/');
  }

  /** The folder name as the server wants it. */
  static String serverName(String name, IMAPFolder reference) throws MessagingException {
    char separator = reference.getSeparator();
    return separator == '/' || separator == 0 ? name : name.replace('/', separator);
  }

  /** {@code mail.folders}. */
  public static final class Folders extends ImapRead {
    @Override
    public String name() {
      return "mail.folders";
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args) throws MessagingException {
      List<Object> folders = new ArrayList<>();
      IMAPFolder root = (IMAPFolder) store.getDefaultFolder();
      for (Folder f : root.list("*")) {
        IMAPFolder folder = (IMAPFolder) f;
        if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) {
          continue;
        }
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("name", display(folder));
        entry.put("unread", (long) Math.max(0, folder.getUnreadMessageCount()));
        entry.put("total", (long) Math.max(0, folder.getMessageCount()));
        folders.add(entry);
      }
      folders.sort(
          (a, b) -> {
            String x = (String) ((Map<?, ?>) a).get("name");
            String y = (String) ((Map<?, ?>) b).get("name");
            if (x.equalsIgnoreCase("INBOX") != y.equalsIgnoreCase("INBOX")) {
              return x.equalsIgnoreCase("INBOX") ? -1 : 1;
            }
            return x.compareToIgnoreCase(y);
          });
      return Map.of("folders", folders);
    }
  }

  /** {@code mail.list}. */
  public static final class Listing extends ImapRead {
    @Override
    public String name() {
      return "mail.list";
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args) throws MessagingException {
      IMAPFolder folder = (IMAPFolder) store.getFolder((String) args.get("folder"));
      if (!folder.exists()) {
        return Map.of("items", List.of(), "count", 0L);
      }
      long asked = args.get("limit") instanceof Number n ? n.longValue() : DEFAULT_LIMIT;
      int limit = (int) Math.max(1, Math.min(asked, MAX_LIMIT));
      folder.open(Folder.READ_ONLY);
      try {
        int total = folder.getMessageCount();
        int first = Math.max(1, total - limit + 1);
        List<Object> items = new ArrayList<>();
        if (total > 0) {
          Message[] messages = folder.getMessages(first, total);
          FetchProfile profile = new FetchProfile();
          profile.add(FetchProfile.Item.ENVELOPE);
          profile.add(FetchProfile.Item.FLAGS);
          profile.add(FetchProfile.Item.SIZE);
          profile.add(org.eclipse.angus.mail.imap.IMAPFolder.FetchProfileItem.HEADERS);
          profile.add(jakarta.mail.UIDFolder.FetchProfileItem.UID);
          folder.fetch(messages, profile);
          for (int i = messages.length - 1; i >= 0; i--) {
            items.add(item(folder, (MimeMessage) messages[i]));
          }
        }
        return Map.of("items", items, "count", (long) total);
      } finally {
        folder.close(false);
      }
    }

    private Map<String, Object> item(IMAPFolder folder, MimeMessage m) throws MessagingException {
      Map<String, Object> item = new LinkedHashMap<>();
      String[] ids = m.getHeader("Message-ID");
      item.put(
          "id",
          new MessageId(
                  display(folder),
                  folder.getUIDValidity(),
                  folder.getUID(m),
                  ids == null || ids.length == 0 ? "" : ids[0])
              .encode());
      item.put("from", address(m.getFrom()));
      item.put("subject", subject(m));
      item.put("date", m.getHeader("Date") == null ? "" : m.getHeader("Date")[0]);
      item.put("time", instant(m));
      item.put("size", (long) Math.max(0, m.getSize()));
      item.put("unread", !m.getFlags().contains(Flags.Flag.SEEN));
      return item;
    }
  }

  /** {@code mail.get}. */
  public static final class Get extends ImapRead {
    @Override
    public String name() {
      return "mail.get";
    }

    @Override
    Map<String, Object> read(IMAPStore store, Map<String, Object> args)
        throws MessagingException, IOException, CapabilityException {
      MessageId id =
          MessageId.parse((String) args.get("message"))
              .orElseThrow(
                  () ->
                      new CapabilityException(
                          "'" + args.get("message") + "' is not the id of an IMAP mail", false));
      IMAPFolder folder = (IMAPFolder) store.getFolder(id.folder());
      folder.open(Folder.READ_ONLY);
      try {
        MimeMessage m =
            folder.getUIDValidity() == id.validity()
                ? (MimeMessage) folder.getMessageByUID(id.uid())
                : null;
        if (m == null) {
          throw new CapabilityException("mail not found: it is not in " + id.folder(), false);
        }
        Body body = new Body();
        collect(m, body, 0);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("from", address(m.getFrom()));
        out.put("to", address(m.getRecipients(Message.RecipientType.TO)));
        out.put("cc", address(m.getRecipients(Message.RecipientType.CC)));
        out.put("subject", subject(m));
        out.put("date", m.getHeader("Date") == null ? "" : m.getHeader("Date")[0]);
        out.put("time", instant(m));
        out.put("message_id", id.messageId());
        out.put("text", cut(body.text));
        out.put("html", cut(body.html));
        out.put("attachments", body.attachments);
        out.put("size", (long) Math.max(0, m.getSize()));
        return out;
      } finally {
        folder.close(false);
      }
    }

    private static String cut(String text) {
      return text.length() > MAX_BODY ? text.substring(0, MAX_BODY) : text;
    }

    private static final class Body {
      String text = "";
      String html = "";
      final List<Object> attachments = new ArrayList<>();
    }

    /** Walks the parts: the first plain text, the first HTML, and the attachments by name. */
    private static void collect(Part part, Body out, int depth)
        throws MessagingException, IOException {
      if (depth > 8) {
        return;
      }
      if (part.isMimeType("multipart/*")) {
        Multipart multipart = (Multipart) part.getContent();
        for (int i = 0; i < multipart.getCount(); i++) {
          collect(multipart.getBodyPart(i), out, depth + 1);
        }
        return;
      }
      String disposition = part.getDisposition();
      String file = part.getFileName();
      boolean attached = Part.ATTACHMENT.equalsIgnoreCase(disposition) || file != null;
      if (!attached && part.isMimeType("text/plain")) {
        if (out.text.isEmpty()) {
          out.text = String.valueOf(part.getContent());
        }
      } else if (!attached && part.isMimeType("text/html")) {
        if (out.html.isEmpty()) {
          out.html = String.valueOf(part.getContent());
        }
      } else {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("name", file == null ? "(no name)" : MimeUtility.decodeText(file));
        a.put("type", part.getContentType().split(";", 2)[0].strip().toLowerCase());
        a.put("size", (long) Math.max(0, part.getSize()));
        out.attachments.add(a);
      }
    }
  }

  static String address(jakarta.mail.Address[] addresses) {
    if (addresses == null || addresses.length == 0) {
      return "";
    }
    List<String> out = new ArrayList<>();
    for (jakarta.mail.Address a : addresses) {
      out.add(a instanceof InternetAddress ia ? ia.toUnicodeString() : a.toString());
    }
    return String.join(", ", out);
  }

  static String subject(MimeMessage m) throws MessagingException {
    String s = m.getSubject();
    return s == null ? "" : s;
  }

  /** The time of the mail: its Date header, else when the server received it. */
  static Instant instant(MimeMessage m) throws MessagingException {
    if (m.getSentDate() != null) {
      return m.getSentDate().toInstant();
    }
    return m.getReceivedDate() == null ? Instant.EPOCH : m.getReceivedDate().toInstant();
  }
}
