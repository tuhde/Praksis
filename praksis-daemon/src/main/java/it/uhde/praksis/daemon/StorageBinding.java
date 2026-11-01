// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.entity.EntityCatalog;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which storage keeps which type, from {@code [entities.bind]} (a type) and {@code
 * [entities.bind_set]} (the types of an entity set): the type's own binding wins; otherwise the
 * sets that hold it and are bound decide, the smallest set first, and two equally small sets that
 * are bound to different storages are an error.
 */
final class StorageBinding {
  private StorageBinding() {}

  /**
   * The storage name of every type that is bound by itself or by a set. A type that is not in the
   * result stays in the daemon's own database.
   */
  static Map<String, String> resolve(EntitiesConfig config, EntityCatalog catalog)
      throws DaemonException {
    for (String type : config.bind().keySet()) {
      if (catalog.get(type).isEmpty()) {
        throw new DaemonException(
            "[entities.bind]: there is no entity type '"
                + type
                + "' (types: "
                + new TreeSet<>(catalog.types().keySet())
                + ")");
      }
    }
    for (String set : config.bindSet().keySet()) {
      if (!catalog.sets().containsKey(set)) {
        throw new DaemonException(
            "[entities.bind_set]: there is no entity set '"
                + set
                + "' (sets: "
                + new TreeSet<>(catalog.sets().keySet())
                + ")");
      }
    }
    Map<String, String> out = new LinkedHashMap<>();
    for (String type : new TreeSet<>(catalog.types().keySet())) {
      String own = config.bind().get(type);
      if (own != null) {
        out.put(type, own);
        continue;
      }
      int smallest = Integer.MAX_VALUE;
      Set<String> winners = new TreeSet<>(); // sets of the smallest size
      Set<String> storages = new TreeSet<>();
      for (String set : catalog.setsOf(type)) {
        if (!config.bindSet().containsKey(set)) {
          continue;
        }
        int size = catalog.members(set).size();
        if (size < smallest) {
          smallest = size;
          winners.clear();
          storages.clear();
        }
        if (size == smallest) {
          winners.add(set);
          storages.add(config.bindSet().get(set));
        }
      }
      if (storages.size() > 1) {
        throw new DaemonException(
            "[entities.bind_set]: the type '"
                + type
                + "' is in the sets "
                + winners
                + ", which are equally small and bound to different storages "
                + storages
                + "; bind the type itself in [entities.bind]");
      }
      if (!storages.isEmpty()) {
        out.put(type, storages.iterator().next());
      }
    }
    return out;
  }
}
