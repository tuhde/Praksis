// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import it.uhde.praksis.ruleset.RuleTest.ExpectedCall;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Reads the {@code tests/} directory of a layer, see {@link RuleTest}. */
public final class RuleTests {
  private RuleTests() {}

  /**
   * The tests of a layer, in file order. A layer without a {@code tests} directory has none.
   *
   * @throws RulesetException with every problem found
   */
  public static List<RuleTest> load(Path layerDirectory) throws RulesetException {
    Path dir = layerDirectory.resolve("tests");
    if (!Files.isDirectory(dir)) {
      return List.of();
    }
    List<Problem> problems = new ArrayList<>();
    List<RuleTest> tests = new ArrayList<>();
    try (Stream<Path> files = Files.list(dir)) {
      for (Path file : files.filter(f -> f.toString().endsWith(".toml")).sorted().toList()) {
        read(file, problems, tests);
      }
    } catch (IOException e) {
      problems.add(new Problem("tests", "cannot list: " + e.getMessage()));
    }
    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }
    return tests;
  }

  private static void read(Path file, List<Problem> problems, List<RuleTest> tests) {
    String name = file.getFileName().toString();
    String where = "tests/" + name;
    String rule = name.replaceFirst("\\.toml$", "");
    Table t = RulesetLoader.read(file, where, problems);
    if (t == null) {
      return;
    }
    t.onlyKeys(Set.of("format_version", "test"));
    int version = RulesetLoader.formatVersion(t);
    if (version != 0 && version != 1) {
      t.problem("unsupported 'format_version' " + version);
    }
    Set<String> names = new HashSet<>();
    List<Table> entries = t.tables("test");
    if (entries.isEmpty()) {
      t.problem("has no [[test]]");
    }
    for (Table e : entries) {
      e.onlyKeys(
          Set.of("name", "event", "params", "mock", "call", "when_modules", "without_modules"));
      String testName = e.string("name", true).orElse("?");
      if (!names.add(testName)) {
        e.problem("test name '" + testName + "' is used twice");
      }
      String type = "";
      Map<String, Object> data = Map.of();
      var event = e.table("event", true);
      if (event.isPresent()) {
        event.get().onlyKeys(Set.of("type", "data"));
        type = event.get().string("type", true).orElse("");
        var raw = event.get().raw("data");
        if (raw.isPresent()) {
          if (raw.get() instanceof Map<?, ?> m) {
            data = copy(m);
          } else {
            event.get().problem("'data' must be a table");
          }
        }
      }
      Map<String, Object> params = new LinkedHashMap<>();
      e.table("params", false).ifPresent(m -> params.putAll(m.rawEntries()));
      Map<String, Map<String, Object>> mocks = new LinkedHashMap<>();
      e.table("mock", false)
          .ifPresent(
              m -> m.subTables().forEach((cap, results) -> mocks.put(cap, results.rawEntries())));
      List<ExpectedCall> calls = new ArrayList<>();
      for (Table c : e.tables("call")) {
        c.onlyKeys(Set.of("capability", "args"));
        Map<String, Object> args = Map.of();
        var raw = c.raw("args");
        if (raw.isPresent()) {
          if (raw.get() instanceof Map<?, ?> m) {
            args = copy(m);
          } else {
            c.problem("'args' must be a table");
          }
        }
        Map<String, Object> expected = args;
        c.string("capability", true).ifPresent(cap -> calls.add(new ExpectedCall(cap, expected)));
      }
      tests.add(
          new RuleTest(
              rule,
              testName,
              where + ", test '" + testName + "'",
              type,
              data,
              params,
              mocks,
              calls,
              e.strings("when_modules"),
              e.strings("without_modules")));
    }
  }

  private static Map<String, Object> copy(Map<?, ?> m) {
    Map<String, Object> out = new LinkedHashMap<>();
    m.forEach((k, v) -> out.put(String.valueOf(k), v));
    return out;
  }
}
