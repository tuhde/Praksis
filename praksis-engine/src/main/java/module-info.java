// SPDX-License-Identifier: Apache-2.0
/** Runs rules: takes events, fires the matching rules in order and calls capabilities. */
module it.uhde.praksis.engine {
  requires transitive it.uhde.praksis.capability;
  requires transitive it.uhde.praksis.ruleset;
  requires transitive it.uhde.praksis.store;

  exports it.uhde.praksis.engine;
}
