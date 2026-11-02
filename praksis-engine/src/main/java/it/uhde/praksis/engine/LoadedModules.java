// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.Credentials;
import it.uhde.praksis.capability.EntityStorage;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.ruleset.ModuleCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/** The modules found in the modules directory, with their capabilities and event sources. */
public final class LoadedModules implements AutoCloseable {
  private final List<ModuleDescriptor> descriptors;
  private final Map<String, Capability> capabilities;
  private final List<EventSource> sources;
  private final Map<String, Supplier<EntityStorage>> backends;
  private final List<EventSource> started = new ArrayList<>();
  private java.util.function.Function<String, Optional<String>> availability =
      n -> Optional.empty();

  LoadedModules(
      List<ModuleDescriptor> descriptors,
      Map<String, Capability> capabilities,
      List<EventSource> sources) {
    this(descriptors, capabilities, sources, Map.of());
  }

  LoadedModules(
      List<ModuleDescriptor> descriptors,
      Map<String, Capability> capabilities,
      List<EventSource> sources,
      Map<String, Supplier<EntityStorage>> backends) {
    this.descriptors = List.copyOf(descriptors);
    this.capabilities = Map.copyOf(capabilities);
    this.sources = List.copyOf(sources);
    this.backends = Map.copyOf(backends);
  }

  /**
   * A new instance of the entity storage that a module provides under this name, not yet
   * configured, or empty if no module does. (Several instances of one backend can exist, each for
   * its own types.)
   */
  public Optional<EntityStorage> newBackend(String name) {
    Supplier<EntityStorage> factory = backends.get(name);
    return factory == null ? Optional.empty() : Optional.of(factory.get());
  }

  /** The names of the entity storages the modules provide. */
  public java.util.Set<String> backendNames() {
    return backends.keySet();
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
    LoadedModules next = new LoadedModules(all, merged, sources, backends);
    next.availability = availability;
    return next;
  }

  /**
   * These modules as they are when the program runs offline: the capabilities that reach other
   * machines (the {@code network} list of the module) refuse with a message that says why, its
   * event sources do not start, and every other part is told ({@link Capability#useOffline}). Rules
   * and views that name those capabilities still check, so a ruleset works the same on both sides
   * of the switch. The original is not used again.
   */
  public LoadedModules offline() {
    java.util.Set<String> network = new java.util.HashSet<>();
    descriptors.forEach(d -> network.addAll(d.network()));
    Map<String, Capability> out = new java.util.HashMap<>();
    capabilities.forEach(
        (name, c) -> {
          if (network.contains(name)) {
            out.put(name, new Offline(c));
          } else {
            c.useOffline();
            out.put(name, c);
          }
        });
    List<EventSource> kept = new ArrayList<>();
    for (EventSource s : sources) {
      if (!network.contains(s.name())) {
        s.useOffline();
        kept.add(s);
      }
    }
    Map<String, Supplier<EntityStorage>> backendsOffline = new java.util.HashMap<>();
    backends.forEach(
        (name, factory) ->
            backendsOffline.put(
                name,
                network.contains(name)
                    ? () -> {
                      throw new IllegalStateException(
                          "offline = true: the storage '" + name + "' needs the network");
                    }
                    : () -> {
                      EntityStorage storage = factory.get();
                      storage.useOffline();
                      return storage;
                    }));
    LoadedModules next = new LoadedModules(descriptors, out, kept, backendsOffline);
    next.availability = availability;
    return next;
  }

  /** A capability of a module that needs the network, while the program is offline. */
  private record Offline(Capability inner) implements Capability {
    @Override
    public String name() {
      return inner.name();
    }

    @Override
    public Map<String, Object> invoke(
        it.uhde.praksis.capability.CapabilityContext context, Map<String, Object> args)
        throws CapabilityException {
      throw new CapabilityException(
          "offline = true: " + inner.name() + " needs the network and is not available", false);
    }

    @Override
    public Map<String, Object> redact(Map<String, Object> args) {
      return inner.redact(args);
    }

    @Override
    public void close() {
      inner.close();
    }
  }

  public List<ModuleDescriptor> descriptors() {
    return descriptors;
  }

  /** The descriptors by capability and event type name, for validating rulesets. */
  public ModuleCatalog catalog() {
    return new ModuleCatalog(descriptors).withAvailability(availability);
  }

  /**
   * Tells the catalog which capabilities (those of other daemons) cannot be used now. Returns this.
   */
  public LoadedModules withAvailability(
      java.util.function.Function<String, Optional<String>> availability) {
    this.availability = availability;
    return this;
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
