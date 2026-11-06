// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import it.uhde.praksis.capability.CapabilityException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentInspectTest {
  @Test
  void onlyTheFiveReadingQuestionsAreAnswered() {
    for (String what :
        new String[] {
          "agent.propose", "notes.save", "plan.propose", "praksis.history.find", "", null
        }) {
      CapabilityException e =
          assertThrows(CapabilityException.class, () -> new Agent().inspect(what, Map.of()), what);
      assertTrue(e.getMessage().contains("unknown question"), e.getMessage());
    }
  }

  @Test
  void onlyThreeKindsOfProposalAreTaken() {
    for (String what :
        new String[] {
          "rules.read", "plan.read", "plan.run", "layers.update", "entity.create", "", null
        }) {
      CapabilityException e =
          assertThrows(
              CapabilityException.class, () -> new Agent().stage(what, Map.of(), "web:x"), what);
      assertTrue(e.getMessage().contains("unknown kind"), e.getMessage());
    }
  }
}
