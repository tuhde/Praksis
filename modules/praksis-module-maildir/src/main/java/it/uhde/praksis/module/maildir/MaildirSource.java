// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Turns every file in the {@code new} directory of a Maildir into a {@code mail.received} event.
 *
 * <p>The idempotency key is the unique part of the file name (the part before the first colon),
 * which Maildir guarantees to be unique. A mail that is still in {@code new} after a restart is
 * announced again, and the engine drops it because the key is known.
 */
public final class MaildirSource implements EventSource {
  private static final System.Logger LOG = System.getLogger(MaildirSource.class.getName());

  private Settings settings;
  private ScheduledExecutorService poller;
  private final Set<String> announced = new HashSet<>();

  @Override
  public String name() {
    return "maildir.inbox";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    settings = Settings.parse(config);
  }

  @Override
  public synchronized void start(EventSink sink) throws CapabilityException {
    if (settings == null) {
      throw new CapabilityException("maildir source is not configured", false);
    }
    if (poller != null) {
      throw new CapabilityException("maildir source is already started", false);
    }
    if (!Files.isDirectory(settings.root().resolve("new"))) {
      throw new CapabilityException(settings.root() + " has no 'new' directory", false);
    }
    poller =
        Executors.newSingleThreadScheduledExecutor(
            r -> {
              Thread t = new Thread(r, "maildir-poll");
              t.setDaemon(true);
              return t;
            });
    long millis = settings.pollInterval().toMillis();
    poller.scheduleWithFixedDelay(() -> poll(sink), 0, millis, TimeUnit.MILLISECONDS);
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
  }

  private void poll(EventSink sink) {
    try {
      Path newDir = settings.root().resolve("new");
      List<Path> files;
      try (Stream<Path> stream = Files.list(newDir)) {
        files =
            stream
                .filter(Files::isRegularFile)
                .filter(f -> !f.getFileName().toString().startsWith("."))
                .sorted()
                .toList();
      }
      Set<String> present = new HashSet<>();
      for (Path file : files) {
        String name = file.getFileName().toString();
        present.add(name);
        if (announced.contains(name)) {
          continue;
        }
        try {
          sink.accept(event(file));
          announced.add(name);
        } catch (IOException | RuntimeException e) {
          // Tried again on the next poll; the file may still be arriving or the engine busy.
          LOG.log(System.Logger.Level.WARNING, "cannot announce " + name + ": " + e);
        }
      }
      announced.retainAll(present);
    } catch (IOException | RuntimeException e) {
      LOG.log(System.Logger.Level.WARNING, "cannot read " + settings.root() + ": " + e);
    }
  }

  private static Event event(Path file) throws IOException {
    String name = file.getFileName().toString();
    String unique = MailMove.uniquePart(name);
    Map<String, String> headers = Headers.read(file);
    return new Event(
        UUID.randomUUID().toString(),
        "mail.received",
        "maildir",
        Files.getLastModifiedTime(file).toInstant(),
        "mail:" + unique,
        Optional.of("maildir:" + unique),
        Optional.empty(),
        0,
        Map.of(
            "path", file.toString(),
            "filename", name,
            "folder", "INBOX",
            "size", Files.size(file),
            "from", headers.getOrDefault("from", ""),
            "to", headers.getOrDefault("to", ""),
            "subject", headers.getOrDefault("subject", ""),
            "message_id", headers.getOrDefault("message-id", "")));
  }
}
