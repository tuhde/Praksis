// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression;

import it.uhde.praksis.capability.ValueType;

/** Compiles expression source text. Implementations are found with {@link Expressions}. */
public interface ExpressionCompiler {
  /**
   * Compiles an expression whose result may have any type.
   *
   * @throws ExpressionException if the source is malformed or uses unknown fields or wrong types
   */
  Expression compile(String source, Scope scope) throws ExpressionException;

  /**
   * Compiles an expression that must produce a value of the expected type.
   *
   * @throws ExpressionException as above, or if the result type does not match
   */
  Expression compile(String source, Scope scope, ValueType expected) throws ExpressionException;
}
