// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WebGateTest {
  private static final WebTokens.Token FULL = new WebTokens.Token("laptop", "h", false, "t");
  private static final WebTokens.Token LOOK = new WebTokens.Token("wall", "h", true, "t");
  private final List<Request> handled = new ArrayList<>();

  private Response ask(WebTokens.Token t, Request r) {
    return WebGate.decide(
        t,
        r,
        x -> {
          handled.add(x);
          return new Response.Done("ok");
        });
  }

  @Test
  void lookingIsOpenToEveryToken() {
    for (Request r :
        List.of(
            new Request.Status(),
            new Request.Log(5),
            new Request.Views(),
            new Request.Commands(),
            new Request.EntityTypes(),
            new Request.Changes(0, 0),
            new Request.EntityRead("invoice", "1", List.of()))) {
      assertInstanceOf(Response.Done.class, ask(LOOK, r), r.toString());
    }
    assertEquals(7, handled.size());
  }

  @Test
  void aCommandNeedsATokenThatMayDoMoreThanLook() {
    var run = new Request.RunCommand("mail.send", Map.of("to", "x"), "forged");
    assertInstanceOf(Response.Error.class, ask(LOOK, run));
    assertTrue(handled.isEmpty());
    assertInstanceOf(Response.Done.class, ask(FULL, run));
    // the client name cannot be chosen by the caller: the audit line names the token
    assertEquals("web:laptop", ((Request.RunCommand) handled.get(0)).client());
  }

  @Test
  void nothingThatAnswersChangesOrSpendsAPermissionIsReachable() {
    List<Request> forbidden =
        List.of(
            new Request.Answer(1, "always", "replay"),
            new Request.Revoke("g1"),
            new Request.Reload(),
            new Request.ShadowPromote("r"),
            new Request.ShadowReset("r"),
            new Request.LayersUpdate(false, true, List.of()),
            new Request.ProposalApprove("p", "c", true),
            new Request.ProposalReject("p", "no"),
            new Request.Missed("r", "replay"),
            new Request.AgentPlanRun("1"),
            new Request.AgentPlanDrop("1"),
            new Request.StagedDrop("1"),
            new Request.Inject("t", "x", "k", Map.of()),
            new Request.Verify(false, 0, false, Map.of()),
            new Request.PinSet("acme", "finance", "pin"),
            new Request.RemoteAnswer(1, "approve"),
            new Request.EntityCreate("invoice", Map.of()),
            new Request.EntityUpdate("invoice", "1", Map.of()),
            new Request.EntityDelete("invoice", "1"),
            new Request.Upload(),
            new Request.Dictate("v", "c"),
            new Request.Stop(),
            new Request.PeerCall("entity.read", Map.of(), false),
            new Request.PeerCatalog());
    for (Request r : forbidden) {
      for (WebTokens.Token t : List.of(FULL, LOOK)) {
        assertInstanceOf(Response.Error.class, ask(t, r), r.toString());
      }
    }
    assertTrue(handled.isEmpty());
  }

  @Test
  void revealingProtectedFieldsIsRefused() {
    assertInstanceOf(
        Response.Error.class, ask(FULL, new Request.EntityRead("person", "1", List.of("iban"))));
    assertTrue(handled.isEmpty());
  }
}
