// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

/** The value of one action argument. */
public sealed interface Arg {
  /** A value written in the rule. */
  record Literal(Object value) implements Arg {}

  /** An expression, written as {@code { cel = "..." }}, evaluated when the action runs. */
  record Expr(String source) implements Arg {}
}
