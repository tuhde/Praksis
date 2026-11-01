// SPDX-License-Identifier: Apache-2.0
/** Entity types: the model, the loader for types written in TOML, and their validation. */
module it.uhde.praksis.entity {
  requires transitive it.uhde.praksis.capability;
  requires transitive it.uhde.praksis.ruleset;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.dataformat.toml;

  exports it.uhde.praksis.entity;
}
