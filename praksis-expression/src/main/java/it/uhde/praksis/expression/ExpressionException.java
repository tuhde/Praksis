// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression;

/** An expression could not be compiled or evaluated. */
public class ExpressionException extends Exception {
  private static final long serialVersionUID = 1L;

  public ExpressionException(String message) {
    super(message);
  }

  public ExpressionException(String message, Throwable cause) {
    super(message, cause);
  }
}
