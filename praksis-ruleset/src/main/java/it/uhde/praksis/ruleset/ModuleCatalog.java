// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** The capabilities and event types of all installed modules, by name. */
public final class ModuleCatalog {
  private final Map<String, CapabilityDescriptor> capabilities = new HashMap<>();
  private final Map<String, EventTypeDescriptor> eventTypes = new HashMap<>();
  private final java.util.Set<String> sources = new java.util.HashSet<>();
  private final java.util.Set<String> moduleNames = new java.util.TreeSet<>();
  private java.util.function.Function<String, Optional<String>> availability =
      n -> Optional.empty();

  /** Builds the catalog. Fails if two modules declare the same capability or event type. */
  public ModuleCatalog(List<ModuleDescriptor> modules) {
    for (ModuleDescriptor module : modules) {
      moduleNames.add(module.name());
      sources.addAll(module.sources());
      for (CapabilityDescriptor c : module.capabilities()) {
        if (capabilities.putIfAbsent(c.name(), c) != null) {
          throw new IllegalArgumentException("capability declared twice: " + c.name());
        }
      }
      for (EventTypeDescriptor e : module.eventTypes()) {
        if (eventTypes.putIfAbsent(e.type(), e) != null) {
          throw new IllegalArgumentException("event type declared twice: " + e.type());
        }
      }
    }
  }

  /**
   * Tells which capabilities cannot be used now and why. This is for the capabilities of other
   * daemons ({@code acme/entity.read}), which can disappear or be out of reach while the rest of
   * the catalog stays; a rule that calls one is valid but the verifier reports it. Returns this.
   */
  public ModuleCatalog withAvailability(
      java.util.function.Function<String, Optional<String>> availability) {
    this.availability = availability;
    return this;
  }

  /** Why this capability cannot be used now, or empty if it can. */
  public Optional<String> unavailable(String capability) {
    return availability.apply(capability);
  }

  /** The names of the modules that are loaded, which a rule or view may ask for. */
  public java.util.Set<String> moduleNames() {
    return java.util.Set.copyOf(moduleNames);
  }

  /** Whether a module provides an event source with this name. */
  public boolean hasSource(String name) {
    return sources.contains(name);
  }

  /** Every capability of the installed modules. */
  public java.util.Collection<CapabilityDescriptor> capabilities() {
    return java.util.List.copyOf(capabilities.values());
  }

  public Optional<CapabilityDescriptor> capability(String name) {
    return Optional.ofNullable(capabilities.get(name));
  }

  public Optional<EventTypeDescriptor> eventType(String type) {
    return Optional.ofNullable(eventTypes.get(type));
  }
}
