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
}
