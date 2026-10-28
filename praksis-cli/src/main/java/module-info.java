// SPDX-License-Identifier: Apache-2.0
/** The {@code praksis} command. Talks to the daemon only through the protocol. */
module it.uhde.praksis.cli {
  requires it.uhde.praksis.protocol;
  requires info.picocli;

  opens it.uhde.praksis.cli to
      info.picocli;
}
