// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

import it.uhde.praksis.capability.Event;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The daemon's SQLite database: the event queue and the run log.
 *
 * <p>An event is stored before anything handles it and is marked done afterwards, so a crash in
 * between leaves it pending and it is handled again after the restart. Handling is therefore at
 * least once, and capabilities get an idempotency key to make repeats harmless.
 *
 * <p>All methods are safe to call from several threads.
 */
public final class Store implements AutoCloseable {
  private static final int SCHEMA_VERSION = 1;

  private final Connection connection;

  private Store(Connection connection) {
    this.connection = connection;
  }

  /** Opens the database file, creating it and its parent directory if needed. */
  public static Store open(Path file) throws StoreException {
    try {
      Path parent = file.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      Connection c = DriverManager.getConnection("jdbc:sqlite:" + file.toAbsolutePath());
      try (Statement s = c.createStatement()) {
        s.execute("PRAGMA journal_mode = WAL");
        s.execute("PRAGMA synchronous = FULL");
      }
      Store store = new Store(c);
      store.migrate();
      return store;
    } catch (SQLException | IOException e) {
      throw new StoreException("cannot open " + file + ": " + e.getMessage(), e);
    }
  }

  private void migrate() throws SQLException, StoreException {
    int version;
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("PRAGMA user_version")) {
      version = r.getInt(1);
    }
    if (version > SCHEMA_VERSION) {
      throw new StoreException(
          "database has schema version " + version + ", this version supports " + SCHEMA_VERSION,
          null);
    }
    if (version < 1) {
      try (Statement s = connection.createStatement()) {
        s.execute(
            """
            CREATE TABLE event (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              id TEXT NOT NULL UNIQUE,
              key TEXT UNIQUE,
              state TEXT NOT NULL DEFAULT 'pending',
              json TEXT NOT NULL
            )""");
        s.execute("CREATE INDEX event_state ON event (state, seq)");
        s.execute(
            """
            CREATE TABLE run_log (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              time TEXT NOT NULL,
              event_id TEXT NOT NULL,
              rule_id TEXT,
              action_id TEXT,
              outcome TEXT NOT NULL,
              message TEXT NOT NULL
            )""");
        s.execute("PRAGMA user_version = " + SCHEMA_VERSION);
      }
    }
  }

  /**
   * Stores an event as pending.
   *
   * @return false, storing nothing, if an event with the same id or the same idempotency key was
   *     stored before
   */
  public synchronized boolean append(Event event) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO event (id, key, json) VALUES (?, ?, ?)")) {
      s.setString(1, event.id());
      s.setString(2, event.key().orElse(null));
      s.setString(3, EventCodec.encode(event));
      return s.executeUpdate() == 1;
    } catch (SQLException e) {
      throw new StoreException("cannot store event " + event.id(), e);
    }
  }

  /** The events not yet marked done, oldest first. */
  public synchronized List<Event> pending() throws StoreException {
    List<Event> out = new ArrayList<>();
    try (Statement s = connection.createStatement();
        ResultSet r =
            s.executeQuery("SELECT json FROM event WHERE state = 'pending' ORDER BY seq")) {
      while (r.next()) {
        out.add(EventCodec.decode(r.getString(1)));
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read pending events", e);
    }
  }

  /** Marks an event as handled, so it is not handled again after a restart. */
  public synchronized void markDone(String eventId) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement("UPDATE event SET state = 'done' WHERE id = ?")) {
      s.setString(1, eventId);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot mark event " + eventId + " done", e);
    }
  }

  /** Appends a line to the run log. */
  public synchronized void log(LogEntry entry) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO run_log (time, event_id, rule_id, action_id, outcome, message)"
                + " VALUES (?, ?, ?, ?, ?, ?)")) {
      s.setString(1, entry.time().toString());
      s.setString(2, entry.eventId());
      s.setString(3, entry.ruleId().orElse(null));
      s.setString(4, entry.actionId().orElse(null));
      s.setString(5, entry.outcome().name());
      s.setString(6, entry.message());
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot write the run log", e);
    }
  }

  /** The newest log lines, returned oldest first. */
  public synchronized List<LogEntry> recentLog(int limit) throws StoreException {
    List<LogEntry> out = new ArrayList<>();
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT time, event_id, rule_id, action_id, outcome, message FROM"
                + " (SELECT * FROM run_log ORDER BY seq DESC LIMIT ?) ORDER BY seq")) {
      s.setInt(1, limit);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          out.add(
              new LogEntry(
                  Instant.parse(r.getString(1)),
                  r.getString(2),
                  Optional.ofNullable(r.getString(3)),
                  Optional.ofNullable(r.getString(4)),
                  LogEntry.Outcome.valueOf(r.getString(5)),
                  r.getString(6)));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the run log", e);
    }
  }

  @Override
  public synchronized void close() throws StoreException {
    try {
      connection.close();
    } catch (SQLException e) {
      throw new StoreException("cannot close the database", e);
    }
  }
}
