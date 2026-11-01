// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityCapabilitiesTest {
  @TempDir Path dir;

  private static CapabilityContext context(boolean dryRun, String key) {
    return new CapabilityContext() {
      @Override
      public boolean dryRun() {
        return dryRun;
      }

      @Override
      public String idempotencyKey() {
        return key;
      }

      @Override
      public Optional<Event> event() {
        return Optional.empty();
      }
    };
  }

  @Test
  void theFiveCapabilitiesWorkAndAreDescribed() throws Exception {
    var module = EntityCapabilities.descriptor();
    assertThat(module.capabilities())
        .extracting(c -> c.name())
        .containsExactly(
            "entity.create", "entity.read", "entity.query", "entity.update", "entity.delete");
    assertThat(module.capabilities())
        .allSatisfy(
            c -> {
              assertThat(c.supportsDryRun()).isTrue();
              assertThat(c.params()).anyMatch(p -> p.name().equals("type") && p.scopable());
            });
    assertThat(module.capabilities().get(4).effect()).isEqualTo(EffectClass.DESTRUCTIVE);

    try (Store store = Store.open(dir.resolve("praksis.db"))) {
      EntityType t =
          EntityTypes.parse(
                  "format_version = 1\nid = \"note\"\ntitle = \"Note\"\n"
                      + "[[field]]\nname = \"text\"\ntype = \"string\"\nrequired = true\n",
                  "note",
                  new ArrayList<>())
              .orElseThrow();
      var caps =
          EntityCapabilities.of(
                  new EntityService(store, new EntityCatalog(Map.of("note", t)), Clock.systemUTC()))
              .capabilities();
      assertThat(caps).containsOnlyKeys(module.capabilities().stream().map(c -> c.name()).toList());

      Map<String, Object> created =
          caps.get("entity.create")
              .invoke(context(false, "k"), Map.of("type", "note", "values", Map.of("text", "hi")));
      String id = (String) created.get("id");
      assertThat(
              caps.get("entity.read").invoke(context(false, "k"), Map.of("type", "note", "id", id)))
          .containsEntry("found", true);
      Map<String, Object> q =
          caps.get("entity.query")
              .invoke(
                  context(false, "k"),
                  Map.of(
                      "type",
                      "note",
                      "filter",
                      Map.of("op", "prefix", "field", "text", "value", "h")));
      assertThat(q).containsEntry("count", 1L).containsEntry("total", 1L);
      assertThat(
              caps.get("entity.update")
                  .invoke(
                      context(false, "k"),
                      Map.of("type", "note", "id", id, "values", Map.of("text", "ho"))))
          .containsEntry("found", true);
      assertThat(
              caps.get("entity.delete")
                  .invoke(context(true, "k"), Map.of("type", "note", "id", id)))
          .containsEntry("deleted", true);
      assertThat(
              caps.get("entity.read").invoke(context(false, "k"), Map.of("type", "note", "id", id)))
          .containsEntry("found", true);
      assertThat(
              caps.get("entity.delete")
                  .invoke(context(false, "k"), Map.of("type", "note", "id", id)))
          .containsEntry("deleted", true);
      assertThat(
              caps.get("entity.read").invoke(context(false, "k"), Map.of("type", "note", "id", id)))
          .containsEntry("found", false);
      Capability create = caps.get("entity.create");
      assertThatThrownBy(
              () -> create.invoke(context(false, "k2"), Map.of("type", "note", "values", Map.of())))
          .isInstanceOf(CapabilityException.class)
          .hasMessageContaining("'text' is required");
      List<?> none =
          (List<?>)
              caps.get("entity.query")
                  .invoke(context(false, "k"), Map.of("type", "note"))
                  .get("items");
      assertThat(none).isEmpty();
    }
  }
}
