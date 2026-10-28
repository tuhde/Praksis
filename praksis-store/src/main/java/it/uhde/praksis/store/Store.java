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
  private static final int SCHEMA_VERSION = 2;

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
        s.execute("PRAGMA user_version = 1");
      }
    }
    if (version < 2) {
      try (Statement s = connection.createStatement()) {
        s.execute(
            """
            CREATE TABLE audit (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              time TEXT NOT NULL,
              kind TEXT NOT NULL,
              rule_id TEXT,
              subject TEXT NOT NULL,
              allowed INTEGER NOT NULL,
              detail TEXT NOT NULL,
              error TEXT
            )""");
        s.execute(
            """
            CREATE TABLE missed (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              time TEXT NOT NULL,
              rule_id TEXT NOT NULL,
              event_id TEXT NOT NULL,
              UNIQUE (rule_id, event_id)
            )""");
        s.execute("PRAGMA user_version = 2");
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

  /** The stored event with this id, whatever its state. */
  public synchronized Optional<Event> event(String eventId) throws StoreException {
    try (PreparedStatement s = connection.prepareStatement("SELECT json FROM event WHERE id = ?")) {
      s.setString(1, eventId);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(EventCodec.decode(r.getString(1))) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new StoreException("cannot read event " + eventId, e);
    }
  }

  /** Appends a line to the audit log. */
  public synchronized void audit(AuditEntry entry) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO audit (time, kind, rule_id, subject, allowed, detail, error)"
                + " VALUES (?, ?, ?, ?, ?, ?, ?)")) {
      s.setString(1, entry.time().toString());
      s.setString(2, entry.kind().name());
      s.setString(3, entry.ruleId().orElse(null));
      s.setString(4, entry.subject());
      s.setInt(5, entry.allowed() ? 1 : 0);
      s.setString(6, entry.detail());
      s.setString(7, entry.error().orElse(null));
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot write the audit log", e);
    }
  }

  /** The newest audit lines, returned oldest first. */
  public synchronized List<AuditEntry> recentAudit(int limit) throws StoreException {
    return readAudit(
        "SELECT time, kind, rule_id, subject, allowed, detail, error FROM"
            + " (SELECT * FROM audit ORDER BY seq DESC LIMIT ?) ORDER BY seq",
        limit);
  }

  /** The newest audit line of this kind, if there is one. */
  public synchronized Optional<AuditEntry> lastAudit(AuditEntry.Kind kind) throws StoreException {
    return readAudit(
            "SELECT time, kind, rule_id, subject, allowed, detail, error FROM audit"
                + " WHERE kind = '"
                + kind.name()
                + "' ORDER BY seq DESC LIMIT ?",
            1)
        .stream()
        .findFirst();
  }

  private List<AuditEntry> readAudit(String sql, int limit) throws StoreException {
    List<AuditEntry> out = new ArrayList<>();
    try (PreparedStatement s = connection.prepareStatement(sql)) {
      s.setInt(1, limit);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          out.add(
              new AuditEntry(
                  Instant.parse(r.getString(1)),
                  AuditEntry.Kind.valueOf(r.getString(2)),
                  Optional.ofNullable(r.getString(3)),
                  r.getString(4),
                  r.getInt(5) == 1,
                  r.getString(6),
                  Optional.ofNullable(r.getString(7))));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the audit log", e);
    }
  }

  /**
   * Remembers that a rule did not handle an event because it was disabled. Remembering the same
   * pair again changes nothing. Entries older than the cutoff go, and so do the oldest ones beyond
   * {@code maxPerRule}.
   */
  public synchronized void addMissed(
      Instant time, String ruleId, String eventId, Instant cutoff, int maxPerRule)
      throws StoreException {
    try {
      try (PreparedStatement s =
          connection.prepareStatement(
              "INSERT OR IGNORE INTO missed (time, rule_id, event_id) VALUES (?, ?, ?)")) {
        s.setString(1, time.toString());
        s.setString(2, ruleId);
        s.setString(3, eventId);
        s.executeUpdate();
      }
      try (PreparedStatement s =
          connection.prepareStatement(
              "DELETE FROM missed WHERE rule_id = ? AND (time < ? OR seq <= (SELECT seq FROM"
                  + " missed WHERE rule_id = ? ORDER BY seq DESC LIMIT 1 OFFSET ?))")) {
        s.setString(1, ruleId);
        s.setString(2, cutoff.toString());
        s.setString(3, ruleId);
        s.setInt(4, maxPerRule);
        s.executeUpdate();
      }
    } catch (SQLException e) {
      throw new StoreException("cannot remember the missed event " + eventId, e);
    }
  }

  /** The ids of the events a rule missed, oldest first. */
  public synchronized List<String> missed(String ruleId) throws StoreException {
    List<String> out = new ArrayList<>();
    try (PreparedStatement s =
        connection.prepareStatement("SELECT event_id FROM missed WHERE rule_id = ? ORDER BY seq")) {
      s.setString(1, ruleId);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          out.add(r.getString(1));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the missed events", e);
    }
  }

  /** How many events each rule missed, for the rules that missed any. */
  public synchronized java.util.Map<String, Integer> missedCounts() throws StoreException {
    java.util.Map<String, Integer> out = new java.util.TreeMap<>();
    try (Statement s = connection.createStatement();
        ResultSet r = s.executeQuery("SELECT rule_id, COUNT(*) FROM missed GROUP BY rule_id")) {
      while (r.next()) {
        out.put(r.getString(1), r.getInt(2));
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot count the missed events", e);
    }
  }

  /** Forgets the events a rule missed, e.g. after they were replayed or discarded. */
  public synchronized void clearMissed(String ruleId) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement("DELETE FROM missed WHERE rule_id = ?")) {
      s.setString(1, ruleId);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot clear the missed events of " + ruleId, e);
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
