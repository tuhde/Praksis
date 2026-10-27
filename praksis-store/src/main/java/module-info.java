// SPDX-License-Identifier: Apache-2.0
/** SQLite persistence for events and the run log. */
module it.uhde.praksis.store {
  requires transitive it.uhde.praksis.capability;
  requires java.sql;
  requires com.fasterxml.jackson.databind;
  requires org.xerial.sqlitejdbc;

  exports it.uhde.praksis.store;
}
