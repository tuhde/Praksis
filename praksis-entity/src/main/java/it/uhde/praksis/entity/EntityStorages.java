// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.StorageException;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.store.Store;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Which storage keeps the entities of which type: the one in the daemon's database unless a person
 * bound the type to another in {@code praksis.toml}.
 */
public final class EntityStorages implements AutoCloseable {
  private final EntityStorage fallback;
  private final Map<String, EntityStorage> bound;
  private final List<EntityStorage> owned;

  /**
   * Creates the registry.
   *
   * @param fallback the storage of a type that is bound to none
   * @param bound the storage of a type, by type id
   * @param owned the storages to close when the daemon stops (those a module provided)
   */
  public EntityStorages(
      EntityStorage fallback, Map<String, EntityStorage> bound, List<EntityStorage> owned) {
    this.fallback = fallback;
    this.bound = Map.copyOf(bound);
    this.owned = List.copyOf(owned);
  }

  /** All types in the daemon's own database. */
  public static EntityStorages sqliteOnly(Store store, EntityCatalog catalog)
      throws StorageException {
    SqliteEntityStorage sqlite = new SqliteEntityStorage(store);
    sqlite.open(storageTypes(catalog.types().values()));
    return new EntityStorages(sqlite, Map.of(), List.of());
  }

  /** The types as a storage is told of them. */
  public static List<StorageType> storageTypes(Collection<EntityType> types) {
    return types.stream()
        .map(
            t ->
                new StorageType(
                    t.id(),
                    t.fields().stream()
                        .map(
                            f ->
                                new StorageType.Field(f.name(), f.type(), f.unique(), f.required()))
                        .toList()))
        .toList();
  }

  /** The storage that keeps the type. */
  public EntityStorage of(String typeId) {
    return bound.getOrDefault(typeId, fallback);
  }

  @Override
  public void close() {
    owned.forEach(EntityStorage::close);
  }
}
