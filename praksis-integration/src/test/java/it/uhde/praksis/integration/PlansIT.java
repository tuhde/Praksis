// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Plans against the runtime image: a rule stands in for the model and proposes literal calls; the
 * person looks at the plan, and only the approval runs it, under the grants of the layer that
 * {@code [plan]} names.
 */
class PlansIT {
  private static final String PROPOSE =
      """
      id = "plan.make"
      effect = "local-write"
      needs = ["praksis.plan.propose"]

      [trigger]
      kind = "command"
      title = "Make a plan"
      [[trigger.param]]
      name = "message"
      type = "string"
      [[trigger.param]]
      name = "folder"
      type = "string"

      [[action]]
      id = "propose"
      capability = "praksis.plan.propose"
      args = { request = "file the mail", reason = "test", steps = { cel = "[{'capability': 'mail.move', 'args': {'message': event.data.message, 'folder': event.data.folder}}]" } }
      """;

  private static Path mailInCur(Harness h, String name) throws Exception {
    Path mail = h.mail.resolve("cur").resolve(name);
    Files.writeString(mail, "From: a@example.org\nSubject: x\n\nbody\n");
    return mail;
  }

  private static Response.Plan only(Response r, String status) {
    Response.PlanList list = (Response.PlanList) r;
    return list.items().stream()
        .filter(p -> p.status().equals(status))
        .findFirst()
        .orElseThrow(() -> new AssertionError(status + " in " + list));
  }

  @Test
  void aPlanIsCheckedAgainstTheGrantsAndOnlyAPersonRunsItAllOrNothing() throws Exception {
    try (Harness h = new Harness()) {
      Files.writeString(h.home.resolve("ruleset/rules/plan.make.toml"), PROPOSE);
      Files.writeString(
          h.home.resolve("policy.toml"),
          Files.readString(h.home.resolve("policy.toml"))
              + "\n[[grant]]\nid = \"plan-propose\"\nlayer = \"ruleset\"\n"
              + "capability = \"praksis.plan.propose\"\n");
      Files.writeString(
          h.home.resolve("praksis.toml"),
          Files.readString(h.home.resolve("praksis.toml")) + "\n[plan]\nlayer = \"ruleset\"\n");
      Process daemon = h.startDaemon();

      Path good = mailInCur(h, "1.a:2,");
      Path bad = mailInCur(h, "2.b:2,");
      assertThat(h.cli("run", "plan.make", "message=" + good, "folder=Archive").exit()).isZero();
      assertThat(h.cli("run", "plan.make", "message=" + bad, "folder=Spam").exit()).isZero();

      // nothing has moved: a plan is only a proposal
      assertThat(h.names(".Archive/cur")).isEmpty();
      Response.PlanList plans = (Response.PlanList) h.ask(new Request.AgentPlans());
      assertThat(plans.items()).hasSize(2);

      // the step that no grant covers is marked, and that plan cannot be run
      Response.Plan refusedPlan =
          plans.items().stream()
              .filter(p -> p.steps().get(0).args().get("folder").equals("Spam"))
              .findFirst()
              .orElseThrow();
      Response.PlanDetail refused =
          (Response.PlanDetail) h.ask(new Request.AgentPlanShow(refusedPlan.id()));
      assertThat(refused.runnable()).isFalse();
      assertThat(refused.why()).contains("step 1 is not covered");
      assertThat(refused.plan().steps().get(0).allowed()).isFalse();
      Response notRun = h.ask(new Request.AgentPlanRun(refusedPlan.id()));
      assertThat(notRun).isInstanceOf(Response.Error.class);
      assertThat(h.names(".Spam/cur")).isEmpty();

      // the other one runs when a person approves it, once
      Response.Plan goodPlan =
          plans.items().stream()
              .filter(p -> p.steps().get(0).args().get("folder").equals("Archive"))
              .findFirst()
              .orElseThrow();
      Response.PlanDetail shown =
          (Response.PlanDetail) h.ask(new Request.AgentPlanShow(goodPlan.id()));
      assertThat(shown.runnable()).isTrue();
      Response.PlanDetail ran =
          (Response.PlanDetail) h.ask(new Request.AgentPlanRun(goodPlan.id()));
      assertThat(ran.plan().status()).isEqualTo("DONE");
      assertThat(h.names(".Archive/cur")).containsExactly("1.a:2,");
      assertThat(h.ask(new Request.AgentPlanRun(goodPlan.id()))).isInstanceOf(Response.Error.class);

      // the approval and the call are on the record, under the plan
      String audit = h.cli("audit", "-n", "100").output();
      assertThat(audit).contains("plan.approved").contains("plan:" + goodPlan.id());

      // a plan can be thrown away
      assertThat(h.ask(new Request.AgentPlanDrop(refusedPlan.id())))
          .isInstanceOf(Response.Done.class);
      assertThat(((Response.PlanList) h.ask(new Request.AgentPlans())).items()).hasSize(1);
      h.stop(daemon);
    }
  }
}
