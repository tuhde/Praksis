// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

import java.util.List;
import java.util.Objects;

/**
 * Metadata of one event type. The listed fields are the typed core of {@link Event#data()}. A
 * producer may add further, free-form entries that rules can read but the verifier cannot
 * type-check.
 *
 * @param type the event type, e.g. {@code mail.received}
 * @param fields the typed core fields of the data
 */
public record EventTypeDescriptor(String type, List<FieldDescriptor> fields) {
  public EventTypeDescriptor {
    Objects.requireNonNull(type, "type");
    fields = List.copyOf(fields);
  }
}
