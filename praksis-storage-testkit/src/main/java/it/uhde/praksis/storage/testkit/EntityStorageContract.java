// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.storage.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.Filter;
import it.uhde.praksis.capability.Query;
import it.uhde.praksis.capability.StorageType;
import it.uhde.praksis.capability.StoredEntity;
import it.uhde.praksis.capability.ValueType;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What every {@link EntityStorage} must do. A backend extends this class in its own tests and says
 * how to open its storage; the same tests then hold for the SQLite database, the files and the JDBC
 * databases.
 *
 * <p>{@link #open} must return a new, opened storage on the same data each time it is called with
 * the same directory, so that the tests can close a storage and open it again.
 */
public abstract class EntityStorageContract {
  /** The type of the tests: one field of each kind of value. */
  public static final StorageType PERSON =
      new StorageType(
          "person",
          List.of(
              new StorageType.Field("name", ValueType.STRING, true, true),
              new StorageType.Field("age", ValueType.INT, false, false),
              new StorageType.Field("score", ValueType.DOUBLE, false, false),
              new StorageType.Field("vip", ValueType.BOOL, false, false),
              new StorageType.Field("met", ValueType.TIMESTAMP, false, false),
              new StorageType.Field("photo", ValueType.BYTES, false, false),
              new StorageType.Field("tags", ValueType.LIST, false, false),
              new StorageType.Field("extra", ValueType.MAP, false, false),
              new StorageType.Field("note", ValueType.STRING, false, false)));

  /** A second type, to see that types do not mix. */
  public static final StorageType PET =
      new StorageType("pet", List.of(new StorageType.Field("name", ValueType.STRING, false, true)));

  @TempDir protected Path dir;

  private final List<EntityStorage> opened = new ArrayList<>();

  /** Opens a storage for the two types of the tests in the directory. */
  protected abstract EntityStorage open(Path dir, List<StorageType> types) throws Exception;

  protected final EntityStorage storage() throws Exception {
    EntityStorage s = open(dir, List.of(PERSON, PET));
    opened.add(s);
    return s;
  }

  @AfterEach
  void closeAll() {
    opened.forEach(EntityStorage::close);
  }

  private static final Instant T0 = Instant.parse("2026-11-01T10:00:00.000Z");

  private static StoredEntity person(String id, int second, Map<String, Object> fields) {
    Instant t = T0.plusSeconds(second);
    return new StoredEntity(id, t, t, "user", "user", fields);
  }

  private static Map<String, Object> fields(Object... kv) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) {
      m.put((String) kv[i], kv[i + 1]);
    }
    return m;
  }

  private static List<String> ids(EntityStorage.Page page) {
    return page.items().stream().map(StoredEntity::id).toList();
  }

  private void fill(EntityStorage s) throws Exception {
    s.insert("person", person("a", 1, fields("name", "Mei", "age", 41L, "vip", true, "note", "x")));
    s.insert("person", person("b", 2, fields("name", "Meike", "age", 17L, "vip", false)));
    s.insert("person", person("c", 3, fields("name", "Ole", "age", 65L, "score", 2.5)));
    s.insert("person", person("d", 4, fields("name", "Ida")));
  }

  private static Query where(Filter f) {
    return new Query(Optional.of(f), "id", false, 100, 0);
  }

  @Test
  public void everyKindOfValueComesBackAsItWasKept() throws Exception {
    EntityStorage s = storage();
    Instant met = Instant.parse("2026-01-02T03:04:05.678Z");
    StoredEntity in =
        new StoredEntity(
            "p1",
            Instant.parse("2026-11-01T10:00:00.123Z"),
            Instant.parse("2026-11-01T10:00:01.456Z"),
            "rule:mail.10-spam",
            "user",
            fields(
                "name",
                "Mei \"quoted\", with; separators\nand a line",
                "age",
                41L,
                "score",
                1.5,
                "vip",
                true,
                "met",
                met,
                "photo",
                new byte[] {0, 1, 2, (byte) 255},
                "tags",
                List.of("a", "b"),
                "extra",
                Map.of("k", "v", "n", List.of("p", "q"), "b", true)));
    s.insert("person", in);
    StoredEntity out = s.get("person", "p1").orElseThrow();
    assertThat(out.id()).isEqualTo("p1");
    assertThat(out.created()).isEqualTo(in.created());
    assertThat(out.updated()).isEqualTo(in.updated());
    assertThat(out.createdBy()).isEqualTo("rule:mail.10-spam");
    assertThat(out.updatedBy()).isEqualTo("user");
    assertThat(out.fields().get("name")).isEqualTo(in.fields().get("name"));
    assertThat(out.fields().get("age")).isEqualTo(41L);
    assertThat(out.fields().get("score")).isEqualTo(1.5);
    assertThat(out.fields().get("vip")).isEqualTo(true);
    assertThat(out.fields().get("met")).isEqualTo(met);
    assertThat((byte[]) out.fields().get("photo")).containsExactly(0, 1, 2, (byte) 255);
    assertThat(out.fields().get("tags")).isEqualTo(List.of("a", "b"));
    assertThat(out.fields().get("extra"))
        .isEqualTo(Map.of("k", "v", "n", List.of("p", "q"), "b", true));
    assertThat(out.fields()).doesNotContainKey("note");
    assertThat(s.get("person", "nope")).isEmpty();
    assertThat(s.get("pet", "p1")).isEmpty();
  }

  @Test
  public void aWholeNumberStaysAnIntegerAndADoubleStaysADouble() throws Exception {
    EntityStorage s = storage();
    s.insert("person", person("a", 1, fields("name", "A", "age", 3L, "score", 3.0)));
    StoredEntity out = s.get("person", "a").orElseThrow();
    assertThat(out.fields().get("age")).isInstanceOf(Long.class).isEqualTo(3L);
    assertThat(out.fields().get("score")).isInstanceOf(Double.class).isEqualTo(3.0);
  }

  @Test
  public void replaceChangesAndDeleteRemoves() throws Exception {
    EntityStorage s = storage();
    s.insert("person", person("a", 1, fields("name", "Mei", "age", 41L)));
    s.replace(
        "person",
        new StoredEntity(
            "a", T0.plusSeconds(1), T0.plusSeconds(9), "user", "rule:x", fields("name", "Mei2")));
    StoredEntity out = s.get("person", "a").orElseThrow();
    assertThat(out.fields()).containsOnlyKeys("name").containsEntry("name", "Mei2");
    assertThat(out.updatedBy()).isEqualTo("rule:x");
    assertThat(out.updated()).isEqualTo(T0.plusSeconds(9));
    assertThat(s.delete("person", "a")).isTrue();
    assertThat(s.delete("person", "a")).isFalse();
    assertThat(s.get("person", "a")).isEmpty();
    assertThat(s.query("person", Query.all()).total()).isZero();
  }

  @Test
  public void typesDoNotMix() throws Exception {
    EntityStorage s = storage();
    s.insert("person", person("x", 1, fields("name", "A")));
    s.insert("pet", person("x", 1, fields("name", "Rex")));
    assertThat(s.get("person", "x").orElseThrow().fields()).containsEntry("name", "A");
    assertThat(s.get("pet", "x").orElseThrow().fields()).containsEntry("name", "Rex");
    assertThat(s.delete("pet", "x")).isTrue();
    assertThat(s.get("person", "x")).isPresent();
    assertThat(s.query("pet", Query.all()).total()).isZero();
  }

  @Test
  public void aUniqueValueIsFoundWhileItBelongsToAnEntity() throws Exception {
    EntityStorage s = storage();
    fill(s);
    assertThat(s.findUnique("person", "name", "Ole")).contains("c");
    assertThat(s.findUnique("person", "name", "Nobody")).isEmpty();
    s.replace("person", person("c", 3, fields("name", "Olaf")));
    assertThat(s.findUnique("person", "name", "Ole")).isEmpty();
    assertThat(s.findUnique("person", "name", "Olaf")).contains("c");
    s.delete("person", "c");
    assertThat(s.findUnique("person", "name", "Olaf")).isEmpty();
  }

  @Test
  public void queriesUseTheFilterVocabulary() throws Exception {
    EntityStorage s = storage();
    fill(s);
    assertThat(ids(s.query("person", where(new Filter.Equals("name", "Ole")))))
        .containsExactly("c");
    assertThat(ids(s.query("person", where(new Filter.Equals("age", 41L))))).containsExactly("a");
    assertThat(ids(s.query("person", where(new Filter.Equals("vip", true))))).containsExactly("a");
    assertThat(ids(s.query("person", where(new Filter.Equals("vip", false))))).containsExactly("b");
    assertThat(ids(s.query("person", where(new Filter.Equals("score", 2.5))))).containsExactly("c");
    assertThat(ids(s.query("person", where(new Filter.OneOf("name", List.of("Ida", "Ole"))))))
        .containsExactly("c", "d");
    assertThat(ids(s.query("person", where(new Filter.Prefix("name", "Mei")))))
        .containsExactly("a", "b");
    assertThat(ids(s.query("person", where(new Filter.Range("age", null, 18L, 65L, null)))))
        .containsExactly("a");
    assertThat(ids(s.query("person", where(new Filter.Range("age", 17L, null, null, 65L)))))
        .containsExactly("a", "c");
    assertThat(
            ids(
                s.query(
                    "person",
                    where(
                        new Filter.And(
                            List.of(
                                new Filter.Prefix("name", "Me"),
                                new Filter.Range("age", null, null, null, 20L)))))))
        .containsExactly("b");
    assertThat(
            ids(
                s.query(
                    "person",
                    where(
                        new Filter.Or(
                            List.of(
                                new Filter.Equals("name", "Ida"),
                                new Filter.Equals("name", "Ole")))))))
        .containsExactly("c", "d");
    // a missing value matches nothing, but it is "not equal"
    assertThat(
            ids(
                s.query(
                    "person",
                    where(new Filter.Not(new Filter.Range("age", null, 18L, null, null))))))
        .containsExactly("b", "d");
    // the built-in fields
    assertThat(ids(s.query("person", where(new Filter.Equals("id", "b"))))).containsExactly("b");
    assertThat(
            ids(
                s.query(
                    "person",
                    where(new Filter.Range("created", null, null, T0.plusSeconds(3), null)))))
        .containsExactly("a", "b");
    assertThat(ids(s.query("person", where(new Filter.Equals("created_by", "user")))))
        .containsExactly("a", "b", "c", "d");
  }

  @Test
  public void queriesSortAndPage() throws Exception {
    EntityStorage s = storage();
    fill(s);
    assertThat(ids(s.query("person", Query.all()))).containsExactly("a", "b", "c", "d");
    assertThat(ids(s.query("person", new Query(Optional.empty(), "name", false, 100, 0))))
        .containsExactly("d", "a", "b", "c"); // Ida, Mei, Meike, Ole
    EntityStorage.Page page = s.query("person", new Query(Optional.empty(), "name", true, 2, 1));
    assertThat(ids(page)).containsExactly("b", "a");
    assertThat(page.total()).isEqualTo(4);
    // an entity without a value comes first when sorting up, last when sorting down
    assertThat(ids(s.query("person", new Query(Optional.empty(), "age", false, 100, 0))))
        .containsExactly("d", "b", "a", "c");
    assertThat(ids(s.query("person", new Query(Optional.empty(), "age", true, 100, 0))))
        .containsExactly("c", "a", "b", "d");
    assertThat(ids(s.query("person", new Query(Optional.empty(), "created", true, 100, 0))))
        .containsExactly("d", "c", "b", "a");
    assertThat(ids(s.query("person", new Query(Optional.empty(), "id", false, 100, 3))))
        .containsExactly("d");
    assertThat(ids(s.query("person", new Query(Optional.empty(), "id", false, 100, 9)))).isEmpty();
    // the total counts what matches, not the page
    assertThat(
            s.query(
                    "person",
                    new Query(Optional.of(new Filter.Prefix("name", "Me")), "id", false, 1, 0))
                .total())
        .isEqualTo(2);
  }

  @Test
  public void manyEntitiesArePagedWithoutLoss() throws Exception {
    EntityStorage s = storage();
    for (int i = 0; i < 250; i++) {
      s.insert(
          "person",
          person(String.format("id%03d", i), i, fields("name", "N" + i, "age", (long) i)));
    }
    List<String> seen = new ArrayList<>();
    for (int offset = 0; offset < 250; offset += 100) {
      seen.addAll(ids(s.query("person", new Query(Optional.empty(), "id", false, 100, offset))));
    }
    assertThat(seen).hasSize(250).doesNotHaveDuplicates();
    assertThat(s.query("person", Query.all()).total()).isEqualTo(250);
  }

  @Test
  public void whatWasKeptIsThereAfterClosingAndOpeningAgain() throws Exception {
    EntityStorage first = storage();
    fill(first);
    first.close();
    EntityStorage second = storage();
    assertThat(ids(second.query("person", Query.all()))).containsExactly("a", "b", "c", "d");
    assertThat(second.get("person", "c").orElseThrow().fields()).containsEntry("name", "Ole");
    assertThat(second.findUnique("person", "name", "Ida")).contains("d");
  }

  @Test
  public void anEmptyTextIsNotAMissingValue() throws Exception {
    EntityStorage s = storage();
    s.insert("person", person("a", 1, fields("name", "A", "note", "")));
    s.insert("person", person("b", 2, fields("name", "B")));
    assertThat(s.get("person", "a").orElseThrow().fields()).containsEntry("note", "");
    assertThat(s.get("person", "b").orElseThrow().fields()).doesNotContainKey("note");
    assertThat(ids(s.query("person", where(new Filter.Equals("note", ""))))).containsExactly("a");
  }

  @Test
  public void textsThatLookLikeOtherThingsStayTexts() throws Exception {
    EntityStorage s = storage();
    List<String> texts =
        List.of(
            "=1+1",
            "+49 30",
            "-5",
            "@mention",
            "'=x",
            "''@y",
            "\tTab",
            " leading",
            "trailing ",
            "a,b;c\"d",
            "line\nbreak\r\nand more",
            "  ",
            "true",
            "123",
            "null",
            "{\"a\":1}",
            "\u00e4\u00f6\u00fc \u2603 \ud83d\ude00");
    for (int i = 0; i < texts.size(); i++) {
      s.insert("person", person("t" + i, i, fields("name", "N" + i, "note", texts.get(i))));
    }
    s.close();
    EntityStorage again = storage();
    for (int i = 0; i < texts.size(); i++) {
      assertThat(again.get("person", "t" + i).orElseThrow().fields())
          .as("text %d", i)
          .containsEntry("note", texts.get(i));
    }
  }

  @Test
  public void atomicallyReturnsWhatTheWorkReturns() throws Exception {
    EntityStorage s = storage();
    String result =
        s.atomically(
            () -> {
              s.insert("person", person("a", 1, fields("name", "A")));
              s.insert("person", person("b", 2, fields("name", "B")));
              return "done";
            });
    assertThat(result).isEqualTo("done");
    assertThat(s.query("person", Query.all()).total()).isEqualTo(2);
  }
}
