// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.ruleset;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import it.uhde.praksis.capability.CapabilityDescriptor;
import it.uhde.praksis.capability.FieldDescriptor;
import it.uhde.praksis.capability.ValueType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The local grants, read from {@code policy.toml} in the Praksis home. Nothing is allowed unless a
 * grant covers it. The file is never part of a ruleset repository, so a ruleset cannot grant itself
 * anything.
 *
 * @param grants the grants, in file order
 * @param credentials the credential bindings
 * @param trust whose commits count as the user's own
 * @param approval which changes need the user
 */
public record Policy(
    List<Grant> grants, List<CredentialBinding> credentials, Trust trust, ApprovalRules approval) {
  private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]*");
  private static final TomlMapper MAPPER = new TomlMapper();

  private static final Pattern CREDENTIAL = Pattern.compile("[a-z0-9][a-z0-9._:-]*");

  public Policy {
    grants = List.copyOf(grants);
    credentials = List.copyOf(credentials);
    java.util.Objects.requireNonNull(trust, "trust");
    java.util.Objects.requireNonNull(approval, "approval");
  }

  /** A policy with grants and bindings, and the strict rules for approval. */
  public Policy(List<Grant> grants, List<CredentialBinding> credentials) {
    this(grants, credentials, Trust.none(), ApprovalRules.strict());
  }

  /** A policy with grants only. */
  public Policy(List<Grant> grants) {
    this(grants, List.of());
  }

  /** A policy that allows nothing. */
  public static Policy empty() {
    return new Policy(List.of());
  }

  /**
   * Reads the policy file. A file that does not exist is an empty policy.
   *
   * @throws RulesetException with every structural problem found
   */
  public static Policy load(Path file) throws RulesetException {
    if (!Files.exists(file)) {
      return empty();
    }
    List<Problem> problems = new ArrayList<>();
    String where = file.getFileName().toString();
    Table t;
    try {
      Map<String, Object> map = MAPPER.readValue(file.toFile(), new TypeReference<>() {});
      t = new Table(map, where, problems);
    } catch (IOException e) {
      throw new RulesetException(List.of(new Problem(where, "cannot read: " + e.getMessage())));
    }
    t.onlyKeys(Set.of("format_version", "grant", "credential", "trust", "approval"));
    int version = RulesetLoader.formatVersion(t);
    if (version != 0 && version != 1) {
      t.problem("unsupported 'format_version' " + version);
    }
    List<Grant> grants = new ArrayList<>();
    Set<String> ids = new HashSet<>();
    for (Table g : t.tables("grant")) {
      grant(g, ids).ifPresent(grants::add);
    }
    List<CredentialBinding> credentials = new ArrayList<>();
    t.table("credential", false)
        .ifPresent(
            c -> {
              for (var e : c.subTables().entrySet()) {
                credential(e.getKey(), e.getValue()).ifPresent(credentials::add);
              }
            });
    Trust trust =
        t.table("trust", false)
            .map(
                tt -> {
                  tt.onlyKeys(Set.of("human_keys"));
                  List<String> keys = tt.strings("human_keys");
                  if (keys.stream().anyMatch(String::isBlank)) {
                    tt.problem("'human_keys' must not contain an empty key");
                  }
                  return new Trust(keys);
                })
            .orElse(Trust.none());
    ApprovalRules approval =
        t.table("approval", false)
            .map(
                a -> {
                  a.onlyKeys(
                      Set.of(
                          "widening_access",
                          "destructive_or_external",
                          "user_rules",
                          "shadow_first"));
                  return new ApprovalRules(
                      a.bool("widening_access", true),
                      a.bool("destructive_or_external", true),
                      a.bool("user_rules", true),
                      a.bool("shadow_first", true));
                })
            .orElse(ApprovalRules.strict());
    if (!problems.isEmpty()) {
      throw new RulesetException(problems);
    }
    return new Policy(grants, credentials, trust, approval);
  }

  private static Optional<Grant> grant(Table g, Set<String> ids) {
    g.onlyKeys(Set.of("id", "layer", "capability", "note", "args"));
    Optional<String> id = g.string("id", true);
    Optional<String> layer = g.string("layer", true);
    Optional<String> capability = g.string("capability", true);
    Optional<String> note = g.string("note", false);
    id.ifPresent(
        i -> {
          if (!ID.matcher(i).matches()) {
            g.problem("'id' must be lower case letters, digits, '.', '_' or '-'");
          } else if (!ids.add(i)) {
            g.problem("grant id '" + i + "' is used twice");
          }
        });
    Map<String, ArgMatcher> args = new LinkedHashMap<>();
    g.table("args", false)
        .ifPresent(
            a -> {
              for (var e : a.subTables().entrySet()) {
                matcher(e.getValue()).ifPresent(m -> args.put(e.getKey(), m));
              }
            });
    if (id.isEmpty() || layer.isEmpty() || capability.isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(new Grant(id.get(), layer.get(), capability.get(), args, note));
  }

  private static Optional<CredentialBinding> credential(String name, Table c) {
    c.onlyKeys(Set.of("command", "capabilities", "note"));
    if (!CREDENTIAL.matcher(name).matches()) {
      c.problem("the name must be lower case letters, digits, '.', '_', ':' or '-'");
    }
    List<String> command = c.strings("command");
    List<String> components = c.strings("capabilities");
    if (command.isEmpty() || command.stream().anyMatch(String::isBlank)) {
      c.problem("'command' must be a list with the command and its arguments");
    }
    if (components.isEmpty()) {
      c.problem("'capabilities' must list the capabilities and event sources that may ask for it");
    }
    Optional<String> note = c.string("note", false);
    return Optional.of(new CredentialBinding(name, command, components, note));
  }

  private static Optional<ArgMatcher> matcher(Table m) {
    m.onlyKeys(Set.of("equals", "one_of", "prefix"));
    if (m.rawEntries().size() != 1) {
      m.problem("must have exactly one of 'equals', 'one_of' or 'prefix'");
      return Optional.empty();
    }
    var entry = m.rawEntries().entrySet().iterator().next();
    Object v = entry.getValue();
    switch (entry.getKey()) {
      case "equals" -> {
        if (plain(v)) {
          return Optional.of(new ArgMatcher.Equals(v));
        }
        m.problem("'equals' must be a string, integer or true/false");
      }
      case "one_of" -> {
        if (v instanceof List<?> l && !l.isEmpty() && l.stream().allMatch(Policy::plain)) {
          return Optional.of(new ArgMatcher.OneOf(new ArrayList<>(l)));
        }
        m.problem("'one_of' must be a non-empty list of strings, integers or true/false");
      }
      case "prefix" -> {
        if (v instanceof String s && !s.isEmpty()) {
          return Optional.of(new ArgMatcher.Prefix(s));
        }
        m.problem("'prefix' must be a non-empty string");
      }
      default -> {
        // reported by onlyKeys
      }
    }
    return Optional.empty();
  }

  private static boolean plain(Object v) {
    return v instanceof String || v instanceof Boolean || v instanceof Integer || v instanceof Long;
  }

  /** The first grant that covers a call of the rules of this layer, if there is one. */
  public Optional<Grant> find(String layer, String capability, Map<String, Object> args) {
    return grants.stream().filter(g -> g.covers(layer, capability, args)).findFirst();
  }

  /**
   * Checks the grants against the installed modules and the configured layers: the capability
   * exists, matchers are only on parameters that a grant may constrain, and a prefix only on
   * strings.
   *
   * @param catalog the installed modules
   * @param layers the names of the configured layers
   */
  public List<Problem> check(ModuleCatalog catalog, Set<String> layers) {
    List<Problem> problems = new ArrayList<>();
    for (Grant g : grants) {
      String where = "policy.toml: grant '" + g.id() + "'";
      if (!layers.contains(g.layer())) {
        problems.add(new Problem(where, "no layer named '" + g.layer() + "'"));
      }
      Optional<CapabilityDescriptor> capability = catalog.capability(g.capability());
      if (capability.isEmpty()) {
        problems.add(new Problem(where, "no capability named '" + g.capability() + "'"));
        continue;
      }
      for (var e : g.args().entrySet()) {
        Optional<FieldDescriptor> param =
            capability.get().params().stream().filter(p -> p.name().equals(e.getKey())).findFirst();
        if (param.isEmpty()) {
          problems.add(
              new Problem(where, g.capability() + " has no parameter '" + e.getKey() + "'"));
        } else if (!param.get().scopable()) {
          problems.add(
              new Problem(where, "parameter '" + e.getKey() + "' cannot be restricted by a grant"));
        } else if (e.getValue() instanceof ArgMatcher.Prefix
            && param.get().type() != ValueType.STRING) {
          problems.add(new Problem(where, "'prefix' only works on strings: '" + e.getKey() + "'"));
        }
      }
    }
    for (CredentialBinding b : credentials) {
      String where = "policy.toml: credential '" + b.name() + "'";
      for (String component : b.components()) {
        if (catalog.capability(component).isEmpty() && !catalog.hasSource(component)) {
          problems.add(
              new Problem(where, "no capability or event source named '" + component + "'"));
        }
      }
    }
    return problems;
  }

  /** The binding with this name, if there is one. */
  public Optional<CredentialBinding> credential(String name) {
    return credentials.stream().filter(b -> b.name().equals(name)).findFirst();
  }
}
