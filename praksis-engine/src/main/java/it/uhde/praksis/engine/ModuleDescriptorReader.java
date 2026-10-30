// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.EffectClass;
import it.uhde.praksis.capability.EventTypeDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ModuleDescriptor;
import it.uhde.praksis.capability.ValueType;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Table;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Reads a module's {@code module.toml}:
 *
 * <pre>
 * format_version = 1
 * api_version = 1                              # the capability API it was built for
 * name = "maildir"
 * version = "0.1.0"
 * module = "it.uhde.praksis.module.maildir"   # the JPMS module that provides the services
 * jdk_modules = ["java.sql"]
 * sources = ["maildir.inbox"]
 *
 * [[capability]]
 * name = "mail.move"
 * effect = "local-write"
 * dry_run = true
 * params = [{ name = "message", type = "string", required = true, scopable = false }]
 * results = [{ name = "path", type = "string" }]
 *
 * [[event]]
 * type = "mail.received"
 * fields = [{ name = "subject", type = "string" }]
 * </pre>
 */
final class ModuleDescriptorReader {
  private static final TomlMapper MAPPER = new TomlMapper();

  /** The descriptor and the name of the JPMS module that provides the services. */
  record Result(ModuleDescriptor descriptor, String moduleName, int apiVersion) {}

  private ModuleDescriptorReader() {}

  static Result read(Path file) throws ModuleLoadException {
    List<Problem> problems = new ArrayList<>();
    Map<String, Object> map;
    try {
      map = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
    } catch (IOException e) {
      throw new ModuleLoadException("cannot read " + file + ": " + e.getMessage(), e);
    }
    Table t = new Table(map, "module.toml", problems);
    t.onlyKeys(
        Set.of(
            "format_version",
            "api_version",
            "name",
            "version",
            "module",
            "jdk_modules",
            "sources",
            "capability",
            "event"));
    if (!t.raw("format_version").map(v -> v.equals(1) || v.equals(1L)).orElse(false)) {
      t.problem("'format_version' must be 1");
    }
    int apiVersion =
        t.raw("api_version")
            .filter(v -> v instanceof Number)
            .map(v -> ((Number) v).intValue())
            .orElse(-1);
    if (apiVersion < 1) {
      t.problem("'api_version' is required: the capability API version the module was built for");
    }
    String name = t.string("name", true).orElse("");
    String version = t.string("version", true).orElse("");
    String module = t.string("module", true).orElse("");

    List<CapabilityDescriptor> capabilities = new ArrayList<>();
    for (Table c : t.tables("capability")) {
      c.onlyKeys(Set.of("name", "effect", "dry_run", "params", "results"));
      String capName = c.string("name", true).orElse("");
      EffectClass effect =
          c.string("effect", true)
              .map(
                  id ->
                      EffectClass.fromId(id)
                          .orElseGet(
                              () -> {
                                c.problem("unknown effect '" + id + "'");
                                return null;
                              }))
              .orElse(null);
      if (effect != null) {
        capabilities.add(
            new CapabilityDescriptor(
                capName,
                effect,
                c.bool("dry_run", false),
                fields(c, "params"),
                fields(c, "results")));
      }
    }
    List<EventTypeDescriptor> events = new ArrayList<>();
    for (Table e : t.tables("event")) {
      e.onlyKeys(Set.of("type", "fields"));
      events.add(new EventTypeDescriptor(e.string("type", true).orElse(""), fields(e, "fields")));
    }
    if (!problems.isEmpty()) {
      throw new ModuleLoadException(
          "invalid "
              + file
              + ":\n"
              + problems.stream().map(Problem::toString).collect(Collectors.joining("\n")));
    }
    return new Result(
        new ModuleDescriptor(
            name, version, t.strings("jdk_modules"), capabilities, events, t.strings("sources")),
        module,
        apiVersion);
  }

  private static List<FieldDescriptor> fields(Table owner, String key) {
    List<FieldDescriptor> out = new ArrayList<>();
    for (Table f : owner.tables(key)) {
      f.onlyKeys(Set.of("name", "type", "required", "scopable"));
      String name = f.string("name", true).orElse("");
      String typeId = f.string("type", true).orElse("");
      ValueType type = ValueType.fromId(typeId).orElse(null);
      if (type == null) {
        if (!typeId.isEmpty()) {
          f.problem("unknown type '" + typeId + "'");
        }
        continue;
      }
      out.add(
          new FieldDescriptor(name, type, f.bool("required", false), f.bool("scopable", false)));
    }
    return out;
  }
}
