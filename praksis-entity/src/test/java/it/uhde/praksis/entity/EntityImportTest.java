// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exporting the entities of a type with everything, and importing them again. */
class EntityImportTest {
  @TempDir Path dir;
  private Store store;
  private EntityService service;
  private final List<Event> events = new ArrayList<>();

  private static final String PERSON =
      """
      format_version = 1
      id = "person"
      title = "Person"
      [[field]]
      name = "name"
      type = "string"
      required = true
      unique = true
      [[field]]
      name = "iban"
      type = "string"
      sensitive = true
      [[field]]
      name = "boss"
      type = "string"
      ref = "person"
      [[field]]
      name = "photo"
      type = "bytes"
      [[field]]
      name = "met"
      type = "timestamp"
      """;

  @BeforeEach
  void open() throws Exception {
    store = Store.open(dir.resolve("praksis.db"));
    List<Problem> problems = new ArrayList<>();
    EntityType person = EntityTypes.parse(PERSON, "person", problems).orElseThrow();
    assertThat(problems).isEmpty();
    service =
        new EntityService(
            store, new EntityCatalog(Map.of("person", person)), Clock.systemUTC(), events::add);
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  private static EntityService.Imported item(String id, String name, Object... kv) {
    Map<String, Object> f = new LinkedHashMap<>();
    f.put("name", name);
    for (int i = 0; i < kv.length; i += 2) {
      f.put((String) kv[i], kv[i + 1]);
    }
    return new EntityService.Imported(
        id,
        Instant.parse("2026-01-01T00:00:00Z"),
        Instant.parse("2026-01-02T00:00:00Z"),
        "rule:old",
        "user",
        f);
  }

  @Test
  void anExportHasEverythingAndAnImportBringsItBack() throws Exception {
    Map<String, Object> mei =
        service.create(
            "person",
            Map.of(
                "name",
                "Mei",
                "iban",
                "DE01",
                "photo",
                new byte[] {1, 2},
                "met",
                Instant.parse("2026-03-04T05:06:07Z")),
            null,
            false);
    service.create("person", Map.of("name", "Ole", "boss", mei.get("id")), null, false);
    EntityService.Dump dump = service.export("person", 0, 100);
    assertThat(dump.total()).isEqualTo(2);
    StoredEntity first = dump.items().get(0);
    assertThat(first.fields()).containsEntry("iban", "DE01"); // the secret is in the export
    assertThat(service.export("person", 1, 1).items()).hasSize(1);

    // restore into an empty service
    Store other = Store.open(dir.resolve("other.db"));
    try {
      EntityService fresh =
          new EntityService(
              other,
              new EntityCatalog(Map.of("person", service.catalog().get("person").orElseThrow())),
              Clock.systemUTC());
      List<EntityService.Imported> items = new ArrayList<>();
      for (StoredEntity e : dump.items()) {
        items.add(
            new EntityService.Imported(
                e.id(),
                e.created(),
                e.updated(),
                e.createdBy(),
                e.updatedBy(),
                new LinkedHashMap<>(e.fields())));
      }
      EntityService.ImportResult result = fresh.importAll("person", items, false, false);
      assertThat(result).isEqualTo(new EntityService.ImportResult(2, 0));
      for (StoredEntity e : dump.items()) {
        StoredEntity back = other_get(fresh, e.id());
        assertThat(back.created()).isEqualTo(e.created());
        assertThat(back.updated()).isEqualTo(e.updated());
        assertThat(back.createdBy()).isEqualTo(e.createdBy());
        assertThat(back.fields()).containsAllEntriesOf(Map.of("name", e.fields().get("name")));
      }
      assertThat(fresh.reveal("person", first.id(), List.of("iban")).orElseThrow())
          .containsEntry("iban", "DE01");
    } finally {
      other.close();
    }
  }

  private static StoredEntity other_get(EntityService s, String id) throws Exception {
    Map<String, Object> m = s.read("person", id).orElseThrow();
    return new StoredEntity(
        id,
        (Instant) m.get("created"),
        (Instant) m.get("updated"),
        (String) m.get("created_by"),
        (String) m.get("updated_by"),
        Map.of("name", m.get("name")));
  }

  @Test
  void animportKeepsIdsTimesAndProvenanceAndEmitsNoEvents() throws Exception {
    service.importAll("person", List.of(item("a", "Mei"), item("b", "Ole")), false, false);
    Map<String, Object> a = service.read("person", "a").orElseThrow();
    assertThat(a)
        .containsEntry("created", Instant.parse("2026-01-01T00:00:00Z"))
        .containsEntry("updated", Instant.parse("2026-01-02T00:00:00Z"))
        .containsEntry("created_by", "rule:old")
        .containsEntry("updated_by", "user");
    assertThat(events).isEmpty();
    // a missing id, time or author is made up
    assertThatThrownBy(
            () ->
                service.importAll(
                    "person",
                    List.of(
                        new EntityService.Imported(
                            "", null, null, null, null, Map.of("name", "X"))),
                    false,
                    false))
        .hasMessageContaining("needs an id");
    service.importAll(
        "person",
        List.of(new EntityService.Imported("c", null, null, null, null, Map.of("name", "Ida"))),
        false,
        false);
    assertThat(service.read("person", "c").orElseThrow()).containsEntry("created_by", "user");
  }

  @Test
  void nothingIsWrittenIfAnyEntityIsWrong() throws Exception {
    service.importAll("person", List.of(item("a", "Mei")), false, false);
    record Bad(List<EntityService.Imported> items, boolean upsert, String message) {}
    for (Bad bad :
        List.of(
            new Bad(
                List.of(item("b", "Ole"), item("a", "Again")),
                false,
                "entity 'a' exists already; use upsert"),
            new Bad(
                List.of(item("b", "Ole"), item("b", "Twice")),
                false,
                "the id b is in the import twice"),
            new Bad(
                List.of(item("b", "Ole"), item("c", "Mei")),
                false,
                "entity 'c': another 'person' already has name = Mei"),
            new Bad(
                List.of(item("b", "Ole"), item("c", "Ole")),
                false,
                "another 'person' already has name = Ole"),
            new Bad(
                List.of(item("b", "Ole"), item("c", "Ida", "boss", "ghost")),
                false,
                "refers to person 'ghost', which does not exist"),
            new Bad(
                List.of(item("b", "Ole"), item("c", "Ida", "colour", "red")),
                false,
                "type 'person' has no field 'colour'"),
            new Bad(
                List.of(
                    item("b", "Ole"),
                    new EntityService.Imported("c", null, null, null, null, Map.of())),
                false,
                "field 'name' is required"),
            new Bad(List.of(item("b", "Ole", "met", "yesterday")), false, "is not a time"))) {
      assertThatThrownBy(() -> service.importAll("person", bad.items(), bad.upsert(), false))
          .as(bad.message())
          .hasMessageContaining(bad.message());
      assertThat(service.query("person", it.uhde.praksis.capability.Query.all()).total())
          .isEqualTo(1);
    }
  }

  @Test
  void upsertReplacesAndAReferenceMayPointInsideTheImport() throws Exception {
    service.importAll("person", List.of(item("a", "Mei")), false, false);
    EntityService.ImportResult r =
        service.importAll(
            "person",
            List.of(
                item("a", "Mei-Ling"),
                item("b", "Ole", "boss", "a"),
                item("c", "Ida", "boss", "d"),
                item("d", "Bo")),
            true,
            false);
    assertThat(r).isEqualTo(new EntityService.ImportResult(3, 1));
    assertThat(service.read("person", "a").orElseThrow()).containsEntry("name", "Mei-Ling");
    // an entity may keep its own unique value in a replacement
    assertThat(service.importAll("person", List.of(item("a", "Mei-Ling")), true, false))
        .isEqualTo(new EntityService.ImportResult(0, 1));
    // two entities may swap a unique value inside one import
    service.importAll("person", List.of(item("a", "Ole2"), item("b", "Mei-Ling")), true, false);
    assertThat(service.read("person", "a").orElseThrow()).containsEntry("name", "Ole2");
  }

  @Test
  void aDryRunChecksAndWritesNothing() throws Exception {
    EntityService.ImportResult r =
        service.importAll("person", List.of(item("a", "Mei")), false, true);
    assertThat(r).isEqualTo(new EntityService.ImportResult(1, 0));
    assertThat(service.read("person", "a")).isEmpty();
    assertThatThrownBy(
            () ->
                service.importAll(
                    "person", List.of(item("a", "Mei", "boss", "ghost")), false, true))
        .hasMessageContaining("which does not exist");
  }

  @Test
  void exportIsBounded() {
    assertThatThrownBy(() -> service.export("person", 0, 0)).hasMessageContaining("limit");
    assertThatThrownBy(() -> service.export("person", -1, 10)).hasMessageContaining("offset");
    assertThatThrownBy(() -> service.export("nope", 0, 10))
        .hasMessageContaining("unknown entity type");
  }
}
