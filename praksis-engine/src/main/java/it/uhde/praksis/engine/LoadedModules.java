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

  /**
   * These modules and one that is part of the daemon itself (it needs the daemon's own objects, so
   * it is not loaded from disk). The result owns all the capabilities; the original is not used
   * again.
   */
  public LoadedModules with(ModuleDescriptor builtIn, Map<String, Capability> capabilities) {
    List<ModuleDescriptor> all = new ArrayList<>(descriptors);
    all.add(builtIn);
    Map<String, Capability> merged = new java.util.HashMap<>(this.capabilities);
    capabilities.forEach(
        (name, c) -> {
          if (merged.putIfAbsent(name, c) != null) {
            throw new IllegalArgumentException("capability '" + name + "' is provided twice");
          }
        });
    return new LoadedModules(all, merged, sources);
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

  /** Tells every capability where the files are that a person chose to attach. */
  public void useUploadsDirectory(java.nio.file.Path directory) {
    capabilities.values().forEach(capability -> capability.useUploadsDirectory(directory));
  }

  /**
   * Gives every capability and event source the directory of its module below {@code root} ({@code
   * root/<module name>}), which is made if it is missing. A module that cannot be given a directory
   * is left without one and told nothing: it has to work without, which is the default.
   */
  public void useDataDirectory(java.nio.file.Path root) {
    for (ModuleDescriptor module : descriptors) {
      java.nio.file.Path directory = root.resolve(module.name());
      try {
        java.nio.file.Files.createDirectories(directory);
      } catch (java.io.IOException e) {
        System.err.println("praksis: no data directory for module " + module.name() + ": " + e);
        continue;
      }
      for (var capability : module.capabilities()) {
        Capability part = capabilities.get(capability.name());
        if (part != null) {
          part.useDataDirectory(directory);
        }
      }
      for (String name : module.sources()) {
        sources.stream()
            .filter(source -> source.name().equals(name))
            .forEach(source -> source.useDataDirectory(directory));
      }
    }
  }

  /** Starts all event sources. If one fails, the ones already started are stopped again. */
  public synchronized void startSources(EventSink sink) throws CapabilityException {
    try {
      for (EventSource source : sources) {
        ModuleCalls.start(source, sink);
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
