// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.engine.UnavailableException;
import java.io.IOException;
import java.util.Map;

/**
 * A capability of another daemon as a rule sees it: {@code acme/entity.read}. Nothing of it is
 * installed here; the call is forwarded and runs at the remote under the remote's grants. The
 * engine has decided the local side already (the grants of the rule's layer for the qualified
 * name), so the forwarding does not ask the local policy a second time.
 */
final class RemoteCapability implements Capability {
  private final String peer;
  private final String capability;
  private final RemoteCalls calls;

  /**
   * @param calls how to reach the remote; null where nothing can be called (a check only)
   */
  RemoteCapability(String peer, String capability, RemoteCalls calls) {
    this.peer = peer;
    this.capability = capability;
    this.calls = calls;
  }

  @Override
  public String name() {
    return peer + "/" + capability;
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (calls == null) {
      throw new UnavailableException(name() + " can only be called by a running daemon");
    }
    try {
      return calls.call(context.ruleId().orElse("?"), peer, capability, args, context.dryRun());
    } catch (RemoteCalls.Unreachable e) {
      throw new UnavailableException(e.getMessage(), e);
    } catch (IOException e) {
      throw new CapabilityException(e.getMessage(), false, e);
    }
  }
}
