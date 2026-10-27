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
 */
public record ModuleDescriptor(
    String name,
    String version,
    List<String> jdkModules,
    List<CapabilityDescriptor> capabilities,
    List<EventTypeDescriptor> eventTypes) {
  public ModuleDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(version, "version");
    jdkModules = List.copyOf(jdkModules);
    capabilities = List.copyOf(capabilities);
    eventTypes = List.copyOf(eventTypes);
  }
}
