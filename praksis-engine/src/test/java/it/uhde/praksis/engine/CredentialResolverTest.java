// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.capability.CapabilityException;
import it.uhde.praksis.ruleset.CredentialBinding;
import it.uhde.praksis.ruleset.Policy;
import it.uhde.praksis.store.AuditEntry;
import it.uhde.praksis.store.Store;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialResolverTest {
  @TempDir Path dir;

  private Store store;
  private final AtomicReference<Policy> policy = new AtomicReference<>(Policy.empty());
  private CredentialResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    store = Store.open(dir.resolve("praksis.db"));
    resolver = new CredentialResolver(policy::get, store, Clock.systemUTC());
  }

  @AfterEach
  void tearDown() throws Exception {
    store.close();
  }

  private void bind(String name, String shell, String... components) {
    policy.set(
        new Policy(
            List.of(),
            List.of(
                new CredentialBinding(
                    name, List.of("sh", "-c", shell), List.of(components), Optional.empty()))));
  }

  private List<AuditEntry> audit() throws Exception {
    return store.recentAudit(20);
  }

  @Test
  void aBoundComponentGetsTheSecretWithoutItsLineBreakAndTheRequestIsAudited() throws Exception {
    bind("imap:work", "printf 'hunter2\\n'", "imap.inbox", "mail.move");
    assertThat(resolver.forComponent("imap.inbox").secret("imap:work")).isEqualTo("hunter2");
    assertThat(resolver.forComponent("mail.move").secret("imap:work")).isEqualTo("hunter2");

    assertThat(audit())
        .extracting(e -> e.kind() + " " + e.subject() + " " + e.allowed() + " " + e.detail())
        .containsExactly(
            "CREDENTIAL imap:work true {\"component\":\"imap.inbox\"}",
            "CREDENTIAL imap:work true {\"component\":\"mail.move\"}");
    assertThat(audit()).noneMatch(e -> e.toString().contains("hunter2"));
  }

  @Test
  void aComponentThatIsNotNamedInTheBindingIsRefused() throws Exception {
    bind("imap:work", "printf secret", "imap.inbox");
    assertThatThrownBy(() -> resolver.forComponent("mail.move").secret("imap:work"))
        .isInstanceOf(CapabilityException.class)
        .hasMessage("credential 'imap:work' is not bound for mail.move");
    assertThatThrownBy(() -> resolver.forComponent("imap.inbox").secret("imap:other"))
        .hasMessage("credential 'imap:other' is not bound in policy.toml");
    assertThat(audit()).extracting(AuditEntry::allowed).containsExactly(false, false);
    assertThat(audit().get(0).error().orElseThrow()).contains("is not bound for mail.move");
  }

  @Test
  void aFailingCommandIsAnErrorThatNeverShowsWhatItPrinted() throws Exception {
    bind("a", "echo topsecret; exit 3", "c");
    assertThatThrownBy(() -> resolver.forComponent("c").secret("a"))
        .hasMessage("credential 'a': command 'sh' failed with exit code 3");
    bind("b", "printf ''", "c");
    assertThatThrownBy(() -> resolver.forComponent("c").secret("b"))
        .hasMessageContaining("printed nothing");
    policy.set(
        new Policy(
            List.of(),
            List.of(
                new CredentialBinding(
                    "d", List.of("/no/such/command"), List.of("c"), Optional.empty()))));
    assertThatThrownBy(() -> resolver.forComponent("c").secret("d"))
        .hasMessageContaining("cannot run credential 'd'");
    assertThat(audit()).hasSize(3).noneMatch(e -> e.allowed());
    assertThat(audit()).noneMatch(e -> e.toString().contains("topsecret"));
  }

  @Test
  void aChangedPolicyAppliesToTheNextRequestAndTheSecretIsReadAgain() throws Exception {
    bind("a", "printf one", "c");
    var credentials = resolver.forComponent("c");
    assertThat(credentials.secret("a")).isEqualTo("one");
    bind("a", "printf two", "c");
    assertThat(credentials.secret("a")).isEqualTo("two");
    policy.set(Policy.empty());
    assertThatThrownBy(() -> credentials.secret("a")).hasMessageContaining("is not bound");
  }
}
