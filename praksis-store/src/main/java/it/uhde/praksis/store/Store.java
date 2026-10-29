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
  private static final int SCHEMA_VERSION = 4;

  private final Connection connection;
  private volatile java.util.function.Consumer<String> changes = topic -> {};

  private Store(Connection connection) {
    this.connection = connection;
  }

  /**
   * Tells {@code listener} the topic of every write: {@code events}, {@code log}, {@code audit},
   * {@code approvals} (requests and missed events) and {@code shadow}. The listener is called while
   * the store is locked, so it must be quick and must not call the store.
   */
  public void onChange(java.util.function.Consumer<String> listener) {
    this.changes = listener;
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
    if (version < 3) {
      try (Statement s = connection.createStatement()) {
        s.execute(
            """
            CREATE TABLE approval (
              id INTEGER PRIMARY KEY AUTOINCREMENT,
              time TEXT NOT NULL,
              rule_id TEXT NOT NULL,
              layer TEXT NOT NULL,
              capability TEXT NOT NULL,
              args TEXT NOT NULL,
              event_id TEXT NOT NULL,
              state TEXT NOT NULL,
              answered TEXT
            )""");
        s.execute("PRAGMA user_version = 3");
      }
    }
    if (version < 4) {
      try (Statement s = connection.createStatement()) {
        s.execute("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)");
        s.execute(
            """
            CREATE TABLE promoted (
              rule_id TEXT NOT NULL,
              hash TEXT NOT NULL,
              time TEXT NOT NULL,
              json TEXT NOT NULL,
              PRIMARY KEY (rule_id, hash)
            )""");
        s.execute(
            """
            CREATE TABLE shadow_rule (
              rule_id TEXT NOT NULL,
              hash TEXT NOT NULL,
              since TEXT NOT NULL,
              PRIMARY KEY (rule_id, hash)
            )""");
        s.execute(
            """
            CREATE TABLE shadow_run (
              seq INTEGER PRIMARY KEY AUTOINCREMENT,
              time TEXT NOT NULL,
              rule_id TEXT NOT NULL,
              hash TEXT NOT NULL,
              event_id TEXT NOT NULL,
              calls TEXT NOT NULL,
              note TEXT NOT NULL
            )""");
        s.execute("CREATE INDEX shadow_run_rule ON shadow_run (rule_id, hash, seq)");
        s.execute("PRAGMA user_version = 4");
      }
    }
  }

  /** A small value kept by the store, e.g. that the baseline of promoted rules was taken. */
  public synchronized Optional<String> meta(String key) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement("SELECT value FROM meta WHERE key = ?")) {
      s.setString(1, key);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? Optional.of(r.getString(1)) : Optional.empty();
      }
    } catch (SQLException e) {
      throw new StoreException("cannot read " + key, e);
    }
  }

  public synchronized void setMeta(String key, String value) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement("INSERT OR REPLACE INTO meta (key, value) VALUES (?, ?)")) {
      s.setString(1, key);
      s.setString(2, value);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot write " + key, e);
    }
  }

  /** Marks a version of a rule as live. Doing it again changes nothing. */
  public synchronized void promote(Promoted rule) throws StoreException {
    promoteLocked(rule);
    changes.accept("shadow");
  }

  private void promoteLocked(Promoted rule) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO promoted (rule_id, hash, time, json) VALUES (?, ?, ?, ?)")) {
      s.setString(1, rule.ruleId());
      s.setString(2, rule.hash());
      s.setString(3, rule.time().toString());
      s.setString(4, rule.json());
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot promote " + rule.ruleId(), e);
    }
  }

  /** Every version of every rule that was promoted, oldest first. */
  public synchronized List<Promoted> promoted() throws StoreException {
    List<Promoted> out = new ArrayList<>();
    try (Statement s = connection.createStatement();
        ResultSet r =
            s.executeQuery("SELECT rule_id, hash, time, json FROM promoted ORDER BY time, rowid")) {
      while (r.next()) {
        out.add(
            new Promoted(
                r.getString(1), r.getString(2), Instant.parse(r.getString(3)), r.getString(4)));
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the promoted rules", e);
    }
  }

  /** Starts the trial of a version of a rule, unless it has started before. */
  public synchronized void startShadow(ShadowRule rule) throws StoreException {
    startShadowLocked(rule);
    changes.accept("shadow");
  }

  private void startShadowLocked(ShadowRule rule) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT OR IGNORE INTO shadow_rule (rule_id, hash, since) VALUES (?, ?, ?)")) {
      s.setString(1, rule.ruleId());
      s.setString(2, rule.hash());
      s.setString(3, rule.since().toString());
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot start the trial of " + rule.ruleId(), e);
    }
  }

  /** The versions on trial. */
  public synchronized List<ShadowRule> shadowRules() throws StoreException {
    List<ShadowRule> out = new ArrayList<>();
    try (Statement s = connection.createStatement();
        ResultSet r =
            s.executeQuery("SELECT rule_id, hash, since FROM shadow_rule ORDER BY rule_id")) {
      while (r.next()) {
        out.add(new ShadowRule(r.getString(1), r.getString(2), Instant.parse(r.getString(3))));
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the trials", e);
    }
  }

  /** Ends the trial of a version of a rule and forgets what was observed. */
  public synchronized void endShadow(String ruleId, String hash) throws StoreException {
    endShadowLocked(ruleId, hash);
    changes.accept("shadow");
  }

  private void endShadowLocked(String ruleId, String hash) throws StoreException {
    try {
      for (String table : new String[] {"shadow_rule", "shadow_run"}) {
        try (PreparedStatement s =
            connection.prepareStatement(
                "DELETE FROM " + table + " WHERE rule_id = ? AND hash = ?")) {
          s.setString(1, ruleId);
          s.setString(2, hash);
          s.executeUpdate();
        }
      }
    } catch (SQLException e) {
      throw new StoreException("cannot end the trial of " + ruleId, e);
    }
  }

  /** Forgets what was observed of a version and starts its trial again at {@code since}. */
  public synchronized void restartShadow(String ruleId, String hash, Instant since)
      throws StoreException {
    endShadow(ruleId, hash);
    startShadow(new ShadowRule(ruleId, hash, since));
  }

  /** Records what a rule on trial would have done; keeps the newest {@code keep} per version. */
  public synchronized void addShadowRun(ShadowRun run, int keep) throws StoreException {
    addShadowRunLocked(run, keep);
    changes.accept("shadow");
  }

  private void addShadowRunLocked(ShadowRun run, int keep) throws StoreException {
    try {
      try (PreparedStatement s =
          connection.prepareStatement(
              "INSERT INTO shadow_run (time, rule_id, hash, event_id, calls, note)"
                  + " VALUES (?, ?, ?, ?, ?, ?)")) {
        s.setString(1, run.time().toString());
        s.setString(2, run.ruleId());
        s.setString(3, run.hash());
        s.setString(4, run.eventId());
        s.setString(5, run.calls());
        s.setString(6, run.note());
        s.executeUpdate();
      }
      try (PreparedStatement s =
          connection.prepareStatement(
              "DELETE FROM shadow_run WHERE rule_id = ? AND hash = ? AND seq <= (SELECT seq FROM"
                  + " shadow_run WHERE rule_id = ? AND hash = ? ORDER BY seq DESC LIMIT 1 OFFSET"
                  + " ?)")) {
        s.setString(1, run.ruleId());
        s.setString(2, run.hash());
        s.setString(3, run.ruleId());
        s.setString(4, run.hash());
        s.setInt(5, keep);
        s.executeUpdate();
      }
    } catch (SQLException e) {
      throw new StoreException("cannot record the trial of " + run.ruleId(), e);
    }
  }

  /** What a version has been seen to do, newest first. */
  public synchronized List<ShadowRun> shadowRuns(String ruleId, String hash, int limit)
      throws StoreException {
    List<ShadowRun> out = new ArrayList<>();
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT time, rule_id, hash, event_id, calls, note FROM shadow_run WHERE rule_id = ?"
                + " AND hash = ? ORDER BY seq DESC LIMIT ?")) {
      s.setString(1, ruleId);
      s.setString(2, hash);
      s.setInt(3, limit);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          out.add(
              new ShadowRun(
                  Instant.parse(r.getString(1)),
                  r.getString(2),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  r.getString(6)));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the trial of " + ruleId, e);
    }
  }

  /** How many events a version has been seen with. */
  public synchronized int shadowCount(String ruleId, String hash) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT COUNT(*) FROM shadow_run WHERE rule_id = ? AND hash = ?")) {
      s.setString(1, ruleId);
      s.setString(2, hash);
      try (ResultSet r = s.executeQuery()) {
        return r.next() ? r.getInt(1) : 0;
      }
    } catch (SQLException e) {
      throw new StoreException("cannot count the trial of " + ruleId, e);
    }
  }

  /** The log lines of one event, oldest first. */
  public synchronized List<LogEntry> logOf(String eventId) throws StoreException {
    List<LogEntry> out = new ArrayList<>();
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT time, event_id, rule_id, action_id, outcome, message FROM run_log WHERE"
                + " event_id = ? ORDER BY seq")) {
      s.setString(1, eventId);
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
      throw new StoreException("cannot read the log of " + eventId, e);
    }
  }

  /**
   * Stores an event as pending.
   *
   * @return false, storing nothing, if an event with the same id or the same idempotency key was
   *     stored before
   */
  public synchronized boolean append(Event event) throws StoreException {
    boolean result = appendLocked(event);
    changes.accept("events");
    return result;
  }

  private boolean appendLocked(Event event) throws StoreException {
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
    markDoneLocked(eventId);
    changes.accept("events");
  }

  private void markDoneLocked(String eventId) throws StoreException {
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
    logLocked(entry);
    changes.accept("log");
  }

  private void logLocked(LogEntry entry) throws StoreException {
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

  /** The newest stored events, whatever their state, returned oldest first. */
  public synchronized List<Event> recentEvents(int limit) throws StoreException {
    List<Event> out = new ArrayList<>();
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT json FROM (SELECT seq, json FROM event ORDER BY seq DESC LIMIT ?)"
                + " ORDER BY seq")) {
      s.setInt(1, limit);
      try (ResultSet r = s.executeQuery()) {
        while (r.next()) {
          out.add(EventCodec.decode(r.getString(1)));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the events", e);
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
    auditLocked(entry);
    changes.accept("audit");
  }

  private void auditLocked(AuditEntry entry) throws StoreException {
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

  /** The newest audit line of this kind about this rule, if there is one. */
  public synchronized Optional<AuditEntry> lastAuditForRule(AuditEntry.Kind kind, String ruleId)
      throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement(
            "SELECT time, kind, rule_id, subject, allowed, detail, error FROM audit WHERE kind = ?"
                + " AND rule_id = ? ORDER BY seq DESC LIMIT 1")) {
      s.setString(1, kind.name());
      s.setString(2, ruleId);
      try (ResultSet r = s.executeQuery()) {
        if (!r.next()) {
          return Optional.empty();
        }
        return Optional.of(
            new AuditEntry(
                Instant.parse(r.getString(1)),
                AuditEntry.Kind.valueOf(r.getString(2)),
                Optional.ofNullable(r.getString(3)),
                r.getString(4),
                r.getInt(5) == 1,
                r.getString(6),
                Optional.ofNullable(r.getString(7))));
      }
    } catch (SQLException e) {
      throw new StoreException("cannot read the audit log", e);
    }
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
    addMissedLocked(time, ruleId, eventId, cutoff, maxPerRule);
    changes.accept("approvals");
  }

  private void addMissedLocked(
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

  /** Forgets one event a rule missed, e.g. after it was replayed. */
  public synchronized void removeMissed(String ruleId, String eventId) throws StoreException {
    removeMissedLocked(ruleId, eventId);
    changes.accept("approvals");
  }

  private void removeMissedLocked(String ruleId, String eventId) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement("DELETE FROM missed WHERE rule_id = ? AND event_id = ?")) {
      s.setString(1, ruleId);
      s.setString(2, eventId);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot forget the missed event " + eventId, e);
    }
  }

  /**
   * Opens a request for a permission. A rule that already has an unanswered or refused request gets
   * no second one; that request is returned.
   */
  public synchronized Approval openApproval(
      Instant time,
      String ruleId,
      String layer,
      String capability,
      java.util.Map<String, Object> args,
      String eventId)
      throws StoreException {
    Approval result = openApprovalLocked(time, ruleId, layer, capability, args, eventId);
    changes.accept("approvals");
    return result;
  }

  private Approval openApprovalLocked(
      Instant time,
      String ruleId,
      String layer,
      String capability,
      java.util.Map<String, Object> args,
      String eventId)
      throws StoreException {
    for (Approval a :
        approvals(java.util.EnumSet.of(Approval.State.PENDING, Approval.State.DENIED))) {
      if (a.ruleId().equals(ruleId)) {
        return a;
      }
    }
    try (PreparedStatement s =
        connection.prepareStatement(
            "INSERT INTO approval (time, rule_id, layer, capability, args, event_id, state)"
                + " VALUES (?, ?, ?, ?, ?, ?, 'PENDING')",
            Statement.RETURN_GENERATED_KEYS)) {
      s.setString(1, time.toString());
      s.setString(2, ruleId);
      s.setString(3, layer);
      s.setString(4, capability);
      s.setString(5, EventCodec.encodeMap(args));
      s.setString(6, eventId);
      s.executeUpdate();
      try (ResultSet keys = s.getGeneratedKeys()) {
        keys.next();
        return new Approval(
            keys.getLong(1),
            time,
            ruleId,
            layer,
            capability,
            args,
            eventId,
            Approval.State.PENDING,
            Optional.empty());
      }
    } catch (SQLException e) {
      throw new StoreException("cannot open an approval request for " + ruleId, e);
    }
  }

  /** The requests in these states, oldest first. */
  public synchronized List<Approval> approvals(java.util.Set<Approval.State> states)
      throws StoreException {
    List<Approval> out = new ArrayList<>();
    try (Statement s = connection.createStatement();
        ResultSet r =
            s.executeQuery(
                "SELECT id, time, rule_id, layer, capability, args, event_id, state, answered"
                    + " FROM approval ORDER BY id")) {
      while (r.next()) {
        Approval.State state = Approval.State.valueOf(r.getString(8));
        if (states.contains(state)) {
          out.add(
              new Approval(
                  r.getLong(1),
                  Instant.parse(r.getString(2)),
                  r.getString(3),
                  r.getString(4),
                  r.getString(5),
                  EventCodec.decodeMap(r.getString(6)),
                  r.getString(7),
                  state,
                  Optional.ofNullable(r.getString(9)).map(Instant::parse)));
        }
      }
      return out;
    } catch (SQLException e) {
      throw new StoreException("cannot read the approval requests", e);
    }
  }

  /** The request with this number, in any state. */
  public synchronized Optional<Approval> approval(long id) throws StoreException {
    return approvals(java.util.EnumSet.allOf(Approval.State.class)).stream()
        .filter(a -> a.id() == id)
        .findFirst();
  }

  /** Records the answer to a request. */
  public synchronized void answer(long id, Approval.State state, Instant at) throws StoreException {
    answerLocked(id, state, at);
    changes.accept("approvals");
  }

  private void answerLocked(long id, Approval.State state, Instant at) throws StoreException {
    try (PreparedStatement s =
        connection.prepareStatement("UPDATE approval SET state = ?, answered = ? WHERE id = ?")) {
      s.setString(1, state.name());
      s.setString(2, at.toString());
      s.setLong(3, id);
      s.executeUpdate();
    } catch (SQLException e) {
      throw new StoreException("cannot answer approval request " + id, e);
    }
  }

  /** Forgets the events a rule missed, e.g. after they were replayed or discarded. */
  public synchronized void clearMissed(String ruleId) throws StoreException {
    clearMissedLocked(ruleId);
    changes.accept("approvals");
  }

  private void clearMissedLocked(String ruleId) throws StoreException {
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
