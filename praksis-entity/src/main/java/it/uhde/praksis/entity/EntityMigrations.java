// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import it.uhde.praksis.store.StoreException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Brings the stored entities up to the version of their type that the layers now have. The daemon
 * notes in its database which version of each type its entities have (the hash of the type file).
 * When a type was replaced in a way that comes with {@code [[migration]]} steps, the first start
 * with the replacement applies the steps to every entity of the type, checks the result, and notes
 * the new version. A replacement without steps (it only adds optional fields, say) changes nothing
 * in the data and is only noted, as is a change of the type in place that names no version its
 * steps start from. A type that has no note yet is taken to be at its current version.
 */
public final class EntityMigrations {
  private EntityMigrations() {}

  private static String key(String typeId) {
    return "entity-type-version:" + typeId;
  }

  /**
   * Migrates what has to be migrated.
   *
   * @return the number of rewritten entities by type id
   * @throws EntityException if the entities do not fit the new version (nothing is changed then)
   */
  public static Map<String, Integer> run(
      EntityService service, EntityCatalog catalog, Store store, Clock clock)
      throws EntityException {
    Map<String, Integer> done = new java.util.TreeMap<>();
    try {
      for (String id : new TreeSet<>(catalog.types().keySet())) {
        List<EntityCatalog.Version> versions = catalog.versions(id);
        if (versions.isEmpty()) {
          continue;
        }
        String current = versions.get(versions.size() - 1).hash();
        Optional<String> recorded = store.meta(key(id));
        if (recorded.isPresent() && recorded.get().equals(current)) {
          continue;
        }
        if (recorded.isPresent()) {
          EntityCatalog.Version last = versions.get(versions.size() - 1);
          int at = -1;
          for (int i = 0; i < versions.size() - 1; i++) {
            if (versions.get(i).hash().equals(recorded.get())) {
              at = i;
            }
          }
          List<EntityCatalog.Version> chain = List.of();
          EntityType from = null;
          if (at >= 0) {
            // made under an older version that the layers still have: every step after it
            chain = versions.subList(at + 1, versions.size());
            from = versions.get(at).type();
          } else if (last.type().migratesFrom().filter(recorded.get()::equals).isPresent()) {
            // the type was changed in place, and its steps name the version of the entities
            chain = List.of(last);
          }
          // anything else (a change in place without steps, say) leaves the data alone: the
          // person who changed the type answers for it fitting
          if (chain.stream().anyMatch(v -> !v.steps().isEmpty())) {
            int n = service.migrate(id, from, chain);
            done.put(id, n);
            store.audit(
                AuditEntry.of(
                    clock.instant(),
                    AuditEntry.Kind.CALL,
                    Optional.empty(),
                    "entity.migrate",
                    Map.of(
                        "type", id,
                        "from", recorded.get(),
                        "to", current,
                        "entities", n,
                        "by", EntityService.USER)));
          }
        }
        store.setMeta(key(id), current);
      }
    } catch (StoreException e) {
      throw new EntityException("the database failed: " + e.getMessage(), e);
    }
    return done;
  }
}
