// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

/** A module or the expression layer could not be loaded. */
public class ModuleLoadException extends Exception {
  private static final long serialVersionUID = 1L;

  public ModuleLoadException(String message) {
    super(message);
  }

  public ModuleLoadException(String message, Throwable cause) {
    super(message, cause);
  }
}
