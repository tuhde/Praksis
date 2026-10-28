// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A rule as the plain values of its TOML file: maps, lists, strings, numbers and booleans. Patches
 * are applied to this form, and the hash that layers use to say which rule they were written for is
 * computed from it, so the formatting of a rule file does not matter.
 */
public final class RuleView {
  private static final JsonMapper CANONICAL =
      JsonMapper.builder()
          .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
          .build();

  private RuleView() {}

  /** The rule in the shape of its TOML file. */
  public static Map<String, Object> toMap(Rule rule) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", rule.id());
    rule.description().ifPresent(d -> m.put("description", d));
    m.put("effect", rule.effect().id());
    m.put("needs", new ArrayList<Object>(rule.needs()));
    m.put("required", rule.required());
    Trigger.OnEvent trigger = (Trigger.OnEvent) rule.trigger();
    Map<String, Object> t = new LinkedHashMap<>();
    t.put("kind", "event");
    t.put("type", trigger.type());
    trigger.filter().ifPresent(f -> t.put("filter", f));
    m.put("trigger", t);
    List<Object> actions = new ArrayList<>();
    for (Action action : rule.actions()) {
      Map<String, Object> a = new LinkedHashMap<>();
      switch (action) {
        case Action.Call call -> {
          a.put("id", call.id());
          a.put("capability", call.capability());
          Map<String, Object> args = new LinkedHashMap<>();
          call.args()
              .forEach(
                  (name, arg) ->
                      args.put(
                          name,
                          switch (arg) {
                            case Arg.Literal l -> l.value();
                            case Arg.Expr e ->
                                new LinkedHashMap<String, Object>(Map.of("cel", e.source()));
                          }));
          a.put("args", args);
        }
        case Action.Stop stop -> a.put("stop", true);
      }
      actions.add(a);
    }
    m.put("action", actions);
    return m;
  }

  /** The rule as JSON, in the shape of {@link #toMap}; {@link #fromJson} reads it back. */
  public static String toJson(Rule rule) {
    try {
      return CANONICAL.writeValueAsString(toMap(rule));
    } catch (JsonProcessingException e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * Reads a rule written by {@link #toJson}.
   *
   * @throws RulesetException if the text is not a rule
   */
  public static Rule fromJson(String json) throws RulesetException {
    Map<String, Object> map;
    try {
      map = CANONICAL.readValue(json, new com.fasterxml.jackson.core.type.TypeReference<>() {});
    } catch (JsonProcessingException e) {
      throw new RulesetException(
          List.of(new Problem("rule", "cannot read: " + e.getOriginalMessage())));
    }
    List<Problem> problems = new ArrayList<>();
    String id = map.get("id") instanceof String s ? s : "";
    java.util.Optional<Rule> rule = RulesetLoader.rule(new Table(map, "rule " + id, problems), id);
    if (rule.isEmpty() || !problems.isEmpty()) {
      throw new RulesetException(
          problems.isEmpty() ? List.of(new Problem("rule " + id, "cannot be read")) : problems);
    }
    return rule.get();
  }

  /** {@code sha256:<hex>} of the rule's content, independent of file formatting and key order. */
  public static String hash(Rule rule) {
    try {
      byte[] json = CANONICAL.writeValueAsString(toMap(rule)).getBytes(StandardCharsets.UTF_8);
      return "sha256:"
          + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json));
    } catch (JsonProcessingException | NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
