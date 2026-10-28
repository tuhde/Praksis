// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

/** The daemon cannot start or run. The message is meant for the person who started it. */
public class DaemonException extends Exception {
  private static final long serialVersionUID = 1L;

  public DaemonException(String message) {
    super(message);
  }

  public DaemonException(String message, Throwable cause) {
    super(message, cause);
  }
}
