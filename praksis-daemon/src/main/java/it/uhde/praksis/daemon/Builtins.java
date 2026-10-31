// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.engine.LoadedModules;
import java.util.List;

/**
 * The module {@code praksis}, which is part of the daemon and not loaded from disk: what the
 * maintenance agent needs to see of the daemon and to leave behind (see {@link Agent}). Like any
 * capability, each of these needs a grant.
 */
final class Builtins {
  static final String MODULE = "praksis";

  private Builtins() {}

  private static FieldDescriptor in(
      String name, ValueType type, boolean required, boolean scopable) {
    return new FieldDescriptor(name, type, required, scopable);
  }

  private static FieldDescriptor out(String name, ValueType type) {
    return new FieldDescriptor(name, type, true, false);
  }

  static ModuleDescriptor descriptor() {
    return new ModuleDescriptor(
        MODULE,
        "0.1.0",
        List.of(),
        List.of(
            new CapabilityDescriptor(
                "praksis.rules.list",
                EffectClass.READ_ONLY,
                true,
                List.of(
                    in("layer", ValueType.STRING, false, true),
                    in("untested", ValueType.BOOL, false, false)),
                List.of(out("items", ValueType.LIST))),
            new CapabilityDescriptor(
                "praksis.rules.read",
                EffectClass.READ_ONLY,
                true,
                List.of(in("rule", ValueType.STRING, true, true)),
                List.of(
                    out("rule", ValueType.STRING),
                    out("layer", ValueType.STRING),
                    out("file", ValueType.STRING),
                    out("text", ValueType.STRING),
                    out("tests", ValueType.STRING),
                    out("effect", ValueType.STRING),
                    out("description", ValueType.STRING),
                    out("trigger", ValueType.STRING),
                    out("needs", ValueType.LIST),
                    out("tested", ValueType.BOOL))),
            new CapabilityDescriptor(
                "praksis.agent.propose",
                EffectClass.LOCAL_WRITE,
                true,
                List.of(
                    in("layer", ValueType.STRING, true, true),
                    in("rule", ValueType.STRING, true, true),
                    in("files", ValueType.LIST, true, false),
                    in("reason", ValueType.STRING, false, false)),
                List.of(
                    out("ok", ValueType.BOOL),
                    out("covers", ValueType.BOOL),
                    out("problems", ValueType.LIST),
                    out("staged", ValueType.BOOL),
                    out("id", ValueType.STRING))),
            new CapabilityDescriptor(
                "praksis.agent.proposals",
                EffectClass.READ_ONLY,
                true,
                List.of(),
                List.of(out("items", ValueType.LIST))),
            new CapabilityDescriptor(
                "praksis.notes.save",
                EffectClass.LOCAL_WRITE,
                true,
                List.of(
                    in("title", ValueType.STRING, true, false),
                    in("text", ValueType.STRING, true, false)),
                List.of(out("id", ValueType.STRING))),
            new CapabilityDescriptor(
                "praksis.history.find",
                EffectClass.READ_ONLY,
                true,
                List.of(
                    in("text", ValueType.STRING, false, false),
                    in("type", ValueType.STRING, false, false),
                    in("since_hours", ValueType.INT, false, false),
                    in("limit", ValueType.INT, false, false)),
                List.of(
                    out("items", ValueType.LIST),
                    out("searched", ValueType.INT),
                    out("report", ValueType.STRING))),
            new CapabilityDescriptor(
                "praksis.history.trace",
                EffectClass.READ_ONLY,
                true,
                List.of(in("event", ValueType.STRING, true, false)),
                List.of(
                    out("event", ValueType.MAP),
                    out("steps", ValueType.LIST),
                    out("calls", ValueType.LIST),
                    out("rules", ValueType.LIST),
                    out("report", ValueType.STRING))),
            new CapabilityDescriptor(
                "praksis.history.summary",
                EffectClass.READ_ONLY,
                true,
                List.of(in("since_hours", ValueType.INT, false, false)),
                List.of(
                    out("since", ValueType.STRING),
                    out("events", ValueType.MAP),
                    out("rules", ValueType.LIST),
                    out("calls", ValueType.MAP),
                    out("refused", ValueType.LIST),
                    out("report", ValueType.STRING))),
            new CapabilityDescriptor(
                "praksis.notes.list",
                EffectClass.READ_ONLY,
                true,
                List.of(in("limit", ValueType.INT, false, false)),
                List.of(out("items", ValueType.LIST)))),
        List.of());
  }

  /**
   * The modules with the built-in one; its capabilities work once {@link Agent#bind} was called.
   */
  static LoadedModules add(LoadedModules modules, Agent agent) {
    return modules.with(descriptor(), agent.capabilities());
  }
}
