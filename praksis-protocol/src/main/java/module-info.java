// SPDX-License-Identifier: Apache-2.0
/**
 * The messages of the local API between the daemon and its clients, their JSON encoding and a
 * client for the daemon's Unix domain socket.
 */
module it.uhde.praksis.protocol {
  requires com.fasterxml.jackson.databind;

  exports it.uhde.praksis.protocol;

  opens it.uhde.praksis.protocol to
      com.fasterxml.jackson.databind;
}
