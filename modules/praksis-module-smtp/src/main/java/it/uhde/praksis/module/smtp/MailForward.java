// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.smtp;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.util.Map;

/**
 * Makes the starting point of a forward: the subject with one "Fwd:", and the text with the
 * original below a line that says so, headers included. The recipients are left to the person, and
 * the forward starts no thread. Only computes; nothing is read or sent.
 */
public final class MailForward implements Capability {
  private Replies replies;

  @Override
  public String name() {
    return "mail.forward";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    replies = new Replies(SmtpSettings.parse(config));
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (replies == null) {
      throw new CapabilityException("mail.forward is not configured", false);
    }
    return replies.forward(args);
  }
}
