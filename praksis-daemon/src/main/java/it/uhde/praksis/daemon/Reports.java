// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Values as short text that a person or a model reads: long texts and lists are cut. */
final class Reports {
  private static final ObjectMapper JSON = new ObjectMapper();

  private Reports() {}

  /** The value as JSON of at most {@code max} characters. */
  static String shorten(Object value, int max) {
    String json;
    try {
      json = JSON.writeValueAsString(shrink(value, 0));
    } catch (Exception e) {
      json = String.valueOf(value);
    }
    return json.length() > max ? json.substring(0, max) + "..." : json;
  }

  private static Object shrink(Object v, int depth) {
    if (v instanceof String s) {
      String one = s.replaceAll("\\p{Cntrl}+", " ");
      return one.length() > 200 ? one.substring(0, 200) + "..." : one;
    }
    if (v instanceof byte[] b) {
      return "(" + b.length + " bytes)";
    }
    if (v instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object o : list.size() > 30 ? list.subList(0, 30) : list) {
        out.add(depth > 4 ? String.valueOf(o) : shrink(o, depth + 1));
      }
      if (list.size() > 30) {
        out.add("... " + (list.size() - 30) + " more");
      }
      return out;
    }
    if (v instanceof Map<?, ?> m) {
      Map<String, Object> out = new LinkedHashMap<>();
      m.forEach(
          (k, x) ->
              out.put(String.valueOf(k), depth > 4 ? String.valueOf(x) : shrink(x, depth + 1)));
      return out;
    }
    return v;
  }
}
