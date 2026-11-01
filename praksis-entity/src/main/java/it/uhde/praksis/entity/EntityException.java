// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.entity;

/** A call on entities was refused: wrong input, a constraint, or an unknown type. */
public class EntityException extends Exception {
  private static final long serialVersionUID = 1L;

  public EntityException(String message) {
    super(message);
  }

  public EntityException(String message, Throwable cause) {
    super(message, cause);
  }
}
