// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.git;

/** A git operation failed. The message says what and, where git said, why. */
public class GitException extends Exception {
  private static final long serialVersionUID = 1L;

  public GitException(String message) {
    super(message);
  }

  public GitException(String message, Throwable cause) {
    super(message, cause);
  }
}
