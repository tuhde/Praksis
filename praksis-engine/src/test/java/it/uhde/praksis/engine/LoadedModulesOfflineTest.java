// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import it.uhde.praksis.capability.ModuleDescriptor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LoadedModulesOfflineTest {
  private static final CapabilityContext CONTEXT =
      new CapabilityContext() {
        @Override
        public boolean dryRun() {
          return false;
        }

        @Override
        public String idempotencyKey() {
          return "k";
        }

        @Override
        public java.util.Optional<it.uhde.praksis.capability.Event> event() {
          return java.util.Optional.empty();
        }
      };

  private record Cap(String name, List<String> told) implements Capability {
    @Override
    public void useOffline() {
      told.add(name);
    }

    @Override
    public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args) {
      return Map.of("ran", true);
    }
  }

  private record Source(String name, List<String> told) implements EventSource {
    @Override
    public void useOffline() {
      told.add(name);
    }

    @Override
    public void start(EventSink sink) {}

    @Override
    public void stop() {}
  }

  private static ModuleDescriptor module(
      String name, String capability, String source, boolean net) {
    return new ModuleDescriptor(
        name,
        "1",
        List.of(),
        List.of(
            new CapabilityDescriptor(
                capability, EffectClass.READ_ONLY, true, List.of(), List.of())),
        List.of(),
        List.of(source),
        net ? List.of(capability, source) : List.of());
  }

  @Test
  void aModuleThatNeedsTheNetworkRefusesAndStartsNoSourceAndTheOthersAreToldWhileOfflineIsOff()
      throws Exception {
    List<String> told = new ArrayList<>();
    LoadedModules modules =
        new LoadedModules(
            List.of(
                module("remote", "r.read", "r.source", true),
                module("here", "h.read", "h.source", false)),
            Map.of("r.read", new Cap("r.read", told), "h.read", new Cap("h.read", told)),
            List.of(new Source("r.source", told), new Source("h.source", told)));

    LoadedModules offline = modules.offline();

    assertThatThrownBy(() -> offline.capabilities().get("r.read").invoke(CONTEXT, Map.of()))
        .hasMessageContaining("offline = true")
        .hasMessageContaining("r.read");
    assertThat(offline.capabilities().get("h.read").invoke(CONTEXT, Map.of()))
        .containsEntry("ran", true);
    assertThat(offline.sources()).extracting(EventSource::name).containsExactly("h.source");
    // only what stays is told; the descriptors stay, so rules about them still check
    assertThat(told).containsExactlyInAnyOrder("h.read", "h.source");
    assertThat(offline.descriptors()).hasSize(2);
  }

  /** An entity storage that notes whether it was told. */
  private static final class Store implements it.uhde.praksis.capability.EntityStorage {
    final List<String> told;

    Store(List<String> told) {
      this.told = told;
    }

    @Override
    public String name() {
      return "db";
    }

    @Override
    public void useOffline() {
      told.add("db");
    }

    @Override
    public void open(List<it.uhde.praksis.capability.StorageType> types) {}

    @Override
    public java.util.Optional<it.uhde.praksis.capability.StoredEntity> get(String t, String id) {
      return java.util.Optional.empty();
    }

    @Override
    public void insert(String t, it.uhde.praksis.capability.StoredEntity e) {}

    @Override
    public void replace(String t, it.uhde.praksis.capability.StoredEntity e) {}

    @Override
    public boolean delete(String t, String id) {
      return false;
    }

    @Override
    public Page query(String t, it.uhde.praksis.capability.Query q) {
      return new Page(List.of(), 0);
    }
  }

  @Test
  void anEntityStorageIsToldWhenItIsMadeOfflineUnlessItAlwaysNeedsTheNetwork() {
    List<String> told = new ArrayList<>();
    ModuleDescriptor local =
        new ModuleDescriptor(
            "local", "1", List.of(), List.of(), List.of(), List.of(), List.of(), List.of("db"));
    ModuleDescriptor remote =
        new ModuleDescriptor(
            "remote",
            "1",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of("net"),
            List.of("net"));
    LoadedModules modules =
        new LoadedModules(
            List.of(local, remote),
            Map.of(),
            List.of(),
            Map.of("db", () -> new Store(told), "net", () -> new Store(told)));
    LoadedModules offline = modules.offline();
    assertThat(told).isEmpty(); // told when an instance is made, not before
    assertThat(offline.newBackend("db")).isPresent();
    assertThat(told).containsExactly("db");
    assertThatThrownBy(() -> offline.newBackend("net"))
        .hasMessageContaining("offline = true")
        .hasMessageContaining("'net'");
    assertThat(modules.newBackend("db")).isPresent(); // online: nobody is told
    assertThat(told).containsExactly("db");
  }
}
