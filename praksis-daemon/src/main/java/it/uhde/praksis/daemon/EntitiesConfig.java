// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.ruleset.Table;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The {@code [entities]} section of {@code praksis.toml}: where the entities of a type are kept.
 * Without it every type is in the daemon's own database. A storage is a named instance of a backend
 * that a module provides, with the settings that backend asks for; a type is bound to a storage by
 * name. Where data lives is the person's decision, so this is local configuration and never part of
 * a ruleset.
 *
 * <pre>
 * [entities.storage.contacts]        # a storage instance, named by you
 * backend = "json-file"              # the entity storage a module provides
 * directory = "/home/me/contacts"    # settings of that backend
 *
 * [entities.bind]                    # type = storage
 * contact = "contacts"
 *
 * [entities.bind_set]                # entity set = storage: every type in the set
 * people = "contacts"
 * </pre>
 *
 * A type that is bound itself is kept where it is bound. Otherwise the sets that hold it decide: if
 * only one of them is bound, or all are bound to one storage, that is where it is kept; if they are
 * bound to different storages, the smallest set wins, and two equally small sets that disagree stop
 * the start (bind the type itself then).
 *
 * @param storages the storage instances by name
 * @param bind the storage instance of a type, by type id
 * @param bindSet the storage instance of the types of an entity set, by set id
 */
public record EntitiesConfig(
    Map<String, Storage> storages, Map<String, String> bind, Map<String, String> bindSet) {
  /**
   * One storage instance.
   *
   * @param name the name
   * @param backend the backend a module provides
   * @param settings the other keys of the table, handed to the backend
   */
  public record Storage(String name, String backend, Map<String, Object> settings) {}

  /** The default: nothing bound. */
  public static EntitiesConfig none() {
    return new EntitiesConfig(Map.of(), Map.of(), Map.of());
  }

  static EntitiesConfig parse(Table t) {
    t.onlyKeys(Set.of("storage", "bind", "bind_set"));
    Map<String, Storage> storages = new LinkedHashMap<>();
    t.table("storage", false)
        .ifPresent(
            all ->
                all.subTables()
                    .forEach(
                        (name, table) -> {
                          String backend = table.string("backend", true).orElse("");
                          Map<String, Object> settings = new LinkedHashMap<>(table.rawEntries());
                          settings.remove("backend");
                          storages.put(
                              name,
                              new Storage(name, backend, DaemonConfig.normalizeMap(settings)));
                        }));
    Map<String, String> bind = new LinkedHashMap<>();
    t.table("bind", false)
        .ifPresent(
            b ->
                b.rawEntries()
                    .forEach(
                        (type, storage) -> {
                          if (!(storage instanceof String name)) {
                            b.problem("'" + type + "' must name a storage");
                          } else if (!storages.containsKey(name)) {
                            b.problem(
                                "'"
                                    + type
                                    + "' is bound to '"
                                    + name
                                    + "', which is not in [entities.storage.*] "
                                    + new java.util.TreeSet<>(storages.keySet()));
                          } else {
                            bind.put(type, name);
                          }
                        }));
    Map<String, String> bindSet = new LinkedHashMap<>();
    t.table("bind_set", false)
        .ifPresent(
            b ->
                b.rawEntries()
                    .forEach(
                        (set, storage) -> {
                          if (!(storage instanceof String name)) {
                            b.problem("'" + set + "' must name a storage");
                          } else if (!storages.containsKey(name)) {
                            b.problem(
                                "the set '"
                                    + set
                                    + "' is bound to '"
                                    + name
                                    + "', which is not in [entities.storage.*] "
                                    + new java.util.TreeSet<>(storages.keySet()));
                          } else {
                            bindSet.put(set, name);
                          }
                        }));
    return new EntitiesConfig(storages, bind, bindSet);
  }
}
