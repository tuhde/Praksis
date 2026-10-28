// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stand-in results for a capability that is not really called, so that later actions that read
 * {@code results} still evaluate: an empty string, zero, false and so on for each declared result.
 */
final class Defaults {
  private Defaults() {}

  static Map<String, Object> results(CapabilityDescriptor capability) {
    Map<String, Object> out = new HashMap<>();
    for (FieldDescriptor f : capability.results()) {
      out.put(
          f.name(),
          switch (f.type()) {
            case STRING -> "";
            case INT -> 0L;
            case DOUBLE -> 0.0;
            case BOOL -> false;
            case TIMESTAMP -> Instant.EPOCH;
            case BYTES -> new byte[0];
            case LIST -> List.of();
            case MAP -> Map.of();
          });
    }
    return out;
  }
}
