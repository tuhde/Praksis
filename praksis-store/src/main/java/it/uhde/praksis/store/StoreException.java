// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.store;

/** The database could not be read or written. */
public class StoreException extends Exception {
  private static final long serialVersionUID = 1L;

  public StoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
