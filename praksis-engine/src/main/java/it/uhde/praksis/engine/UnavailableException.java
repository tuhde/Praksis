// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityException;

/**
 * A capability cannot be used now because what it needs is out of reach (another daemon that cannot
 * be connected to). The engine does not count this as a failure of the rule: the event is skipped
 * for that rule and recorded as missed, so that it can be replayed when the capability is back,
 * like an event a disabled rule did not see.
 */
public final class UnavailableException extends CapabilityException {
  private static final long serialVersionUID = 1L;

  public UnavailableException(String message) {
    super(message, true);
  }

  public UnavailableException(String message, Throwable cause) {
    super(message, true, cause);
  }
}
