// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import it.uhde.praksis.capability.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LoadedModulesDataDirectoryTest {
  @TempDir Path root;

  private record Cap(String name, List<Path> given) implements Capability {
    @Override
    public void useDataDirectory(Path directory) {
      given.add(directory);
    }

    @Override
    public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args) {
      return Map.of();
    }
  }

  private record Source(String name, List<Path> given) implements EventSource {
    @Override
    public void useDataDirectory(Path directory) {
      given.add(directory);
    }

    @Override
    public void start(EventSink sink) {}

    @Override
    public void stop() {}
  }

  private static ModuleDescriptor module(String name, String capability, String source) {
    return new ModuleDescriptor(
        name,
        "1",
        List.of(),
        List.of(
            new CapabilityDescriptor(
                capability, EffectClass.READ_ONLY, true, List.of(), List.of())),
        List.of(),
        List.of(source));
  }

  @Test
  void everyModuleGetsADirectoryOfItsOwnThatExistsAndSharesItWithItsParts() throws Exception {
    List<Path> a = new ArrayList<>();
    List<Path> aSource = new ArrayList<>();
    List<Path> b = new ArrayList<>();
    LoadedModules modules =
        new LoadedModules(
            List.of(module("alpha", "a.read", "a.source"), module("beta", "b.read", "b.source")),
            Map.of("a.read", new Cap("a.read", a), "b.read", new Cap("b.read", b)),
            List.of(new Source("a.source", aSource), new Source("b.source", new ArrayList<>())));

    modules.useDataDirectory(root.resolve("data"));

    assertThat(a).containsExactly(root.resolve("data/alpha"));
    assertThat(aSource).containsExactly(root.resolve("data/alpha"));
    assertThat(b).containsExactly(root.resolve("data/beta"));
    assertThat(root.resolve("data/alpha")).isDirectory();
    assertThat(root.resolve("data/beta")).isDirectory();
  }

  @Test
  void aModuleWhoseDirectoryCannotBeMadeIsLeftWithoutOneAndTheOthersAreNot() throws Exception {
    Files.writeString(root.resolve("data"), "a file, not a directory");
    List<Path> a = new ArrayList<>();
    LoadedModules modules =
        new LoadedModules(
            List.of(module("alpha", "a.read", "a.source")),
            Map.of("a.read", new Cap("a.read", a)),
            List.of());

    modules.useDataDirectory(root.resolve("data"));

    assertThat(a).isEmpty();
  }
}
