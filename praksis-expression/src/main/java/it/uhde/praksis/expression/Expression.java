// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.expression;

/** A compiled, type-checked expression. Safe to evaluate from several threads. */
public interface Expression {
  /**
   * Evaluates the expression.
   *
   * @return a value of one of the Java types listed at {@link
   *     it.uhde.praksis.capability.ValueType}, or {@code null} for the null value
   * @throws ExpressionException if evaluation fails (e.g. a missing map key)
   */
  Object evaluate(Bindings bindings) throws ExpressionException;
}
