// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The modules found in the modules directory, with their capabilities and event sources. */
public final class LoadedModules implements AutoCloseable {
  private final List<ModuleDescriptor> descriptors;
  private final Map<String, Capability> capabilities;
  private final List<EventSource> sources;
  private final List<EventSource> started = new ArrayList<>();

  LoadedModules(
      List<ModuleDescriptor> descriptors,
      Map<String, Capability> capabilities,
      List<EventSource> sources) {
    this.descriptors = List.copyOf(descriptors);
    this.capabilities = Map.copyOf(capabilities);
    this.sources = List.copyOf(sources);
  }

  public List<ModuleDescriptor> descriptors() {
    return descriptors;
  }

  /** The descriptors by capability and event type name, for validating rulesets. */
  public ModuleCatalog catalog() {
    return new ModuleCatalog(descriptors);
  }

  /** The capability implementations by name. */
  public Map<String, Capability> capabilities() {
    return capabilities;
  }

  public List<EventSource> sources() {
    return sources;
  }

  /**
   * Gives every capability and event source its way to get secrets. The function is asked once per
   * part, with the name of the capability or event source, so the answer can depend on who asks.
   */
  public void useCredentials(java.util.function.Function<String, Credentials> perComponent) {
    capabilities.forEach((name, capability) -> capability.useCredentials(perComponent.apply(name)));
    sources.forEach(source -> source.useCredentials(perComponent.apply(source.name())));
  }

  /** Starts all event sources. If one fails, the ones already started are stopped again. */
  public synchronized void startSources(EventSink sink) throws CapabilityException {
    try {
      for (EventSource source : sources) {
        source.start(sink);
        started.add(source);
      }
    } catch (CapabilityException | RuntimeException e) {
      stopSources();
      throw e;
    }
  }

  /** Stops the sources that were started. */
  public synchronized void stopSources() {
    for (EventSource source : started.reversed()) {
      try {
        source.stop();
      } catch (RuntimeException e) {
        System.err.println("praksis: cannot stop source " + source.name() + ": " + e);
      }
    }
    started.clear();
  }

  @Override
  public void close() {
    stopSources();
    for (Capability capability : capabilities.values()) {
      try {
        capability.close();
      } catch (RuntimeException e) {
        // one module failing to clean up must not keep the others from it
      }
    }
  }
}
