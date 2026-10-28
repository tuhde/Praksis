// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.Optional;

/** The operations a layer can apply to the rules of the layers below it. */
public final class LayerOps {
  private LayerOps() {}

  /**
   * Replaces a rule completely.
   *
   * @param rule the new rule; its id is the target
   * @param basedOn the hash of the rule it replaces, see {@link RuleView#hash}
   */
  public record Replace(Rule rule, String basedOn) {}

  /**
   * Turns a rule off. It stays in the effective view as disabled.
   *
   * @param id the target
   * @param basedOn the hash of the rule it turns off
   * @param reason why, or empty
   */
  public record Disable(String id, String basedOn, Optional<String> reason) {}

  /**
   * Changes single fields of a rule.
   *
   * @param id the target
   * @param basedOn the hash of the whole rule, if the author wants any change to it reported, else
   *     empty. The changes are checked field by field in any case.
   * @param changes the changes, applied in order
   */
  public record Patch(String id, Optional<String> basedOn, List<Change> changes) {}

  /**
   * One field of a patch.
   *
   * @param path the field: {@code description}, {@code effect}, {@code required}, {@code needs},
   *     {@code trigger.type}, {@code trigger.filter}, {@code action.<id>.capability} or {@code
   *     action.<id>.args.<name>}
   * @param hasExpect whether the field must currently have the value {@code expect}; if false it
   *     must currently be absent
   * @param expect the value the field must have now
   * @param remove whether the field is removed instead of set
   * @param value the new value
   */
  public record Change(
      String path, boolean hasExpect, Object expect, boolean remove, Object value) {}
}
