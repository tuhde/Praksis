// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Event;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Types that refer to each other: the target must exist, and deleting it does what the field that
 * refers to it says.
 *
 * <pre>
 * author    (name)
 * book      (title, author -> author [restrict], tags: list -> tag [set_null])
 * note      (text, book -> book [cascade])
 * tag       (name)
 * folder    (name, parent -> folder [cascade])        a tree
 * </pre>
 */
class EntityRelationsTest {
  @TempDir Path dir;
  private Store store;
  private EntityService service;
  private final List<Event> events = new ArrayList<>();

  private static String type(String id, String fields) {
    return "format_version = 1\nid = \"" + id + "\"\ntitle = \"" + id + "\"\n" + fields;
  }

  private static final String NAME = "[[field]]\nname = \"name\"\ntype = \"string\"\n";

  private static final List<String[]> TYPES =
      List.of(
          new String[] {"author", NAME},
          new String[] {"tag", NAME},
          new String[] {
            "book",
            "[[field]]\nname = \"title\"\ntype = \"string\"\n"
                + "[[field]]\nname = \"author\"\ntype = \"string\"\nref = \"author\"\n"
                + "[[field]]\nname = \"tags\"\ntype = \"list\"\nref = \"tag\"\non_delete = \"set_null\"\n"
          },
          new String[] {
            "note",
            "[[field]]\nname = \"text\"\ntype = \"string\"\n"
                + "[[field]]\nname = \"book\"\ntype = \"string\"\nref = \"book\"\non_delete = \"cascade\"\nrequired = true\n"
          },
          new String[] {
            "folder",
            NAME
                + "[[field]]\nname = \"parent\"\ntype = \"string\"\nref = \"folder\"\non_delete = \"cascade\"\n"
          });

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
    Map<String, EntityType> types = new LinkedHashMap<>();
    for (String[] t : TYPES) {
      List<Problem> problems = new ArrayList<>();
      EntityType type = EntityTypes.parse(type(t[0], t[1]), t[0], problems).orElseThrow();
      assertThat(problems).isEmpty();
      types.put(t[0], type);
    }
    service = new EntityService(store, new EntityCatalog(types), new Ticking(), events::add);
  }

  @AfterEach
  void close() throws Exception {
    store.close();
  }

  private String make(String type, Object... kv) throws Exception {
    Map<String, Object> v = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      v.put((String) kv[i], kv[i + 1]);
    }
    return (String) service.create(type, v, null, false).get("id");
  }

  private long count(String type) throws Exception {
    return service.query(type, it.uhde.praksis.capability.Query.all()).total();
  }

  @Test
  void aReferenceMustPointAtAnEntityThatExists() throws Exception {
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author);
    assertThat(service.read("book", book).orElseThrow()).containsEntry("author", author);
    assertThatThrownBy(() -> make("book", "title", "T", "author", "nobody"))
        .hasMessageContaining("field 'author' refers to author 'nobody', which does not exist");
    // an id of another type does not do either
    assertThatThrownBy(() -> make("book", "title", "T", "author", book))
        .hasMessageContaining("which does not exist");
    assertThatThrownBy(() -> make("book", "title", "T", "tags", List.of("t-none")))
        .hasMessageContaining("field 'tags' refers to tag 't-none', which does not exist");
    assertThatThrownBy(() -> make("book", "title", "T", "tags", List.of(5L)))
        .hasMessageContaining("field 'tags' must be a list of ids");
    // updating to a missing one is refused, keeping the old one is not checked again
    assertThatThrownBy(() -> service.update("book", book, Map.of("author", "nobody"), false))
        .hasMessageContaining("which does not exist");
    assertThat(service.update("book", book, Map.of("title", "T2"), false)).isPresent();
    String other = make("author", "name", "Bo");
    assertThat(service.update("book", book, Map.of("author", other), false).orElseThrow())
        .containsEntry("author", other);
  }

  @Test
  void restrictRefusesTheDeleteWhileSomethingRefersToTheEntity() throws Exception {
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author);
    assertThatThrownBy(() -> service.delete("author", author, false))
        .hasMessageContaining(
            "cannot delete author "
                + author
                + ": book "
                + book
                + " refers to it in the field 'author'");
    assertThat(count("author")).isEqualTo(1);
    // once nothing refers to it, it can go
    service.delete("book", book, false);
    assertThat(service.delete("author", author, false)).isTrue();
  }

  @Test
  void cascadeDeletesWhatReferToItAndWhatReferToThat() throws Exception {
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author);
    String n1 = make("note", "text", "a", "book", book);
    String n2 = make("note", "text", "b", "book", book);
    String keep = make("book", "title", "Other", "author", author);
    String n3 = make("note", "text", "c", "book", keep);
    events.clear();
    assertThat(service.delete("book", book, false)).isTrue();
    assertThat(service.read("book", book)).isEmpty();
    assertThat(service.read("note", n1)).isEmpty();
    assertThat(service.read("note", n2)).isEmpty();
    assertThat(service.read("note", n3)).isPresent();
    assertThat(service.read("book", keep)).isPresent();
    // every deletion is an event, the entity asked for last
    assertThat(events).extracting(Event::type).containsOnly("entity.deleted");
    assertThat(events).extracting(e -> e.data().get("id")).hasSize(3).last().isEqualTo(book);
  }

  @Test
  void setNullClearsAReferenceAndRemovesAnIdFromAList() throws Exception {
    String t1 = make("tag", "name", "x");
    String t2 = make("tag", "name", "y");
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author, "tags", List.of(t1, t2));
    String other = make("book", "title", "U", "author", author, "tags", List.of(t1));
    events.clear();
    assertThat(service.delete("tag", t1, false)).isTrue();
    assertThat(service.read("book", book).orElseThrow()).containsEntry("tags", List.of(t2));
    assertThat(service.read("book", other).orElseThrow()).containsEntry("tags", List.of());
    assertThat(events)
        .extracting(Event::type)
        .containsExactly("entity.updated", "entity.updated", "entity.deleted");
    assertThat(events.get(0).data()).containsEntry("fields", List.of("tags"));
  }

  @Test
  void aTreeIsDeletedWithItsBranchesAndACycleDoesNotLoop() throws Exception {
    String root = make("folder", "name", "root");
    String a = make("folder", "name", "a", "parent", root);
    String b = make("folder", "name", "b", "parent", a);
    String c = make("folder", "name", "c", "parent", root);
    String free = make("folder", "name", "free");
    assertThat(service.delete("folder", a, false)).isTrue();
    assertThat(service.read("folder", b)).isEmpty();
    assertThat(service.read("folder", root)).isPresent();
    // x -> y -> x, deleted from either end
    String x = make("folder", "name", "x");
    String y = make("folder", "name", "y", "parent", x);
    service.update("folder", x, Map.of("parent", y), false);
    assertThat(service.delete("folder", x, false)).isTrue();
    assertThat(service.read("folder", y)).isEmpty();
    assertThat(service.delete("folder", root, false)).isTrue();
    assertThat(service.read("folder", c)).isEmpty();
    assertThat(service.read("folder", free)).isPresent();
  }

  @Test
  void aDeleteThatNeedsMoreRightsIsRefusedWholeAndNothingIsDone() throws Exception {
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author);
    String note = make("note", "text", "a", "book", book);
    String tag = make("tag", "name", "x");
    String tagged = make("book", "title", "U", "author", author, "tags", List.of(tag));
    events.clear();
    // may delete books, but not notes
    EntityService.Call noNotes =
        new EntityService.Call(
            false,
            Optional.empty(),
            null,
            "rule:r",
            (capability, args) ->
                "note".equals(args.get("type"))
                    ? Optional.of("layer 'mine' has no grant for " + capability + " (type=note)")
                    : Optional.empty());
    assertThatThrownBy(() -> service.delete("book", book, noNotes))
        .hasMessageContaining("deleting book " + book + " would also delete note " + note)
        .hasMessageContaining(
            "which this call may not do: layer 'mine' has no grant for entity.delete (type=note)");
    assertThat(service.read("book", book)).isPresent();
    assertThat(service.read("note", note)).isPresent();
    assertThat(events).isEmpty();

    // may not change books: a set_null of a list would
    EntityService.Call noBooks =
        new EntityService.Call(
            false,
            Optional.empty(),
            null,
            "rule:r",
            (capability, args) ->
                capability.equals("entity.update") && "book".equals(args.get("type"))
                    ? Optional.of("no grant for entity.update (type=book)")
                    : Optional.empty());
    assertThatThrownBy(() -> service.delete("tag", tag, noBooks))
        .hasMessageContaining("deleting tag " + tag + " would also change book " + tagged);
    assertThat(service.read("tag", tag)).isPresent();
    assertThat(service.read("book", tagged).orElseThrow()).containsEntry("tags", List.of(tag));
  }

  @Test
  void aDryRunPlansTheWholeDeleteAndDoesNothing() throws Exception {
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author);
    String note = make("note", "text", "a", "book", book);
    events.clear();
    assertThat(service.delete("book", book, true)).isTrue();
    assertThatThrownBy(() -> service.delete("author", author, true))
        .hasMessageContaining("cannot delete author");
    assertThat(service.read("note", note)).isPresent();
    assertThat(events).isEmpty();
  }

  @Test
  void theRequiredReferenceOfACascadeIsKept() throws Exception {
    String author = make("author", "name", "Ada");
    String book = make("book", "title", "T", "author", author);
    assertThatThrownBy(() -> make("note", "text", "a"))
        .hasMessageContaining("field 'book' is required");
    assertThat(make("note", "text", "a", "book", book)).isNotEmpty();
  }
}
