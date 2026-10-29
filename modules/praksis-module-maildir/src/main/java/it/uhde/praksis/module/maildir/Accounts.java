// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The accounts of the module. The settings are either those of one account (as they always were:
 * {@code [module.maildir] root = ...}), which is then called {@code default}, or several accounts,
 * each in a table of its own: {@code [module.maildir.work]}. A mail is named by its path, which
 * says which account it is in; only a call without a mail (a list, a search) needs the argument
 * {@code account}.
 */
final class Accounts {
  /** The name of the account when there is only one and it was not given a name. */
  static final String DEFAULT = "default";

  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9_-]{0,30}");

  private Accounts() {}

  /** A parser of the settings of one account. */
  interface Parser<S> {
    S parse(Map<String, Object> settings) throws CapabilityException;
  }

  /** The settings of every account, in the order of the file. */
  static <S> Map<String, S> parse(Map<String, Object> module, Parser<S> parser)
      throws CapabilityException {
    boolean anyTable = module.values().stream().anyMatch(v -> v instanceof Map<?, ?>);
    Map<String, S> out = new LinkedHashMap<>();
    if (!anyTable) {
      out.put(DEFAULT, parser.parse(module));
      return out;
    }
    for (var entry : module.entrySet()) {
      if (!(entry.getValue() instanceof Map<?, ?> table)) {
        throw new CapabilityException(
            "the setting '"
                + entry.getKey()
                + "' is outside of the accounts: either all settings are those of one account, or"
                + " every account has a table of its own, such as [module.maildir.work]",
            false);
      }
      if (!NAME.matcher(entry.getKey()).matches()) {
        throw new CapabilityException(
            "the account name '"
                + entry.getKey()
                + "' must be lower case letters, digits, - and _, starting with a letter",
            false);
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> settings = (Map<String, Object>) table;
      out.put(entry.getKey(), parser.parse(settings));
    }
    return out;
  }

  /**
   * The account a call is for: the one whose Maildir holds the mail, else the one named in {@code
   * account}, else the only one.
   */
  static Settings settingsFor(Map<String, Settings> accounts, Map<String, Object> args)
      throws CapabilityException {
    if (args.get("message") instanceof String message) {
      Path path = Path.of(message).toAbsolutePath().normalize();
      for (Settings s : accounts.values()) {
        if (path.startsWith(s.root())) {
          return s;
        }
      }
      if (accounts.size() == 1) {
        return accounts.values().iterator().next(); // the mail checks say what is wrong with it
      }
      throw new CapabilityException(
          "the mail "
              + message
              + " is in none of the Maildirs of the accounts "
              + accounts.keySet(),
          false);
    }
    return accounts.get(choose(accounts, args.get("account")));
  }

  /** The name of the account a call without a mail is for. */
  static <S> String choose(Map<String, S> accounts, Object named) throws CapabilityException {
    if (named instanceof String name && !name.isBlank()) {
      if (!accounts.containsKey(name)) {
        throw new CapabilityException(
            "there is no account '" + name + "'; the accounts are " + accounts.keySet(), false);
      }
      return name;
    }
    if (accounts.size() == 1) {
      return accounts.keySet().iterator().next();
    }
    throw new CapabilityException(
        "there are several accounts " + accounts.keySet() + "; say which with 'account'", false);
  }

  /** The name of the account whose Maildir this is. */
  static String nameOf(Map<String, Settings> accounts, Settings settings) {
    return accounts.entrySet().stream()
        .filter(e -> e.getValue() == settings)
        .map(Map.Entry::getKey)
        .findFirst()
        .orElse(DEFAULT);
  }
}
