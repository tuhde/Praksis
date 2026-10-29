// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One test of a rule: an event goes in, and the calls the rule makes come out. The file {@code
 * tests/<rule id>.toml} of a layer holds the tests of that rule:
 *
 * <pre>
 * format_version = 1
 *
 * [[test]]
 * name = "moves mail from a list"
 * [test.event]
 * type = "mail.received"
 * data = { subject = "[list] hi", message = "m1" }
 * [[test.call]]
 * capability = "mail.move"
 * args = { folder = "Lists" }
 * </pre>
 *
 * {@code [[test.call]]} lists, in order, the calls that the rule makes (only the arguments named
 * here are compared). No {@code call} means the rule makes none. {@code [test.mock."cap"]} gives
 * the results that a call of that capability returns, for rules that read {@code results}. {@code
 * [test.params]} sets parameter values for the test.
 *
 * @param rule the rule under test
 * @param name the name of the test, unique within the file
 * @param where the file, for messages
 * @param eventType the event type
 * @param eventData the event payload
 * @param params parameter values for this test; the tests otherwise run with the defaults of the
 *     layers, not with the values in the local configuration
 * @param mocks results by capability name
 * @param calls the calls the rule must make, in order
 * @param whenModules modules that must be there for the test to run
 * @param withoutModules modules that must not be there for the test to run
 */
public record RuleTest(
    String rule,
    String name,
    String where,
    String eventType,
    Map<String, Object> eventData,
    Map<String, Object> params,
    Map<String, Map<String, Object>> mocks,
    List<ExpectedCall> calls,
    List<String> whenModules,
    List<String> withoutModules) {
  public RuleTest {
    Objects.requireNonNull(rule, "rule");
    Objects.requireNonNull(name, "name");
    eventData = Map.copyOf(eventData);
    params = Map.copyOf(params);
    mocks = Map.copyOf(mocks);
    calls = List.copyOf(calls);
    whenModules = List.copyOf(whenModules);
    withoutModules = List.copyOf(withoutModules);
  }

  /** A test that runs wherever the rule is. */
  public RuleTest(
      String rule,
      String name,
      String where,
      String eventType,
      Map<String, Object> eventData,
      Map<String, Object> params,
      Map<String, Map<String, Object>> mocks,
      List<ExpectedCall> calls) {
    this(rule, name, where, eventType, eventData, params, mocks, calls, List.of(), List.of());
  }

  /**
   * Whether the test is meant for an installation with these modules: all of {@code whenModules}
   * are there and none of {@code withoutModules}. A rule with steps that need a module does
   * something else without it, and each way has its own test.
   */
  public boolean appliesTo(java.util.Set<String> modules) {
    return modules.containsAll(whenModules) && withoutModules.stream().noneMatch(modules::contains);
  }

  /** A call that the rule is expected to make. */
  public record ExpectedCall(String capability, Map<String, Object> args) {
    public ExpectedCall {
      args = Map.copyOf(args);
    }
  }
}
