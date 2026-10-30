// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleInstallerTest {
  @TempDir Path dir;

  private static String toml(String name, String version) {
    return "format_version = 1\nname = \"%s\"\nversion = \"%s\"\nmodule = \"it.example.%s\"\n"
        .formatted(name, version, name);
  }

  /** A zip with the given entries in order; the values are the contents. */
  private Path archive(String file, Map<String, String> entries) throws IOException {
    Path zip = dir.resolve(file);
    try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
      for (var e : entries.entrySet()) {
        out.putNextEntry(new ZipEntry(e.getKey()));
        out.write(e.getValue().getBytes());
        out.closeEntry();
      }
    }
    return zip;
  }

  private Path good(String name, String version) throws IOException {
    Map<String, String> files = new LinkedHashMap<>();
    files.put("module.toml", toml(name, version));
    files.put(name + ".jar", "not really a jar " + version);
    return archive(name + "-" + version + ".praksis-module", files);
  }

  private Path root() {
    return dir.resolve("home/modules");
  }

  private Path lock() {
    return dir.resolve("home/modules.lock");
  }

  @Test
  void aModuleIsUnpackedLockedAndListed() throws Exception {
    var done = ModuleInstaller.install(good("demo", "1.0.0"), root(), lock(), false);

    assertThat(done.name()).isEqualTo("demo");
    assertThat(done.version()).isEqualTo("1.0.0");
    assertThat(root().resolve("demo/demo.jar")).exists();
    assertThat(root().resolve("demo/module.toml")).exists();
    assertThat(Files.readString(lock())).contains("demo").contains(done.sha256());
    assertThat(ModuleInstaller.list(List.of(root()), lock()))
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.name()).isEqualTo("demo");
              assertThat(e.locked()).isTrue();
              assertThat(e.hidden()).isFalse();
            });
    // nothing is left behind in the modules directory
    try (var files = Files.list(root())) {
      assertThat(files.map(p -> p.getFileName().toString())).containsExactly("demo");
    }
  }

  @Test
  void aModuleThatIsThereNeedsReplaceAndAnUpdateChangesTheHash() throws Exception {
    var first = ModuleInstaller.install(good("demo", "1.0.0"), root(), lock(), false);

    assertThatThrownBy(() -> ModuleInstaller.install(good("demo", "1.0.1"), root(), lock(), false))
        .hasMessageContaining("already installed")
        .hasMessageContaining("--replace");
    assertThat(Files.readString(root().resolve("demo/demo.jar"))).contains("1.0.0");

    var second = ModuleInstaller.install(good("demo", "1.0.1"), root(), lock(), true);
    assertThat(second.version()).isEqualTo("1.0.1");
    assertThat(second.sha256()).isNotEqualTo(first.sha256());
    assertThat(Files.readString(root().resolve("demo/demo.jar"))).contains("1.0.1");
    assertThat(Files.readString(lock())).contains(second.sha256()).doesNotContain(first.sha256());
  }

  @Test
  void pathsDirectoriesAndOtherFilesAreRefusedAndNothingIsInstalled() throws Exception {
    for (String bad :
        List.of("../evil.jar", "/etc/evil.jar", "sub/evil.jar", "a\\b.jar", "run.sh", "x.class")) {
      Map<String, String> files = new LinkedHashMap<>();
      files.put("module.toml", toml("demo", "1.0.0"));
      files.put(bad, "x");
      assertThatThrownBy(
              () -> ModuleInstaller.install(archive("bad.zip", files), root(), lock(), false))
          .as(bad)
          .isInstanceOf(ModuleLoadException.class);
    }
    assertThat(Files.exists(root().resolve("demo"))).isFalse();
    assertThat(dir.resolve("evil.jar")).doesNotExist();
    assertThat(Files.exists(lock())).isFalse();
    // only the temporary directories of the attempts may have been there, and they are gone
    try (var files = Files.list(root())) {
      assertThat(files).isEmpty();
    }
  }

  @Test
  void anArchiveNeedsAModuleTomlAJarAndASaneName() throws Exception {
    assertThatThrownBy(
            () ->
                ModuleInstaller.install(
                    archive("a.zip", Map.of("demo.jar", "x")), root(), lock(), false))
        .hasMessageContaining("no module.toml");
    assertThatThrownBy(
            () ->
                ModuleInstaller.install(
                    archive("b.zip", Map.of("module.toml", toml("demo", "1"))),
                    root(),
                    lock(),
                    false))
        .hasMessageContaining("no jar");
    Map<String, String> evil = new LinkedHashMap<>();
    evil.put("module.toml", toml("Evil..Name", "1"));
    evil.put("x.jar", "x");
    assertThatThrownBy(() -> ModuleInstaller.install(archive("c.zip", evil), root(), lock(), false))
        .isInstanceOf(ModuleLoadException.class);
    assertThatThrownBy(
            () -> ModuleInstaller.install(dir.resolve("nope.zip"), root(), lock(), false))
        .hasMessageContaining("no such file");
    Path text = dir.resolve("text.zip");
    Files.writeString(text, "this is not a zip file");
    assertThatThrownBy(() -> ModuleInstaller.install(text, root(), lock(), false))
        .isInstanceOf(ModuleLoadException.class);
  }

  @Test
  void aModuleOfTheHomeHidesOneOfTheSameNameAndRemovingItBringsTheOtherBack() throws Exception {
    Path image = dir.resolve("image/modules");
    Files.createDirectories(image.resolve("demo"));
    Files.writeString(image.resolve("demo/module.toml"), toml("demo", "0.1.0"));
    Files.writeString(image.resolve("demo/demo.jar"), "shipped");
    ModuleInstaller.install(good("demo", "2.0.0"), root(), lock(), false);

    List<ModuleInstaller.Entry> both = ModuleInstaller.list(List.of(root(), image), lock());
    assertThat(both).extracting(ModuleInstaller.Entry::version).containsExactly("2.0.0", "0.1.0");
    assertThat(both).extracting(ModuleInstaller.Entry::hidden).containsExactly(false, true);
    // the one that ships is not in the lockfile, the installed one is: only that was accepted
    assertThat(both).extracting(ModuleInstaller.Entry::locked).containsExactly(true, false);
    // the loader sees the module of the home only
    assertThat(ModuleLoader.moduleNames(List.of(root(), image), java.util.Set.of()))
        .containsExactly("demo");

    ModuleInstaller.remove("demo", root(), lock());
    assertThat(ModuleInstaller.list(List.of(root(), image), lock()))
        .singleElement()
        .satisfies(e -> assertThat(e.version()).isEqualTo("0.1.0"));
    assertThat(Files.readString(lock())).doesNotContain("demo");
    assertThatThrownBy(() -> ModuleInstaller.remove("demo", root(), lock()))
        .hasMessageContaining("not installed");
    assertThatThrownBy(() -> ModuleInstaller.remove("../x", root(), lock()))
        .hasMessageContaining("not a module name");
  }
}
