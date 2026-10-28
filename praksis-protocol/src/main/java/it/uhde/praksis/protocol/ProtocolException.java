// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.protocol;

import java.io.IOException;

/** A message could not be read, written or understood. */
public class ProtocolException extends IOException {
  private static final long serialVersionUID = 1L;

  public ProtocolException(String message) {
    super(message);
  }

  public ProtocolException(String message, Throwable cause) {
    super(message, cause);
  }
}
