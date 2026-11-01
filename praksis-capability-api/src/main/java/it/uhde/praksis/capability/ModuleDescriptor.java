// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.List;
import java.util.Objects;

/**
 * Metadata of a module, loaded from its {@code module.toml}.
 *
 * @param name the module name, e.g. {@code maildir}
 * @param version the module version
 * @param jdkModules JDK modules the module needs, so the runtime image can include them
 * @param capabilities the capabilities the module provides
 * @param eventTypes the event types the module produces
 * @param sources the names of the event sources the module provides
 * @param network the names of the capabilities, event sources and backends that reach other
 *     machines (a mail server, a database server, say): when the program is offline those
 *     capabilities refuse, those sources do not start and those backends cannot be used; the rest
 *     of the module works
 * @param backends the names of the entity storages the module provides ({@link EntityStorage})
 */
public record ModuleDescriptor(
    String name,
    String version,
    List<String> jdkModules,
    List<CapabilityDescriptor> capabilities,
    List<EventTypeDescriptor> eventTypes,
    List<String> sources,
    List<String> network,
    List<String> backends) {
  public ModuleDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    jdkModules = List.copyOf(jdkModules);
    capabilities = List.copyOf(capabilities);
    eventTypes = List.copyOf(eventTypes);
    sources = List.copyOf(sources);
    network = List.copyOf(network);
    backends = List.copyOf(backends);
  }

  /** A descriptor of a module that provides no entity storage. */
  public ModuleDescriptor(
      String name,
      String version,
      List<String> jdkModules,
      List<CapabilityDescriptor> capabilities,
      List<EventTypeDescriptor> eventTypes,
      List<String> sources,
      List<String> network) {
    this(name, version, jdkModules, capabilities, eventTypes, sources, network, List.of());
  }

  /** A descriptor of a module that stays on this machine. */
  public ModuleDescriptor(
      String name,
      String version,
      List<String> jdkModules,
      List<CapabilityDescriptor> capabilities,
      List<EventTypeDescriptor> eventTypes,
      List<String> sources) {
    this(name, version, jdkModules, capabilities, eventTypes, sources, List.of());
  }

  /** A descriptor without event sources. */
  public ModuleDescriptor(
      String name,
      String version,
      List<String> jdkModules,
      List<CapabilityDescriptor> capabilities,
      List<EventTypeDescriptor> eventTypes) {
    this(name, version, jdkModules, capabilities, eventTypes, List.of());
  }
}
