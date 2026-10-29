// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Event;
import it.uhde.praksis.capability.EventSource;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ModuleCallsTest {
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
        public Optional<Event> event() {
          return Optional.empty();
        }
      };

  /** Remembers the context class loader that it was called with. */
  private static final class Probe implements Capability {
    final AtomicReference<ClassLoader> seen = new AtomicReference<>();
    boolean fail;

    @Override
    public String name() {
      return "probe";
    }

    @Override
    public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
        throws CapabilityException {
      seen.set(Thread.currentThread().getContextClassLoader());
      if (fail) {
        throw new CapabilityException("failed", false);
      }
      return Map.of();
    }
  }

  /** The same for an event source. */
  private static final class SourceProbe implements EventSource {
    final AtomicReference<ClassLoader> seen = new AtomicReference<>();

    @Override
    public String name() {
      return "probe.source";
    }

    @Override
    public void start(it.uhde.praksis.capability.EventSink sink) {
      seen.set(Thread.currentThread().getContextClassLoader());
    }

    @Override
    public void stop() {}
  }

  @Test
  void aModuleIsCalledWithItsOwnClassLoaderAsTheContextAndTheOldOneComesBack() throws Exception {
    ClassLoader mine = new URLClassLoader(new URL[0], getClass().getClassLoader());
    ClassLoader before = Thread.currentThread().getContextClassLoader();
    Thread.currentThread().setContextClassLoader(mine);
    try {
      Probe probe = new Probe();

      ModuleCalls.invoke(probe, CONTEXT, Map.of());
      assertThat(probe.seen.get()).isSameAs(Probe.class.getClassLoader());
      assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(mine);

      SourceProbe source = new SourceProbe();
      ModuleCalls.start(source, e -> {});
      assertThat(source.seen.get()).isSameAs(SourceProbe.class.getClassLoader());
      assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(mine);

      // and also when the module fails
      probe.fail = true;
      assertThatThrownBy(() -> ModuleCalls.invoke(probe, CONTEXT, Map.of()))
          .isInstanceOf(CapabilityException.class);
      assertThat(Thread.currentThread().getContextClassLoader()).isSameAs(mine);
    } finally {
      Thread.currentThread().setContextClassLoader(before);
    }
  }
}
