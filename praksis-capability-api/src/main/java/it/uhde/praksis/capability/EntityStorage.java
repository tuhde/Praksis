// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where the entities of some types are kept. The daemon has one built in (its SQLite database); a
 * module can provide others, named in {@code backends} of its {@code module.toml} and found with
 * {@link java.util.ServiceLoader}. A person binds a type to a storage in {@code praksis.toml}, so
 * where data lives is the person's decision and not a ruleset's.
 *
 * <p>Praksis does the thinking: it checks types, required and unique values, relations and
 * permissions, sets the id, the times and who made the change, and serializes the writes to one
 * storage, so a backend only keeps and finds entities. It must keep what it is given exactly (the
 * Java types of {@link StoredEntity}) and be safe for use by one thread at a time. Several
 * instances of one backend can exist, each configured for its own types.
 */
public interface EntityStorage extends AutoCloseable {
  /** A page of a query. */
  record Page(List<StoredEntity> items, long total) {}

  /** The name the backend is known by, as in {@code backend = "..."} in {@code praksis.toml}. */
  String name();

  /**
   * Gives the backend its settings from {@code praksis.toml}, once, before any other method.
   *
   * @throws CapabilityException if they are missing or wrong
   */
  default void configure(Map<String, Object> settings) throws CapabilityException {}

  /** Hands over the way to get secrets, once, after {@link #configure}. */
  default void useCredentials(Credentials credentials) {}

  /** Hands over a directory the backend may keep files in, once, after {@link #configure}. */
  default void useDataDirectory(Path directory) {}

  /**
   * Opens the storage for these types: creates what it needs (tables, files) if they are not there
   * and checks what is there. Called once; a type that the backend cannot keep is refused here with
   * a message that names the type.
   */
  void open(List<StorageType> types) throws StorageException;

  /** The entity, or empty. */
  Optional<StoredEntity> get(String type, String id) throws StorageException;

  /** Keeps a new entity. The id is not in use. */
  void insert(String type, StoredEntity entity) throws StorageException;

  /** Replaces the entity of the same id. */
  void replace(String type, StoredEntity entity) throws StorageException;

  /** Removes the entity; false if there was none. */
  boolean delete(String type, String id) throws StorageException;

  /**
   * The entities that match, in order, and how many match in all. {@link FilterMatcher#page} does
   * it in memory.
   */
  Page query(String type, Query query) throws StorageException;

  /**
   * The id of the entity of the type whose unique field has this value, or empty. The default asks
   * {@link #query}; a backend with an index should answer from it.
   */
  default Optional<String> findUnique(String type, String field, Object value)
      throws StorageException {
    Page page =
        query(type, new Query(Optional.of(new Filter.Equals(field, value)), "id", false, 1, 0));
    return page.items().stream().map(StoredEntity::id).findFirst();
  }

  /** What {@link #atomically} runs. */
  @FunctionalInterface
  interface Work<T> {
    T run() throws StorageException;
  }

  /**
   * Runs the work so that its writes happen together or not at all, if the backend can. The default
   * just runs it.
   */
  default <T> T atomically(Work<T> work) throws StorageException {
    return work.run();
  }

  /** Releases files and connections, once, when the daemon stops. */
  @Override
  default void close() {}
}
