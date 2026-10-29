// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** A TOML table with typed getters that record problems instead of throwing. */
public final class Table {
  private final Map<String, Object> values;
  private final String location;
  private final List<Problem> problems;

  public Table(Map<String, Object> values, String location, List<Problem> problems) {
    this.values = values;
    this.location = location;
    this.problems = problems;
  }

  /** The list that problems are recorded in, for code that reads nested tables. */
  List<Problem> problems() {
    return problems;
  }

  public String location() {
    return location;
  }

  public void problem(String message) {
    problems.add(new Problem(location, message));
  }

  /** Reports every key that is not in allowed, which catches typos. */
  public void onlyKeys(Set<String> allowed) {
    for (String key : values.keySet()) {
      if (!allowed.contains(key)) {
        problem("unknown key '" + key + "'");
      }
    }
  }

  public boolean has(String key) {
    return values.containsKey(key);
  }

  public Map<String, Object> rawEntries() {
    return values;
  }

  public Optional<Object> raw(String key) {
    return Optional.ofNullable(values.get(key));
  }

  public Optional<String> string(String key, boolean required) {
    Object v = values.get(key);
    if (v == null) {
      if (required) {
        problem("missing '" + key + "'");
      }
      return Optional.empty();
    }
    if (v instanceof String s) {
      return Optional.of(s);
    }
    problem("'" + key + "' must be a string");
    return Optional.empty();
  }

  public boolean bool(String key, boolean defaultValue) {
    Object v = values.get(key);
    if (v == null) {
      return defaultValue;
    }
    if (v instanceof Boolean b) {
      return b;
    }
    problem("'" + key + "' must be true or false");
    return defaultValue;
  }

  public Optional<Table> table(String key, boolean required) {
    Object v = values.get(key);
    if (v == null) {
      if (required) {
        problem("missing [" + key + "]");
      }
      return Optional.empty();
    }
    if (v instanceof Map<?, ?> m) {
      return Optional.of(new Table(cast(m), location + ", " + key, problems));
    }
    problem("'" + key + "' must be a table");
    return Optional.empty();
  }

  /** The tables of an array of tables, e.g. {@code [[action]]}. */
  public List<Table> tables(String key) {
    Object v = values.get(key);
    List<Table> out = new ArrayList<>();
    if (v == null) {
      return out;
    }
    if (v instanceof List<?> list) {
      int i = 0;
      for (Object item : list) {
        String where = location + ", " + key + "[" + i++ + "]";
        if (item instanceof Map<?, ?> m) {
          out.add(new Table(cast(m), where, problems));
        } else {
          problems.add(new Problem(where, "must be a table"));
        }
      }
    } else {
      problem("'" + key + "' must be an array of tables");
    }
    return out;
  }

  public List<String> strings(String key) {
    Object v = values.get(key);
    List<String> out = new ArrayList<>();
    if (v == null) {
      return out;
    }
    if (v instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
      list.forEach(s -> out.add((String) s));
    } else {
      problem("'" + key + "' must be a list of strings");
    }
    return out;
  }

  /** The tables inside this table, by key, e.g. every {@code [param.<name>]}. */
  public Map<String, Table> subTables() {
    Map<String, Table> out = new java.util.LinkedHashMap<>();
    for (var e : values.entrySet()) {
      if (e.getValue() instanceof Map<?, ?> m) {
        out.put(e.getKey(), new Table(cast(m), location + ", " + e.getKey(), problems));
      } else {
        problems.add(new Problem(location, "'" + e.getKey() + "' must be a table"));
      }
    }
    return out;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Map<?, ?> m) {
    return (Map<String, Object>) m;
  }
}
