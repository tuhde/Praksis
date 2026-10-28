// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.Objects;

/**
 * What a grant allows for one argument of a capability. An argument without a matcher is not
 * restricted by the grant.
 */
public sealed interface ArgMatcher {
  /** Whether the evaluated argument is allowed. */
  boolean matches(Object value);

  /** The matcher as it is written in {@code policy.toml}, e.g. {@code prefix = "Archive/"}. */
  String describe();

  /** Allows exactly one value. */
  record Equals(Object value) implements ArgMatcher {
    public Equals {
      Objects.requireNonNull(value, "value");
    }

    @Override
    public boolean matches(Object other) {
      return same(value, other);
    }

    @Override
    public String describe() {
      return "equals = " + show(value);
    }
  }

  /** Allows any of several values. */
  record OneOf(List<Object> values) implements ArgMatcher {
    public OneOf {
      values = List.copyOf(values);
    }

    @Override
    public boolean matches(Object other) {
      return values.stream().anyMatch(v -> same(v, other));
    }

    @Override
    public String describe() {
      return "one_of = [" + String.join(", ", values.stream().map(ArgMatcher::show).toList()) + "]";
    }
  }

  /** Allows strings that start with the prefix. */
  record Prefix(String prefix) implements ArgMatcher {
    public Prefix {
      Objects.requireNonNull(prefix, "prefix");
    }

    @Override
    public boolean matches(Object other) {
      return other instanceof String s && s.startsWith(prefix);
    }

    @Override
    public String describe() {
      return "prefix = " + show(prefix);
    }
  }

  private static boolean same(Object a, Object b) {
    if (a instanceof Number x && b instanceof Number y) {
      return x.longValue() == y.longValue() && x.doubleValue() == y.doubleValue();
    }
    return a.equals(b);
  }

  static String show(Object value) {
    return value instanceof String s
        ? "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        : String.valueOf(value);
  }
}
