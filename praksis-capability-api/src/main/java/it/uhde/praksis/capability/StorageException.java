// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/** A storage backend could not read or write, or refused a write (for example a unique value). */
public class StorageException extends Exception {
  private static final long serialVersionUID = 1L;

  public StorageException(String message) {
    super(message);
  }

  public StorageException(String message, Throwable cause) {
    super(message, cause);
  }
}
