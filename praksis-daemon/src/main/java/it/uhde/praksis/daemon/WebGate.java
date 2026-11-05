// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.util.function.Function;

/**
 * Decides what a web client may ask for. A list of what is allowed, not of what is forbidden: a
 * request type that is not named here is refused, so a request added to the protocol later is not
 * reachable from the web until someone decides it should be.
 *
 * <p>The first slice lets a client <em>look</em> (screens, views, entities, the logs, the catalog)
 * and, unless its token is read-only, <em>start commands</em> that the ruleset offers to a person.
 * Everything that gives, widens or answers a permission, changes the ruleset or the policy, makes
 * the daemon do something outside the screens or exposes a secret is left to the Unix socket, which
 * only the owner of the machine can open: approvals, grants, shadow decisions, proposals, layer
 * updates, plans, pins, requests of other daemons, entity writes, injecting events, replaying
 * missed events, reloading, stopping, and the microphone and file upload of a device. That way a
 * stolen token, or an AI agent that was given one, cannot approve anything.
 */
final class WebGate {
  private static final System.Logger LOG = System.getLogger(WebGate.class.getName());

  private WebGate() {}

  /** Whether the request only looks. */
  static boolean looks(Request r) {
    return switch (r) {
      case Request.Status s -> true;
      case Request.Log s -> true;
      case Request.Audit s -> true;
      case Request.Approvals s -> true;
      case Request.Grants s -> true;
      case Request.Shadow s -> true;
      case Request.Changes s -> true;
      case Request.Effective s -> true;
      case Request.Layers s -> true;
      case Request.Proposals s -> true;
      case Request.ProposalShow s -> true;
      case Request.Commands s -> true;
      case Request.Views s -> true;
      case Request.Help s -> true;
      case Request.ViewResolve s -> true;
      case Request.Lookup s -> true;
      case Request.Fetch s -> true;
      case Request.Outcome s -> true;
      case Request.Notes s -> true;
      case Request.Untested s -> true;
      case Request.Staged s -> true;
      case Request.StagedShow s -> true;
      case Request.AgentPlans s -> true;
      case Request.AgentPlanShow s -> true;
      case Request.EntityTypes s -> true;
      case Request.EntitySets s -> true;
      case Request.EntityQuery s -> true;
      case Request.EntityRead s -> s.reveal() == null || s.reveal().isEmpty();
      case Request.EntityExport s -> true;
      case Request.Inspect s -> true;
      // a candidate may be checked, but moving its layers to their latest commits fetches
      case Request.Verify s -> !s.update();
      case Request.Peers s -> true;
      case Request.RemoteRequests s -> true;
      default -> false;
    };
  }

  /**
   * Answers the request through {@code handler} if the token may ask for it, else with an error.
   */
  static Response decide(
      WebTokens.Token token, Request request, Function<Request, Response> handler) {
    if (looks(request)) {
      return handler.apply(request);
    }
    if (request instanceof Request.RunCommand run) {
      if (token.readOnly()) {
        return refuse(token, request, "this token is read only");
      }
      // the audit line of the start names the web client
      return handler.apply(
          new Request.RunCommand(run.command(), run.args(), "web:" + token.name()));
    }
    if (request instanceof Request.EntityRead) {
      return refuse(token, request, "revealing protected fields is not offered over the web");
    }
    return refuse(
        token,
        request,
        "this can only be done at the daemon itself (the command line or the desktop program)");
  }

  private static Response refuse(WebTokens.Token token, Request request, String why) {
    LOG.log(
        System.Logger.Level.WARNING,
        "web client "
            + token.name()
            + " refused: "
            + request.getClass().getSimpleName()
            + ": "
            + why);
    return new Response.Error(why);
  }
}
