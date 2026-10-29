// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists the accounts of the module: the name and the directory, and whether the directory is there.
 * There is nothing to log in to, so {@code check} only looks again. Only reads.
 */
public final class MailAccounts implements Capability {
  private final Map<String, Settings> accounts = new LinkedHashMap<>();

  @Override
  public String name() {
    return "mail.accounts";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    accounts.putAll(Accounts.parse(config, Settings::parse));
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    List<Object> items = new ArrayList<>();
    for (var entry : accounts.entrySet()) {
      boolean there = Files.isDirectory(entry.getValue().root().resolve("cur"));
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("name", entry.getKey());
      item.put("kind", "maildir");
      item.put("address", entry.getValue().root().toString());
      item.put("folder", "INBOX");
      item.put("state", there ? "ok" : "error");
      item.put("error", there ? "" : "the directory is not a Maildir any more");
      item.put("checked", java.time.Instant.now().toString());
      items.add(item);
    }
    return Map.of("items", items);
  }
}
