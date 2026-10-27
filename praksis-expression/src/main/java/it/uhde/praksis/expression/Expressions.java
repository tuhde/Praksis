// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression;

import java.util.ServiceLoader;

/** Finds the installed {@link ExpressionCompiler}. */
public final class Expressions {
  private Expressions() {}

  /** The compiler found in the layer this module is in. */
  public static ExpressionCompiler compiler() {
    return ServiceLoader.load(ExpressionCompiler.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no ExpressionCompiler implementation found"));
  }

  /** The compiler found in the given layer, for implementations loaded outside the boot layer. */
  public static ExpressionCompiler compiler(ModuleLayer layer) {
    return ServiceLoader.load(layer, ExpressionCompiler.class)
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("no ExpressionCompiler implementation found"));
  }
}
