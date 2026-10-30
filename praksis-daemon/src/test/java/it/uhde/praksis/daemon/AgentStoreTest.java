// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentStoreTest {
  @TempDir Path dir;

  @Test
  void aStagedChangeIsListedReadBackAndDropped() throws Exception {
    AgentStore store = new AgentStore(dir);
    Map<String, String> files = new LinkedHashMap<>();
    files.put("tests/r1.toml", "format_version = 1\n");
    files.put("tests/r2.toml", "format_version = 1\n# two\n");

    AgentStore.Staged staged = store.stage("mine", "r1", "because", "abc123", files);

    assertThat(staged.id()).matches("\\d{13}-[0-9a-f]{6}");
    assertThat(store.list()).extracting(AgentStore.Staged::id).containsExactly(staged.id());
    AgentStore.Staged read = store.find(staged.id()).orElseThrow();
    assertThat(read).isEqualTo(staged);
    assertThat(read.files()).containsExactly("tests/r1.toml", "tests/r2.toml");
    assertThat(store.files(read)).isEqualTo(files);
    assertThat(store.find("nope")).isEmpty();
    store.drop(read);
    assertThat(store.list()).isEmpty();
    assertThat(dir.resolve("proposals/mine/" + staged.id())).doesNotExist();
  }

  @Test
  void theNewestChangeIsFirstAndTooManyAreRefused() throws Exception {
    AgentStore store = new AgentStore(dir);
    AgentStore.Staged first = store.stage("mine", "r1", "", "", Map.of("tests/a.toml", "x"));
    AgentStore.Staged second = store.stage("mine", "r2", "", "", Map.of("tests/b.toml", "x"));
    assertThat(store.list())
        .extracting(AgentStore.Staged::id)
        .containsExactly(second.id(), first.id());

    for (int i = 2; i < AgentStore.MAX_STAGED; i++) {
      store.stage("mine", "r" + i, "", "", Map.of("tests/c" + i + ".toml", "x"));
    }
    assertThatThrownBy(() -> store.stage("mine", "rx", "", "", Map.of("tests/z.toml", "x")))
        .hasMessageContaining("staged changes already");
  }

  @Test
  void notesComeNewestFirstAreBoundedAndPrivate() throws Exception {
    AgentStore store = new AgentStore(dir);
    for (int i = 0; i < AgentStore.MAX_NOTES + 5; i++) {
      store.saveNote("note " + i, "text " + i);
    }

    assertThat(Files.list(dir.resolve("notes")).count()).isEqualTo(AgentStore.MAX_NOTES);
    var newest = store.notes(3);
    assertThat(newest)
        .extracting(AgentStore.Note::title)
        .containsExactly("note 204", "note 203", "note 202");
    assertThat(newest.get(0).text()).isEqualTo("text 204");
    assertThat(
            java.nio.file.Files.getPosixFilePermissions(
                dir.resolve("notes").resolve(newest.get(0).id() + ".json")))
        .containsExactlyInAnyOrder(
            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
  }
}
