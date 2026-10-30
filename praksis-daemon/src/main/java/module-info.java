// SPDX-License-Identifier: Apache-2.0
/** The Praksis daemon: runs the engine and serves the local API on a Unix domain socket. */
module it.uhde.praksis.daemon {
  requires it.uhde.praksis.engine;
  requires it.uhde.praksis.git;
  requires it.uhde.praksis.protocol;
  requires it.uhde.praksis.expression;
  requires com.fasterxml.jackson.databind;
  requires com.fasterxml.jackson.dataformat.toml;
  // Not used here: it puts the HTTP client of the JDK into the boot layer, which the modules that
  // are loaded from disk (the AI module talks to model servers) find their JDK modules in. A JDK
  // module that nothing in the daemon needs or binds is not resolved otherwise.
  requires java.net.http;

  exports it.uhde.praksis.daemon;
}
