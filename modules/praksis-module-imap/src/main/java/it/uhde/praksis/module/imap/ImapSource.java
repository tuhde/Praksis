// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.imap;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import jakarta.mail.FetchProfile;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.UIDFolder;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.search.FlagTerm;
import java.io.UnsupportedEncodingException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.eclipse.angus.mail.imap.IMAPFolder;

/**
 * Turns the mails of a folder into {@code mail.received} events.
 *
 * <p>A mail is named by the UID validity of its folder and its UID, which the server never reuses
 * as long as the validity stays the same. The idempotency key contains both, so a mail that is
 * still in the folder after a restart is announced again and dropped by the store, and a folder
 * that the server renumbered (a new validity) is treated as new. Which mails are handled when the
 * daemon first sees the folder is the setting {@code initial}; afterwards every mail with a higher
 * UID than the last one announced is handled, whether it has been read or not.
 */
public final class ImapSource implements EventSource {
  private static final System.Logger LOG = System.getLogger(ImapSource.class.getName());
  private static final int BATCH = 200;
  private static final List<String> HEADERS =
      List.of(
          "From",
          "To",
          "Cc",
          "Subject",
          "Message-ID",
          "Date",
          "List-Id",
          "Precedence",
          "X-Spam-Flag");

  private ImapSettings settings;
  private Credentials credentials;
  private Connection connection;
  private ScheduledExecutorService poller;
  private long validity = -1;
  private long lastUid = -1;

  @Override
  public String name() {
    return "imap.inbox";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = ImapSettings.parse(config);
  }

  @Override
  public synchronized void useCredentials(Credentials credentials) {
    this.credentials = credentials;
  }

  @Override
  public synchronized void start(EventSink sink) throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("imap source is not configured", false);
    }
    if (poller != null) {
      throw new CapabilityException("imap source is already started", false);
    }
    connection = new Connection(settings);
    connection.useCredentials(credentials);
    poller =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "imap-poll");
              t.setDaemon(true);
              return t;
            });
    poller.scheduleWithFixedDelay(
        () -> poll(sink), 0, settings.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (poller == null) {
      return;
    }
    poller.shutdownNow();
    try {
      poller.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    poller = null;
    connection.close();
  }

  private void poll(EventSink sink) {
    try {
      IMAPFolder folder = (IMAPFolder) connection.store().getFolder(settings.folder());
      folder.open(Folder.READ_ONLY);
      try {
        announceNew(folder, sink);
      } finally {
        try {
          folder.close(false);
        } catch (MessagingException e) {
          connection.close();
        }
      }
    } catch (MessagingException | CapabilityException | RuntimeException e) {
      // tried again at the next poll, on a new connection
      LOG.log(System.Logger.Level.WARNING, "imap " + settings.account() + ": " + e.getMessage());
      connection.close();
    }
  }

  private void announceNew(IMAPFolder folder, EventSink sink) throws MessagingException {
    long current = folder.getUIDValidity();
    if (current != validity) {
      if (validity >= 0) {
        LOG.log(
            System.Logger.Level.WARNING,
            "imap "
                + settings.account()
                + ": the UIDs of "
                + settings.folder()
                + " were renumbered");
      }
      validity = current;
      lastUid = -1;
    }
    long uidNext = folder.getUIDNext();
    Message[] candidates;
    if (lastUid < 0) {
      candidates =
          switch (settings.initial()) {
            case ALL -> folder.getMessages();
            case UNSEEN -> folder.search(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
            case NONE -> new Message[0];
          };
    } else {
      candidates = folder.getMessagesByUID(lastUid + 1, UIDFolder.LASTUID);
    }
    long announcedUpTo = lastUid < 0 ? 0 : lastUid;
    for (int from = 0; from < candidates.length; from += BATCH) {
      Message[] batch =
          Arrays.copyOfRange(candidates, from, Math.min(candidates.length, from + BATCH));
      FetchProfile profile = new FetchProfile();
      profile.add(UIDFolder.FetchProfileItem.UID);
      profile.add(FetchProfile.Item.SIZE);
      HEADERS.forEach(profile::add);
      folder.fetch(batch, profile);
      List<Message> ordered = new ArrayList<>(List.of(batch));
      ordered.sort(Comparator.comparingLong(m -> uidOf(folder, m)));
      for (Message message : ordered) {
        long uid = uidOf(folder, message);
        if (lastUid >= 0 && uid <= lastUid) {
          continue; // the server always includes the last mail of a range
        }
        sink.accept(event(folder, message, uid));
        announcedUpTo = Math.max(announcedUpTo, uid);
      }
    }
    lastUid = lastUid < 0 ? uidNext - 1 : Math.max(lastUid, announcedUpTo);
  }

  private static long uidOf(IMAPFolder folder, Message m) {
    try {
      return folder.getUID(m);
    } catch (MessagingException e) {
      throw new IllegalStateException(e);
    }
  }

  private Event event(IMAPFolder folder, Message message, long uid) throws MessagingException {
    String messageId = header(message, "Message-ID");
    String id = new MessageId(settings.folder(), validity, uid, messageId).encode();
    String unique = settings.account() + ":" + settings.folder() + ":" + validity + ":" + uid;
    Map<String, Object> data = new HashMap<>();
    data.put("message", id);
    data.put("folder", settings.folder());
    data.put("size", (long) Math.max(0, message.getSize()));
    data.put("from", header(message, "From"));
    data.put("to", header(message, "To"));
    data.put("cc", header(message, "Cc"));
    data.put("subject", header(message, "Subject"));
    data.put("message_id", messageId);
    data.put("date", header(message, "Date"));
    data.put("list_id", header(message, "List-Id"));
    data.put("precedence", header(message, "Precedence"));
    data.put("x_spam_flag", header(message, "X-Spam-Flag"));
    return new Event(
        UUID.randomUUID().toString(),
        "mail.received",
        "imap",
        Instant.now(),
        "mail:imap:" + unique,
        Optional.of("imap:" + unique),
        Optional.empty(),
        0,
        data);
  }

  /** The first value of a header, unfolded and with encoded words decoded; empty if absent. */
  static String header(Message message, String name) throws MessagingException {
    String[] values = message.getHeader(name);
    if (values == null || values.length == 0) {
      return "";
    }
    String text = values[0].replaceAll("\\s*\\r?\\n\\s*", " ").strip();
    try {
      return MimeUtility.decodeText(text);
    } catch (UnsupportedEncodingException e) {
      return text;
    }
  }
}
