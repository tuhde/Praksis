// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Writes every capability call to the audit table of the database, with the arguments as they were
 * evaluated. A call is recorded after it was decided and carried out, so a crash in between leaves
 * no line; the event is then handled again and the repeat is recorded.
 */
public final class StoreAuditLog implements AuditLog {
  private final Store store;

  public StoreAuditLog(Store store) {
    this.store = store;
  }

  @Override
  public void record(
      Instant time,
      String ruleId,
      String capability,
      Map<String, Object> args,
      boolean allowed,
      Optional<String> error) {
    try {
      store.audit(AuditEntry.call(time, ruleId, capability, args, allowed, error));
    } catch (StoreException e) {
      // a broken audit table must not stop the work, but it must not go unnoticed
      System.err.println("praksis: cannot write the audit log: " + e.getMessage());
    }
  }
}
