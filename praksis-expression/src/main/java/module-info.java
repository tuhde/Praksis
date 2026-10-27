// SPDX-License-Identifier: Apache-2.0
/**
 * Compiles and evaluates the expressions used in rules. The implementation is found with {@link
 * java.util.ServiceLoader}, so no other module needs to know which expression language runs behind
 * it.
 */
module it.uhde.praksis.expression {
  requires transitive it.uhde.praksis.capability;

  exports it.uhde.praksis.expression;

  uses it.uhde.praksis.expression.ExpressionCompiler;
}
