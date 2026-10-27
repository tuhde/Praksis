// SPDX-License-Identifier: Apache-2.0
/** The rule model, the loader for rulesets written in TOML, and the static validation. */
module it.uhde.praksis.ruleset {
  requires transitive it.uhde.praksis.capability;
  requires transitive it.uhde.praksis.expression;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.dataformat.toml;

  exports it.uhde.praksis.ruleset;
}
