package praksis.api;

import java.util.Map;

/** Minimal stand-in for the future capability-api. */
public interface Capability {
  String name();

  Object invoke(Map<String, Object> args) throws Exception;
}
