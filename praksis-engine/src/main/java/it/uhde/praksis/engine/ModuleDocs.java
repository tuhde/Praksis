// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import java.util.List;

/**
 * The reference page of a module, written from its descriptor so that it cannot differ from what
 * the module declares. The page is under {@code docs/modules/} and is checked by a test.
 */
public final class ModuleDocs {
  private ModuleDocs() {}

  /** The Markdown page for a module. */
  public static String page(ModuleDescriptor m) {
    StringBuilder b = new StringBuilder();
    b.append("# Module ").append(m.name()).append("\n\n");
    b.append("<!-- Generated from the module's descriptor. Do not edit. -->\n\n");
    b.append("Version ").append(m.version()).append(".\n");
    if (!m.sources().isEmpty()) {
      b.append("\n**Event sources:** ").append(codes(m.sources())).append("\n");
    }
    if (!m.network().isEmpty()) {
      b.append("\n**Reaches the network** (refused or not started in offline mode): ")
          .append(codes(m.network()))
          .append("\n");
    }
    if (!m.capabilities().isEmpty()) {
      b.append("\n## Capabilities\n");
      for (CapabilityDescriptor c : m.capabilities()) {
        b.append("\n### ").append(c.name()).append("\n\n");
        b.append("Effect: `").append(c.effect().id()).append("`. ");
        b.append(c.supportsDryRun() ? "Supports dry run.\n" : "Does not support dry run.\n");
        fields(b, "Parameters", c.params(), true);
        fields(b, "Results", c.results(), false);
      }
    }
    if (!m.eventTypes().isEmpty()) {
      b.append("\n## Events\n");
      for (EventTypeDescriptor e : m.eventTypes()) {
        b.append("\n### ").append(e.type()).append("\n");
        fields(b, "Fields", e.fields(), false);
      }
    }
    return b.toString();
  }

  private static void fields(
      StringBuilder b, String title, List<FieldDescriptor> fields, boolean scopable) {
    if (fields.isEmpty()) {
      return;
    }
    b.append("\n").append(title).append(":\n\n");
    b.append(
        scopable
            ? "| Name | Type | Required | Grant can limit |\n"
            : "| Name | Type | Required |\n");
    b.append(scopable ? "|---|---|---|---|\n" : "|---|---|---|\n");
    for (FieldDescriptor f : fields) {
      b.append("| `").append(f.name()).append("` | ").append(f.type().id());
      b.append(" | ").append(f.required() ? "yes" : "no");
      if (scopable) {
        b.append(" | ").append(f.scopable() ? "yes" : "no");
      }
      b.append(" |\n");
    }
  }

  private static String codes(List<String> names) {
    return String.join(", ", names.stream().map(n -> "`" + n + "`").toList());
  }
}
