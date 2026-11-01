// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EntityServiceTest {
  private static final String CONTACT =
      """
      format_version = 1
      id = "contact"
      title = "Contact"

      [[field]]
      name = "name"
      type = "string"
      required = true

      [[field]]
      name = "email"
      type = "string"
      unique = true

      [[field]]
      name = "age"
      type = "int"

      [[field]]
      name = "score"
      type = "double"

      [[field]]
      name = "vip"
      type = "bool"

      [[field]]
      name = "met"
      type = "timestamp"

      [[field]]
      name = "photo"
      type = "bytes"

      [[field]]
      name = "tags"
      type = "list"

      [[field]]
      name = "extra"
      type = "map"
      """;

  @TempDir Path dir;
  private Store store;
  private EntityService service;
  private final List<String> topics = new ArrayList<>();
  private final List<Event> queued = new ArrayList<>();

  /** A clock that moves one second with every reading, so that "created" orders the entities. */
  private static final class Ticking extends Clock {
    private Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      now = now.plus(Duration.ofSeconds(1));
      return now;
    }
  }

  @BeforeEach
  void open() throws Exception {
    store = Store.open(dir.resolve("praksis.db"));
    store.onChange(topics::add);
    List<Problem> problems = new ArrayList<>();
    EntityType contact = EntityTypes.parse(CONTACT, "contact", problems).orElseThrow();
    assertThat(problems).isEmpty();
    service =
        new EntityService(
            store, new EntityCatalog(Map.of("contact", contact)), new Ticking(), queued::add);
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  private Map<String, Object> make(String name, String email, Long age) throws EntityException {
    Map<String, Object> v = new java.util.LinkedHashMap<>();
    v.put("name", name);
    if (email != null) {
      v.put("email", email);
    }
    if (age != null) {
      v.put("age", age);
    }
    return service.create("contact", v, null, false);
  }

  private List<String> names(Query q) throws EntityException {
    return service.query("contact", q).items().stream().map(e -> (String) e.get("name")).toList();
  }

  private static Query where(Map<String, Object> filter) throws EntityException {
    EntityType t = EntityTypes.parse(CONTACT, "contact", new ArrayList<>()).orElseThrow();
    return new Query(Optional.of(Filters.parse(t, filter)), "name", false, 100, 0);
  }

  @Test
  void createsAndReadsAllTypes() throws Exception {
    Instant met = Instant.parse("2026-01-02T03:04:05.678Z");
    Map<String, Object> created =
        service.create(
            "contact",
            Map.of(
                "name",
                "Mei",
                "age",
                41,
                "score",
                1,
                "vip",
                true,
                "met",
                met,
                "photo",
                new byte[] {1, 2, 3},
                "tags",
                List.of("a", "b"),
                "extra",
                Map.of("k", "v")),
            null,
            false);
    assertThat(created.get("id")).isInstanceOf(String.class);
    assertThat(created.get("created")).isEqualTo(Instant.parse("2026-11-01T10:00:01Z"));
    Map<String, Object> read = service.read("contact", (String) created.get("id")).orElseThrow();
    assertThat(read).containsEntry("name", "Mei").containsEntry("age", 41L);
    assertThat(read)
        .containsEntry("score", 1.0)
        .containsEntry("vip", true)
        .containsEntry("met", met);
    assertThat((byte[]) read.get("photo")).containsExactly(1, 2, 3);
    assertThat(read)
        .containsEntry("tags", List.of("a", "b"))
        .containsEntry("extra", Map.of("k", "v"));
    assertThat(topics).contains("entities");
    assertThat(service.read("contact", "nope")).isEmpty();
  }

  @Test
  void refusesWhatTheTypeForbids() throws Exception {
    assertThatThrownBy(() -> service.create("person", Map.of(), null, false))
        .hasMessageContaining("unknown entity type 'person'");
    assertThatThrownBy(() -> service.create("contact", Map.of("age", 3), null, false))
        .hasMessageContaining("'name' is required");
    assertThatThrownBy(() -> service.create("contact", Map.of("name", "A", "x", 1), null, false))
        .hasMessageContaining("no field 'x'");
    assertThatThrownBy(() -> service.create("contact", Map.of("name", "A", "id", "i"), null, false))
        .hasMessageContaining("set by Praksis");
    assertThatThrownBy(() -> service.create("contact", Map.of("name", 5), null, false))
        .hasMessageContaining("must be of type string");
    assertThatThrownBy(
            () -> service.create("contact", Map.of("name", "A", "age", 1.5), null, false))
        .hasMessageContaining("must be of type int");
    assertThatThrownBy(
            () -> service.create("contact", Map.of("name", "A", "met", "yesterday"), null, false))
        .hasMessageContaining("is not a time");
  }

  @Test
  void uniqueValuesAreEnforcedOnCreateAndUpdate() throws Exception {
    Map<String, Object> a = make("A", "a@x", null);
    Map<String, Object> b = make("B", "b@x", null);
    assertThatThrownBy(() -> make("C", "a@x", null)).hasMessageContaining("conflict");
    assertThatThrownBy(
            () -> service.update("contact", (String) b.get("id"), Map.of("email", "a@x"), false))
        .hasMessageContaining("conflict");
    service.update("contact", (String) a.get("id"), Map.of("email", "a2@x"), false);
    make("D", "a@x", null);
    assertThat(names(Query.all())).containsExactly("A", "B", "D");
    service.delete("contact", (String) b.get("id"), false);
    make("E", "b@x", null);
  }

  @Test
  void updateChangesAndRemovesFields() throws Exception {
    Map<String, Object> a = make("A", "a@x", 30L);
    String id = (String) a.get("id");
    Map<String, Object> u =
        service
            .update("contact", id, java.util.Collections.singletonMap("age", null), false)
            .orElseThrow();
    assertThat(u).doesNotContainKey("age").containsEntry("email", "a@x");
    assertThat(u.get("created")).isEqualTo(a.get("created"));
    assertThat((Instant) u.get("updated")).isAfter((Instant) a.get("updated"));
    assertThatThrownBy(
            () ->
                service.update(
                    "contact", id, java.util.Collections.singletonMap("name", null), false))
        .hasMessageContaining("'name' is required");
    assertThat(service.update("contact", "nope", Map.of("age", 1), false)).isEmpty();
    assertThat(service.read("contact", id).orElseThrow()).containsEntry("name", "A");
  }

  @Test
  void deleteRemovesTheEntity() throws Exception {
    Map<String, Object> a = make("A", null, null);
    assertThat(service.delete("contact", (String) a.get("id"), false)).isTrue();
    assertThat(service.delete("contact", (String) a.get("id"), false)).isFalse();
    assertThat(names(Query.all())).isEmpty();
  }

  @Test
  void aDryRunChecksEverythingAndChangesNothing() throws Exception {
    Map<String, Object> a = make("A", "a@x", null);
    topics.clear();
    Map<String, Object> would = service.create("contact", Map.of("name", "B"), null, true);
    assertThat(would).containsEntry("name", "B");
    assertThatThrownBy(
            () -> service.create("contact", Map.of("name", "B", "email", "a@x"), null, true))
        .hasMessageContaining("conflict");
    assertThat(service.update("contact", (String) a.get("id"), Map.of("name", "Z"), true))
        .isPresent();
    assertThat(service.delete("contact", (String) a.get("id"), true)).isTrue();
    assertThat(names(Query.all())).containsExactly("A");
    assertThat(service.read("contact", (String) would.get("id"))).isEmpty();
    assertThat(topics).isEmpty();
  }

  @Test
  void aRetryWithTheSameKeyFindsTheEntityItMade() throws Exception {
    Map<String, Object> first = service.create("contact", Map.of("name", "A"), "key-1", false);
    Map<String, Object> again = service.create("contact", Map.of("name", "A"), "key-1", false);
    Map<String, Object> other = service.create("contact", Map.of("name", "A"), "key-2", false);
    assertThat(again).isEqualTo(first);
    assertThat(other.get("id")).isNotEqualTo(first.get("id"));
    assertThat(names(Query.all())).hasSize(2);
  }

  @Test
  void queriesWithTheFilterVocabulary() throws Exception {
    make("Mei", "mei@x", 41L);
    make("Meike", null, 17L);
    make("Ole", "ole@x", 65L);
    make("Ida", null, null);

    assertThat(names(where(Map.of("op", "equals", "field", "name", "value", "Ole"))))
        .containsExactly("Ole");
    assertThat(
            names(where(Map.of("op", "one_of", "field", "name", "values", List.of("Ida", "Ole")))))
        .containsExactly("Ida", "Ole");
    assertThat(names(where(Map.of("op", "prefix", "field", "name", "value", "Mei"))))
        .containsExactly("Mei", "Meike");
    assertThat(names(where(Map.of("op", "range", "field", "age", "gte", 18, "lt", 65))))
        .containsExactly("Mei");
    assertThat(names(where(Map.of("op", "range", "field", "age", "gt", 17))))
        .containsExactly("Mei", "Ole");
    assertThat(
            names(
                where(
                    Map.of(
                        "op",
                        "and",
                        "of",
                        List.of(
                            Map.of("op", "prefix", "field", "name", "value", "Me"),
                            Map.of("op", "range", "field", "age", "lte", 20))))))
        .containsExactly("Meike");
    assertThat(
            names(
                where(
                    Map.of(
                        "op",
                        "or",
                        "of",
                        List.of(
                            Map.of("op", "equals", "field", "name", "value", "Ida"),
                            Map.of("op", "equals", "field", "name", "value", "Ole"))))))
        .containsExactly("Ida", "Ole");
    // a missing value does not match, but it is "not equal"
    assertThat(
            names(
                where(Map.of("op", "not", "of", Map.of("op", "range", "field", "age", "gte", 18)))))
        .containsExactly("Ida", "Meike");
  }

  @Test
  void filtersOnBoolTimestampAndBuiltInFields() throws Exception {
    Instant t1 = Instant.parse("2026-01-01T00:00:00Z");
    Instant t2 = Instant.parse("2026-06-01T00:00:00Z");
    service.create("contact", Map.of("name", "A", "vip", true, "met", t1), null, false);
    service.create("contact", Map.of("name", "B", "vip", false, "met", t2), null, false);
    assertThat(names(where(Map.of("op", "equals", "field", "vip", "value", true))))
        .containsExactly("A");
    assertThat(names(where(Map.of("op", "equals", "field", "vip", "value", false))))
        .containsExactly("B");
    assertThat(names(where(Map.of("op", "range", "field", "met", "gt", "2026-03-01T00:00:00Z"))))
        .containsExactly("B");
    assertThat(
            names(where(Map.of("op", "range", "field", "created", "lt", "2026-11-01T10:00:02Z"))))
        .containsExactly("A");
    String id = (String) service.query("contact", Query.all()).items().get(0).get("id");
    assertThat(names(where(Map.of("op", "equals", "field", "id", "value", id))))
        .containsExactly("A");
  }

  @Test
  void sortsAndPages() throws Exception {
    for (String n : List.of("C", "A", "D", "B")) {
      make(n, null, null);
    }
    assertThat(names(Query.all())).containsExactly("C", "A", "D", "B");
    EntityService.Page page =
        service.query("contact", new Query(Optional.empty(), "name", true, 2, 1));
    assertThat(page.items()).extracting(e -> e.get("name")).containsExactly("C", "B");
    assertThat(page.total()).isEqualTo(4);
    assertThatThrownBy(
            () -> service.query("contact", new Query(Optional.empty(), "photo", false, 10, 0)))
        .hasMessageContaining("cannot sort by 'photo'");
    assertThatThrownBy(
            () -> service.query("contact", new Query(Optional.empty(), "name", false, 0, 0)))
        .hasMessageContaining("limit");
    assertThatThrownBy(
            () -> service.query("contact", new Query(Optional.empty(), "name", false, 10, -1)))
        .hasMessageContaining("offset");
  }

  @Test
  void badFiltersAreRefusedWithAReason() {
    EntityType t = EntityTypes.parse(CONTACT, "contact", new ArrayList<>()).orElseThrow();
    assertThatThrownBy(() -> Filters.parse(t, "x")).hasMessageContaining("must be a map");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("field", "name")))
        .hasMessageContaining("missing 'op'");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "like")))
        .hasMessageContaining("unknown op 'like'");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "equals", "field", "nope", "value", 1)))
        .hasMessageContaining("'nope' is not a field");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "equals", "field", "photo", "value", 1)))
        .hasMessageContaining("'photo' is not a field");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "prefix", "field", "age", "value", "1")))
        .hasMessageContaining("not a text field");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "equals", "field", "age", "value", "x")))
        .hasMessageContaining("must be of type int");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "range", "field", "age")))
        .hasMessageContaining("give gt");
    assertThatThrownBy(() -> Filters.parse(t, Map.of("op", "and", "of", List.of())))
        .hasMessageContaining("list of conditions");
    assertThatThrownBy(
            () -> Filters.parse(t, Map.of("op", "equals", "field", "name", "value", "a", "x", 1)))
        .hasMessageContaining("unknown key 'x'");
    Map<String, Object> deep = Map.of("op", "equals", "field", "name", "value", "a");
    for (int i = 0; i < 10; i++) {
      deep = Map.of("op", "not", "of", deep);
    }
    Map<String, Object> tooDeep = deep;
    assertThatThrownBy(() -> Filters.parse(t, tooDeep)).hasMessageContaining("8 levels");
  }

  @Test
  void everyChangeStoresAndQueuesAnEventWithoutValues() throws Exception {
    Map<String, Object> a = make("A", "a@x", 30L);
    String id = (String) a.get("id");
    service.update("contact", id, Map.of("age", 31, "name", "A2"), false);
    service.delete("contact", id, false);

    assertThat(queued)
        .extracting(Event::type)
        .containsExactly("entity.created", "entity.updated", "entity.deleted");
    Event created = queued.get(0);
    assertThat(created.source()).isEqualTo("entity");
    assertThat(created.target()).isEqualTo("entity:contact:" + id);
    assertThat(created.depth()).isZero();
    assertThat(created.parent()).isEmpty();
    assertThat(created.data())
        .containsEntry("type", "contact")
        .containsEntry("id", id)
        .containsEntry("fields", List.of("name", "email", "age"))
        .doesNotContainKey("entity");
    assertThat(queued.get(1).data()).containsEntry("fields", List.of("name", "age"));
    assertThat(queued.get(2).data()).containsOnlyKeys("type", "id");
    // they are in the store, so that a restart finds them
    assertThat(store.pending())
        .extracting(Event::id)
        .containsExactlyElementsOf(queued.stream().map(Event::id).toList());
  }

  @Test
  void anEventNamesTheEventThatCausedTheChange() throws Exception {
    Event cause =
        new Event(
            "ev-1",
            "entity.created",
            "entity",
            Instant.parse("2026-11-01T10:00:00Z"),
            "entity:contact:x",
            Optional.empty(),
            Optional.empty(),
            2,
            Map.of());
    service.create(
        "contact", Map.of("name", "A"), new EntityService.Call(false, Optional.of(cause), null));
    assertThat(queued.get(0).parent()).contains("ev-1");
    assertThat(queued.get(0).depth()).isEqualTo(3);
  }

  @Test
  void aLoopOfRulesIsStoppedAtTheDepthLimit() throws Exception {
    Event cause =
        new Event(
            "ev-1",
            "entity.created",
            "entity",
            Instant.parse("2026-11-01T10:00:00Z"),
            "entity:contact:x",
            Optional.empty(),
            Optional.empty(),
            EntityService.MAX_EVENT_DEPTH,
            Map.of());
    EntityService.Call call = new EntityService.Call(false, Optional.of(cause), null);
    assertThatThrownBy(() -> service.create("contact", Map.of("name", "A"), call))
        .hasMessageContaining("event depth limit");
    assertThatThrownBy(() -> service.delete("contact", "x", call))
        .hasMessageContaining("event depth limit");
    assertThat(names(Query.all())).isEmpty();
    assertThat(queued).isEmpty();
  }

  @Test
  void anUpdateThatChangesNothingWritesAndEmitsNothing() throws Exception {
    Map<String, Object> a =
        service.create(
            "contact",
            Map.of("name", "A", "tags", List.of(1, 2), "extra", Map.of("b", 1, "a", 2)),
            null,
            false);
    String id = (String) a.get("id");
    queued.clear();
    Map<String, Object> same =
        service
            .update(
                "contact",
                id,
                Map.of("name", "A", "tags", List.of(1L, 2L), "extra", Map.of("a", 2, "b", 1)),
                false)
            .orElseThrow();
    assertThat(same.get("updated")).isEqualTo(a.get("updated"));
    assertThat(queued).isEmpty();
    service.update("contact", id, java.util.Collections.singletonMap("age", null), false);
    assertThat(queued).isEmpty(); // removing what is not there is no change either
    service.update("contact", id, Map.of("tags", List.of(2, 1)), false);
    assertThat(queued).hasSize(1);
    assertThat(queued.get(0).data()).containsEntry("fields", List.of("tags"));
  }

  @Test
  void aRefusedOrDryRunChangeEmitsNothing() throws Exception {
    make("A", "a@x", null);
    queued.clear();
    assertThatThrownBy(() -> make("B", "a@x", null)).hasMessageContaining("conflict");
    service.create("contact", Map.of("name", "C"), null, true);
    assertThat(queued).isEmpty();
    assertThat(store.pending()).hasSize(1); // only the event of A
  }

  @Test
  void aRetryOfACreateEmitsTheEventOnce() throws Exception {
    service.create("contact", Map.of("name", "A"), "key-1", false);
    service.create("contact", Map.of("name", "A"), "key-1", false);
    assertThat(queued).hasSize(1);
  }

  @Test
  void everyEntityKnowsWhoMadeAndLastChangedIt() throws Exception {
    Map<String, Object> a =
        service.create(
            "contact",
            Map.of("name", "A"),
            new EntityService.Call(false, Optional.empty(), null, "rule:mail.10-spam"));
    String id = (String) a.get("id");
    assertThat(a)
        .containsEntry("created_by", "rule:mail.10-spam")
        .containsEntry("updated_by", "rule:mail.10-spam");
    Map<String, Object> b =
        service
            .update(
                "contact",
                id,
                Map.of("name", "B"),
                new EntityService.Call(false, Optional.empty(), null, "user"))
            .orElseThrow();
    assertThat(b)
        .containsEntry("created_by", "rule:mail.10-spam")
        .containsEntry("updated_by", "user");
    // a change that changes nothing is nobody's
    Map<String, Object> same =
        service
            .update(
                "contact",
                id,
                Map.of("name", "B"),
                new EntityService.Call(false, Optional.empty(), null, "rule:x"))
            .orElseThrow();
    assertThat(same).containsEntry("updated_by", "user");
    assertThat(service.read("contact", id).orElseThrow()).containsEntry("updated_by", "user");
    assertThat(
            names(
                where(Map.of("op", "equals", "field", "created_by", "value", "rule:mail.10-spam"))))
        .containsExactly("B");
    assertThatThrownBy(
            () -> service.create("contact", Map.of("name", "A", "created_by", "x"), null, false))
        .hasMessageContaining("set by Praksis");
  }

  private static final String PERSON =
      """
      format_version = 1
      id = "person"
      title = "Person"
      [[field]]
      name = "name"
      type = "string"
      required = true
      [[field]]
      name = "iban"
      type = "string"
      unique = true
      sensitive = true
      """;

  private EntityService people() {
    EntityType person = EntityTypes.parse(PERSON, "person", new ArrayList<>()).orElseThrow();
    return new EntityService(
        store, new EntityCatalog(Map.of("person", person)), new Ticking(), queued::add);
  }

  @Test
  void sensitiveFieldsAreKeptOutOfFiltersSortsAndConflictMessages() throws Exception {
    EntityService people = people();
    Map<String, Object> a =
        people.create("person", Map.of("name", "A", "iban", "DE01"), null, false);
    String id = (String) a.get("id");
    assertThat(people.withoutSensitive("person", a)).containsKey("name").doesNotContainKey("iban");
    assertThat(people.reveal("person", id, List.of("iban")).orElseThrow())
        .containsOnly(Map.entry("iban", "DE01"));
    assertThat(people.reveal("person", "nope", List.of("iban"))).isEmpty();
    assertThatThrownBy(() -> people.reveal("person", id, List.of("name")))
        .hasMessageContaining("not a sensitive field");
    assertThatThrownBy(() -> people.reveal("person", id, List.of("nope")))
        .hasMessageContaining("not a sensitive field");
    assertThatThrownBy(() -> people.reveal("person", id, List.of()))
        .hasMessageContaining("at least one");

    EntityType person = people.catalog().get("person").orElseThrow();
    assertThatThrownBy(
            () -> Filters.parse(person, Map.of("op", "equals", "field", "iban", "value", "DE01")))
        .hasMessageContaining("sensitive and cannot be used in a filter");
    assertThatThrownBy(
            () -> people.query("person", new Query(Optional.empty(), "iban", false, 10, 0)))
        .hasMessageContaining("cannot sort by 'iban'");
    assertThatThrownBy(
            () -> people.create("person", Map.of("name", "B", "iban", "DE01"), null, false))
        .hasMessageContaining("conflict")
        .satisfies(e -> assertThat(e.getMessage()).doesNotContain("DE01"));
  }
}
