// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.stream.Collectors;

/** A ruleset could not be loaded. Carries every problem found, not only the first. */
public class RulesetException extends Exception {
  private static final long serialVersionUID = 1L;

  private final transient List<Problem> problems;

  public RulesetException(List<Problem> problems) {
    super(problems.stream().map(Problem::toString).collect(Collectors.joining("\n")));
    this.problems = List.copyOf(problems);
  }

  public List<Problem> problems() {
    return problems;
  }
}
