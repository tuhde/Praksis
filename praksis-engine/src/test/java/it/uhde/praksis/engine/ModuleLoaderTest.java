// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.expression.Bindings;
import it.uhde.praksis.expression.ExpressionCompiler;
import it.uhde.praksis.expression.Expressions;
import it.uhde.praksis.expression.Scope;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ModuleLoaderTest {
  @TempDir Path dir;

  private Path modules;
  private Path lockfile;

  private static final String MODULE_INFO =
      """
      module it.example.testmod {
        requires it.uhde.praksis.capability;
        provides it.uhde.praksis.capability.Capability with it.example.testmod.Echo;
        provides it.uhde.praksis.capability.EventSource with it.example.testmod.Ticker;
      }
      """;

  private static final String ECHO =
      """
      package it.example.testmod;
      import it.uhde.praksis.capability.*;
      import java.util.Map;
      public class Echo implements Capability {
        private String prefix = "";
        public String name() { return "test.echo"; }
        public void configure(Map<String, Object> settings) throws CapabilityException {
          if (settings.containsKey("fail")) throw new CapabilityException("bad settings", false);
          prefix = String.valueOf(settings.getOrDefault("prefix", ""));
        }
        public Map<String, Object> invoke(CapabilityContext c, Map<String, Object> args) {
          return Map.of("echo", prefix + args.get("value"));
        }
      }
      """;

  private static final String TICKER =
      """
      package it.example.testmod;
      import it.uhde.praksis.capability.*;
      import java.time.Instant;
      import java.util.Map;
      import java.util.Optional;
      public class Ticker implements EventSource {
                public String name() { return "test.ticker"; }
        public void start(EventSink sink) {
          sink.accept(new Event("t1", "test.tick", "test", Instant.EPOCH, "t",
              Optional.empty(), Optional.empty(), 0, Map.of()));
        }
        public void stop() { System.setProperty("testmod.stopped", "true"); }
      }
      """;

  private static final String MODULE_TOML =
      """
      format_version = 1
      api_version = 1
      name = "testmod"
      version = "0.1.0"
      module = "it.example.testmod"
      jdk_modules = ["java.sql"]
      sources = ["test.ticker"]

      [[capability]]
      name = "test.echo"
      effect = "read-only"
      dry_run = true
      params = [{ name = "value", type = "string", required = true }]
      results = [{ name = "echo", type = "string", required = true }]

      [[event]]
      type = "test.tick"
      fields = []
      """;

  /** Compiles the test module and writes it as modules/testmod/testmod.jar plus module.toml. */
  private Path install(String name, String toml) throws Exception {
    Path moduleDir = modules.resolve(name);
    Path src = dir.resolve("src-" + name);
    Path classes = dir.resolve("classes-" + name);
    write(src.resolve("module-info.java"), MODULE_INFO);
    write(src.resolve("it/example/testmod/Echo.java"), ECHO);
    write(src.resolve("it/example/testmod/Ticker.java"), TICKER);
    Files.createDirectories(classes);
    Path api =
        Path.of(Capability.class.getProtectionDomain().getCodeSource().getLocation().toURI());
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "javac").toString());
    command.addAll(List.of("-d", classes.toString(), "--module-path", api.toString()));
    try (Stream<Path> files = Files.walk(src)) {
      files.filter(f -> f.toString().endsWith(".java")).forEach(f -> command.add(f.toString()));
    }
    Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as(output).isZero();

    Files.createDirectories(moduleDir);
    try (OutputStream out = Files.newOutputStream(moduleDir.resolve("testmod.jar"));
        JarOutputStream jar = new JarOutputStream(out);
        Stream<Path> files = Files.walk(classes)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        jar.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
        jar.write(Files.readAllBytes(f));
        jar.closeEntry();
      }
    }
    write(moduleDir.resolve("module.toml"), toml);
    return moduleDir;
  }

  private static void write(Path file, String content) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }

  private void setUp() {
    modules = dir.resolve("modules");
    lockfile = dir.resolve("modules.lock");
  }

  @Test
  void loadsALockedModuleInItsOwnLayer() throws Exception {
    setUp();
    install("testmod", MODULE_TOML);
    ModuleLoader.lock(modules, lockfile);
    List<Event> events = Collections.synchronizedList(new ArrayList<>());
    try (LoadedModules loaded =
        ModuleLoader.load(modules, lockfile, Map.of("testmod", Map.of("prefix", "p-")))) {
      Capability echo = loaded.capabilities().get("test.echo");
      assertThat(echo.getClass().getModule().getLayer()).isNotSameAs(ModuleLayer.boot());
      assertThat(echo.invoke(null, Map.of("value", "x"))).containsEntry("echo", "p-x");
      assertThat(loaded.catalog().capability("test.echo")).isPresent();
      assertThat(loaded.catalog().eventType("test.tick")).isPresent();
      assertThat(loaded.descriptors().get(0).sources()).containsExactly("test.ticker");

      loaded.startSources(events::add);
      assertThat(events).extracting(Event::id).containsExactly("t1");
      loaded.stopSources();
      assertThat(System.getProperty("testmod.stopped")).isEqualTo("true");
    }
  }

  @Test
  void refusesModulesThatAreNotLockedOrHaveChanged() throws Exception {
    setUp();
    Path moduleDir = install("testmod", MODULE_TOML);
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .isInstanceOf(ModuleLoadException.class)
        .hasMessageContaining("not in the lockfile");

    ModuleLoader.lock(modules, lockfile);
    Files.writeString(moduleDir.resolve("extra.jar"), "swapped");
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("has changed since it was locked");

    ModuleLoader.lock(modules, lockfile);
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("extra.jar"); // the fake jar is now locked but is not loadable
  }

  @Test
  void checksDeclarationsAgainstTheServices() throws Exception {
    setUp();
    install("testmod", MODULE_TOML.replace("name = \"test.echo\"", "name = \"test.other\""));
    ModuleLoader.lock(modules, lockfile);
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("declares capability 'test.other' but does not provide it");

    install("testmod", MODULE_TOML.replace("sources = [\"test.ticker\"]", "sources = []"));
    ModuleLoader.lock(modules, lockfile);
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("provides sources [test.ticker] but module.toml declares []");
  }

  @Test
  void reportsMissingJdkModulesBadSettingsAndBadDescriptors() throws Exception {
    setUp();
    install("testmod", MODULE_TOML.replace("[\"java.sql\"]", "[\"java.nope\"]"));
    ModuleLoader.lock(modules, lockfile);
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("needs the JDK module 'java.nope'");

    install("testmod", MODULE_TOML);
    ModuleLoader.lock(modules, lockfile);
    assertThatThrownBy(
            () -> ModuleLoader.load(modules, lockfile, Map.of("testmod", Map.of("fail", true))))
        .hasMessageContaining("rejected its settings: bad settings");

    install("testmod", "format_version = 2\nname = \"testmod\"\nbogus = 1\n");
    assertThatThrownBy(() -> ModuleLoader.lock(modules, lockfile))
        .hasMessageContaining("'format_version' must be 1")
        .hasMessageContaining("unknown key 'bogus'")
        .hasMessageContaining("missing 'version'")
        .hasMessageContaining("'api_version' is required");
  }

  @Test
  void aModuleBuiltForAnotherApiVersionIsRefusedWithAMessageThatSaysWhatToUpdate()
      throws Exception {
    setUp();
    Path newer = install("testmod", MODULE_TOML.replace("api_version = 1", "api_version = 2"));
    ModuleLoader.lock(modules, lockfile);
    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("built for capability API version 2")
        .hasMessageContaining("this Praksis provides version 1")
        .hasMessageContaining("update Praksis")
        .hasMessageContaining(newer.toString());

    install("testmod", MODULE_TOML.replace("api_version = 1", "api_version = 0"));
    assertThatThrownBy(() -> ModuleLoader.lock(modules, lockfile))
        .hasMessageContaining("'api_version' is required");
  }

  @Test
  void noModulesDirectoryMeansNoModules() throws Exception {
    setUp();
    try (LoadedModules loaded = ModuleLoader.load(modules, lockfile, Map.of())) {
      assertThat(loaded.descriptors()).isEmpty();
    }
  }

  @Test
  void loadsTheExpressionImplementationInItsOwnLayer() throws Exception {
    Path jar = codeSource(Expressions.compiler().getClass());
    assumeTrue(Files.isRegularFile(jar), "needs the packaged (shaded) jar, run 'verify'");
    Path libs = dir.resolve("expression");
    Files.createDirectories(libs);
    Files.copy(jar, libs.resolve(jar.getFileName()));
    ExpressionCompiler compiler = ModuleLoader.loadExpressionCompiler(libs);
    assertThat(compiler.getClass().getModule().getLayer()).isNotSameAs(ModuleLayer.boot());
    assertThat(compiler.compile("1 + 2", Scope.empty()).evaluate(Bindings.of(Instant.EPOCH)))
        .isEqualTo(3L);
  }

  private static Path codeSource(Class<?> type) throws URISyntaxException {
    return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI());
  }

  @Test
  void onlyEnabledModulesAreLoadedSoCompetingOnesCanBeInstalledTogether() throws Exception {
    setUp();
    install("testmod", MODULE_TOML);
    install("other", MODULE_TOML.replace("name = \"testmod\"", "name = \"other\""));
    ModuleLoader.lock(modules, lockfile);

    assertThatThrownBy(() -> ModuleLoader.load(modules, lockfile, Map.of()))
        .hasMessageContaining("capability 'test.echo' is provided by two modules");

    try (LoadedModules loaded =
        ModuleLoader.load(modules, lockfile, Map.of(), java.util.Set.of("other"))) {
      assertThat(loaded.descriptors()).extracting(d -> d.name()).containsExactly("other");
      assertThat(loaded.capabilities()).containsOnlyKeys("test.echo");
    }
    // a disabled module is not even checked against the lockfile
    Files.writeString(modules.resolve("testmod").resolve("extra.txt"), "changed");
    try (LoadedModules loaded =
        ModuleLoader.load(modules, lockfile, Map.of(), java.util.Set.of("other"))) {
      assertThat(loaded.descriptors()).hasSize(1);
    }
    assertThatThrownBy(
            () -> ModuleLoader.load(modules, lockfile, Map.of(), java.util.Set.of("nope")))
        .hasMessageContaining("module 'nope' is enabled but not in");
  }
}
