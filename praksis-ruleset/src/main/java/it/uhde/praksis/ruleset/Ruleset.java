// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.Objects;

/**
 * A loaded ruleset.
 *
 * @param formatVersion the version of the file format
 * @param name the ruleset name
 * @param params the declared parameters
 * @param rules the rules in firing order: sorted by id, since a directory has no declaration order
 * @param views the screens, sorted by id
 */
public record Ruleset(
    int formatVersion, String name, List<ParamDecl> params, List<Rule> rules, List<View> views) {
  /** The format version this engine understands. */
  public static final int SUPPORTED_FORMAT_VERSION = 1;

  public Ruleset {
    Objects.requireNonNull(name, "name");
    params = List.copyOf(params);
    rules = List.copyOf(rules);
    views = List.copyOf(views);
  }

  /** A ruleset without views. */
  public Ruleset(int formatVersion, String name, List<ParamDecl> params, List<Rule> rules) {
    this(formatVersion, name, params, rules, List.of());
  }
}
