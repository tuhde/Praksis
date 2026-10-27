// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.capability;

/** A capability or event source failed. */
public class CapabilityException extends Exception {
  private static final long serialVersionUID = 1L;

  private final boolean retryable;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param retryable whether trying the same call again may succeed (e.g. a network timeout)
   */
  public CapabilityException(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  /** Creates the exception with a cause. */
  public CapabilityException(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }

  /** Whether trying the same call again may succeed. */
  public boolean retryable() {
    return retryable;
  }
}
