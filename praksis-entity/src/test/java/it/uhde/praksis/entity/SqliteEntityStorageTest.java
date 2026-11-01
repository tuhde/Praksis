// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.storage.testkit.EntityStorageContract;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;

/** The storage in the daemon's database passes the tests that every storage passes. */
class SqliteEntityStorageTest extends EntityStorageContract {
  private final List<Store> stores = new ArrayList<>();

  @Override
  protected EntityStorage open(Path dir, List<StorageType> types) throws Exception {
    Store store = Store.open(dir.resolve("praksis.db"));
    stores.add(store);
    SqliteEntityStorage storage = new SqliteEntityStorage(store);
    storage.open(types);
    return new EntityStorage() {
      @Override
      public String name() {
        return storage.name();
      }

      @Override
      public void open(List<StorageType> t) throws it.uhde.praksis.capability.StorageException {
        storage.open(t);
      }

      @Override
      public java.util.Optional<it.uhde.praksis.capability.StoredEntity> get(String t, String id)
          throws it.uhde.praksis.capability.StorageException {
        return storage.get(t, id);
      }

      @Override
      public void insert(String t, it.uhde.praksis.capability.StoredEntity e)
          throws it.uhde.praksis.capability.StorageException {
        storage.insert(t, e);
      }

      @Override
      public void replace(String t, it.uhde.praksis.capability.StoredEntity e)
          throws it.uhde.praksis.capability.StorageException {
        storage.replace(t, e);
      }

      @Override
      public boolean delete(String t, String id)
          throws it.uhde.praksis.capability.StorageException {
        return storage.delete(t, id);
      }

      @Override
      public Page query(String t, it.uhde.praksis.capability.Query q)
          throws it.uhde.praksis.capability.StorageException {
        return storage.query(t, q);
      }

      @Override
      public java.util.Optional<String> findUnique(String t, String f, Object v)
          throws it.uhde.praksis.capability.StorageException {
        return storage.findUnique(t, f, v);
      }

      @Override
      public <T> T atomically(Work<T> work) throws it.uhde.praksis.capability.StorageException {
        return storage.atomically(work);
      }

      @Override
      public void close() {
        // the store is closed by the test, after the storage
      }
    };
  }

  @AfterEach
  void closeStores() throws Exception {
    for (Store s : stores) {
      s.close();
    }
  }
}
