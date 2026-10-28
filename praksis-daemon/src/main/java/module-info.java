// SPDX-License-Identifier: Apache-2.0
/** The Praksis daemon: runs the engine and serves the local API on a Unix domain socket. */
module it.uhde.praksis.daemon {
  requires it.uhde.praksis.engine;
  requires it.uhde.praksis.git;
  requires it.uhde.praksis.protocol;
  requires it.uhde.praksis.expression;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.dataformat.toml;

  exports it.uhde.praksis.daemon;
}
