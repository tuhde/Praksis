// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.List;
import java.util.Objects;

/**
 * Metadata of one capability, read by the verifier, the engine and the AI without running module
 * code.
 *
 * @param name the capability name, e.g. {@code mail.move}
 * @param effect what a call can change
 * @param supportsDryRun whether the capability honours {@link CapabilityContext#dryRun()}
 * @param params the typed parameters
 * @param results the typed values a call returns, available to later actions as {@code results}
 */
public record CapabilityDescriptor(
    String name,
    EffectClass effect,
    boolean supportsDryRun,
    List<FieldDescriptor> params,
    List<FieldDescriptor> results) {
  public CapabilityDescriptor {
    Objects.requireNonNull(name, "name");
    Objects.requireNonNull(effect, "effect");
    params = List.copyOf(params);
    results = List.copyOf(results);
  }
}
