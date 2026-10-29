// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.capability.EventSink;
import it.uhde.praksis.capability.EventSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns the new mails of the Maildir of every account into {@code mail.received} events. Each
 * account is watched on its own, by an {@link AccountPoller}; an event names its account.
 */
public final class MaildirSource implements EventSource {
  private final Map<String, AccountPoller> pollers = new LinkedHashMap<>();

  @Override
  public String name() {
    return "maildir.inbox";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    pollers.clear();
    Accounts.parse(config, Settings::parse)
        .forEach((name, settings) -> pollers.put(name, new AccountPoller(name, settings)));
  }

  @Override
  public synchronized void start(EventSink sink) throws CapabilityException {
    if (pollers.isEmpty()) {
      throw new CapabilityException("maildir source is not configured", false);
    }
    List<AccountPoller> started = new ArrayList<>();
    try {
      for (AccountPoller p : pollers.values()) {
        p.start(sink);
        started.add(p);
      }
    } catch (CapabilityException | RuntimeException e) {
      started.forEach(AccountPoller::stop);
      throw e;
    }
  }

  @Override
  public synchronized void stop() {
    pollers.values().forEach(AccountPoller::stop);
  }
}
