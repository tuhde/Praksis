// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import it.uhde.praksis.git.GitException;
import it.uhde.praksis.git.GitSource;
import it.uhde.praksis.ruleset.Problem;
import it.uhde.praksis.ruleset.Ruleset;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The layer commands, with git replaced by directories prepared in the test. */
class AdminTest {
  private static final String MAIL_URL = "https://example.org/Praksis-Mail";
  private static final String MINE_URL = "git@example.org:me/myPraksis.git";

  private static final String ARCHIVE =
      """
      id = "mail.archive"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"
      filter = "event.data.subject.startsWith('[list]')"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { path = { cel = "event.data.path" }, folder = "Lists" }
      """;

  private static final String SPAM =
      """
      id = "mail.spam"
      effect = "local-write"
      needs = ["mail.move"]

      [trigger]
      kind = "event"
      type = "mail.received"

      [[action]]
      id = "move"
      capability = "mail.move"
      args = { path = { cel = "event.data.path" }, folder = "Spam" }
      """;

  private static final String VIP_PATCH =
      """
      [[change]]
      path = "trigger.filter"
      expect = "event.data.subject.startsWith('[list]')"
      value = "event.data.subject.startsWith('[list]') && event.data.from != 'boss@example.org'"
      """;

  /** Pretends to be git: refs lead to commits, commits are prepared directories. */
  private static final class FakeGit implements GitSource {
    final Map<String, String> refs = new HashMap<>();
    final Map<String, Path> trees = new HashMap<>();
    int calls;
    boolean offline;

    @Override
    public String resolve(String name, String url, String ref) throws GitException {
      calls++;
      if (offline) {
        throw new GitException("no network");
      }
      String commit = refs.get(url + "@" + ref);
      if (commit == null) {
        throw new GitException("'" + ref + "' does not exist in " + url);
      }
      return commit;
    }

    @Override
    public void export(String name, String url, String commit, Path target) throws GitException {
      calls++;
      if (Files.exists(target)) {
        return;
      }
      Path tree = trees.get(commit);
      if (tree == null || offline) {
        throw new GitException("commit " + commit + " is not available");
      }
      try (Stream<Path> files = Files.walk(tree)) {
        for (Path f : files.toList()) {
          Path to = target.resolve(tree.relativize(f).toString());
          if (Files.isDirectory(f)) {
            Files.createDirectories(to);
          } else {
            Files.createDirectories(to.getParent());
            Files.copy(f, to);
          }
        }
      } catch (IOException e) {
        throw new GitException(e.getMessage(), e);
      }
    }
  }

  @TempDir Path dir;

  private Path home;
  private FakeGit git;
  private int counter;
  private String mail1;
  private String mine1;

  @BeforeEach
  void setUp() throws Exception {
    home = Files.createDirectories(dir.resolve("home"));
    git = new FakeGit();
    configure(
        """
        format_version = 1

        [[layer]]
        name = "mail"
        git = "%s"
        ref = "main"

        [[layer]]
        name = "mine"
        git = "%s"
        ref = "main"
        """
            .formatted(MAIL_URL, MINE_URL));
    mail1 = publish(MAIL_URL, mailTree("1", ARCHIVE, SPAM));
    mine1 = publish(MINE_URL, mineTree());
  }

  private void configure(String toml) throws IOException {
    Files.writeString(home.resolve("praksis.toml"), toml);
  }

  /** Distinct commit ids, also in their first ten characters. */
  private String commitId() {
    return String.format("%-40s", Integer.toHexString(++counter + 0x100)).replace(' ', '0');
  }

  private static String abbr(String commit) {
    return commit.substring(0, 10);
  }

  /** Makes the branch main of a repository lead to a new commit with these files. */
  private String publish(String url, Path tree) {
    String commit = commitId();
    git.trees.put(commit, tree);
    git.refs.put(url + "@main", commit);
    return commit;
  }

  private Path tree(Map<String, String> files) throws IOException {
    Path root = Files.createDirectories(dir.resolve("tree" + (++counter)));
    for (var e : files.entrySet()) {
      Path f = root.resolve(e.getKey());
      Files.createDirectories(f.getParent());
      Files.writeString(f, e.getValue());
    }
    return root;
  }

  private Path mailTree(String name, String archive, String spam) throws IOException {
    Map<String, String> files = new java.util.LinkedHashMap<>();
    files.put("ruleset.toml", "format_version = 1\nname = \"mail" + name + "\"\n");
    if (archive != null) {
      files.put("rules/mail.archive.toml", archive);
    }
    if (spam != null) {
      files.put("rules/mail.spam.toml", spam);
    }
    return tree(files);
  }

  private Path mineTree() throws IOException {
    return tree(
        Map.of(
            "ruleset.toml",
            "format_version = 1\nname = \"mine\"\n",
            "patch/mail.archive.toml",
            VIP_PATCH));
  }

  private record Result(int exit, String out, String err) {}

  private Result admin(Function<Ruleset, List<Problem>> validator, String... args)
      throws Exception {
    StringWriter out = new StringWriter();
    StringWriter err = new StringWriter();
    int exit =
        Admin.run(
            DaemonConfig.load(home),
            git,
            validator,
            List.of(args),
            new PrintWriter(out, true),
            new PrintWriter(err, true));
    return new Result(exit, out.toString(), err.toString());
  }

  private Result admin(String... args) throws Exception {
    return admin(r -> List.of(), args);
  }

  private String lock() throws IOException {
    Path file = home.resolve("rulesets.lock");
    return Files.exists(file) ? Files.readString(file) : "(no lockfile)";
  }

  @Test
  void nothingRunsBeforeTheLayersArePinned() throws Exception {
    assertThat(admin("layers", "status").out())
        .contains("mail: " + MAIL_URL + " @ main - not pinned yet")
        .contains("mine: " + MINE_URL + " @ main - not pinned yet");
    assertThatThrownBy(() -> new Layers(DaemonConfig.load(home), git).effective())
        .isInstanceOf(DaemonException.class)
        .hasMessageContaining("layer 'mail' is not pinned to a commit yet")
        .hasMessageContaining("praksis layers update");
    assertThat(git.calls).isZero();
  }

  @Test
  void theFirstUpdatePinsEveryLayerAndTheDaemonThenNeedsNoNetwork() throws Exception {
    Result r = admin("layers", "update");
    assertThat(r.exit()).as(r.err()).isZero();
    assertThat(r.out())
        .contains("mail: pinned to " + abbr(mail1))
        .contains("mine: pinned to " + abbr(mine1))
        .contains("The new ruleset is valid (2 rules).")
        .contains("The pins were written to rulesets.lock");
    assertThat(lock())
        .contains("[layer.mail]")
        .contains("git = \"" + MAIL_URL + "\"")
        .contains("ref = \"main\"")
        .contains("commit = \"" + mail1 + "\"");

    git.offline = true;
    var effective = new Layers(DaemonConfig.load(home), git).effective();
    assertThat(effective.ruleset().rules()).hasSize(2);
    assertThat(effective.origins().get(0).history()).containsExactly("mail: add", "mine: patch");
    assertThat(admin("layers", "status").out())
        .contains("mail: " + MAIL_URL + " @ main - pinned to " + abbr(mail1))
        .doesNotContain("not on disk");
  }

  @Test
  void anUpdateReportsWhatChangedInTheRules() throws Exception {
    assertThat(admin("layers", "update").exit()).isZero();
    String before = lock();

    String extra = SPAM.replace("mail.spam", "mail.extra");
    String mail2 =
        publish(
            MAIL_URL,
            tree(
                Map.of(
                    "ruleset.toml",
                    "format_version = 1\nname = \"mail2\"\n",
                    "rules/mail.archive.toml",
                    ARCHIVE.replace("Lists", "Lists2"),
                    "rules/mail.extra.toml",
                    extra)));

    Result r = admin("layers", "update");
    assertThat(r.exit()).as(r.err()).isZero();
    assertThat(r.out())
        .contains("mail: " + abbr(mail1) + " -> " + abbr(mail2) + " (main)")
        .contains("mine: unchanged")
        .contains("  + mail.extra (from mail)")
        .contains("  ~ mail.archive changed")
        .contains("  - mail.spam removed");
    assertThat(lock()).isNotEqualTo(before);
  }

  @Test
  void anUpdateThatTheOverlayDoesNotFitIsRefusedAndLeavesThePinsAlone() throws Exception {
    assertThat(admin("layers", "update").exit()).isZero();
    String before = lock();

    publish(MAIL_URL, mailTree("2", ARCHIVE.replace("[list]", "[lists]"), SPAM));
    Result r = admin("layers", "update");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.err())
        .contains("Not updated")
        .contains("layer 'mine': patch/mail.archive.toml")
        .contains("has changed since this patch was written")
        .contains("The pins are unchanged.");
    assertThat(lock()).isEqualTo(before);
  }

  @Test
  void anUpdateThatDoesNotFitTheModulesIsRefused() throws Exception {
    assertThat(admin("layers", "update").exit()).isZero();
    String before = lock();
    publish(MAIL_URL, mailTree("2", ARCHIVE, SPAM));

    Result r =
        admin(
            ruleset ->
                List.of(new Problem("rules/mail.archive.toml", "unknown capability 'mail.move'")),
            "layers",
            "update");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.err())
        .contains("unknown capability 'mail.move'")
        .contains("The pins are unchanged.");
    assertThat(lock()).isEqualTo(before);
  }

  @Test
  void aDryRunChangesNothingAndNamedLayersMoveAlone() throws Exception {
    assertThat(admin("layers", "update").exit()).isZero();
    String before = lock();
    publish(MAIL_URL, mailTree("2", ARCHIVE, SPAM));
    String mine2 = publish(MINE_URL, mineTree());

    Result dry = admin("layers", "update", "--dry-run");
    assertThat(dry.out()).contains("Dry run: the pins were not changed.");
    assertThat(lock()).isEqualTo(before);

    Result only = admin("layers", "update", "mine");
    assertThat(only.exit()).as(only.err()).isZero();
    assertThat(only.out())
        .contains("mail: unchanged")
        .contains("mine: " + abbr(mine1) + " -> " + abbr(mine2));
    assertThat(lock()).contains(mail1).contains(mine2).doesNotContain(mine1);

    assertThat(admin("layers", "update", "nope").err())
        .contains("there is no git layer named 'nope'");
  }

  @Test
  void aChangedConfigurationNeedsANewPin() throws Exception {
    assertThat(admin("layers", "update").exit()).isZero();
    configure(
        Files.readString(home.resolve("praksis.toml")).replace("ref = \"main\"", "ref = \"v2\""));
    assertThat(admin("layers", "status").out()).contains("the configuration changed since");
    assertThatThrownBy(() -> new Layers(DaemonConfig.load(home), git).effective())
        .hasMessageContaining("is configured as")
        .hasMessageContaining("@ v2 but pinned as");
  }

  @Test
  void aSnapshotThatWasDeletedIsFetchedAgainWhenNeeded() throws Exception {
    assertThat(admin("layers", "update").exit()).isZero();
    try (Stream<Path> files = Files.walk(home.resolve("layers"))) {
      for (Path f : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(f);
      }
    }
    assertThat(admin("layers", "status").out()).contains("files not on disk");
    assertThat(new Layers(DaemonConfig.load(home), git).effective().ruleset().rules()).hasSize(2);
  }

  @Test
  void directoryLayersAreReadAsTheyAreAndNeverPinned() throws Exception {
    Path rules = Files.createDirectories(home.resolve("my-rules/rules"));
    Files.writeString(
        home.resolve("my-rules/ruleset.toml"), "format_version = 1\nname = \"local\"\n");
    Files.writeString(rules.resolve("mail.spam.toml"), SPAM);
    configure(
        """
        format_version = 1
        [[layer]]
        name = "local"
        path = "my-rules"

        [param]
        x = 1
        """);
    assertThat(admin("layers", "status").out())
        .contains("local: directory")
        .contains("(not pinned)");
    assertThat(admin("layers", "update").err()).contains("no layer comes from git");
    Result r = admin("effective");
    assertThat(r.exit()).isEqualTo(1);
    assertThat(r.err()).contains("param.x").contains("no layer declares this parameter");
  }

  @Test
  void theEffectiveViewShowsOriginsHashesDisabledRulesAndParameters() throws Exception {
    publish(
        MAIL_URL,
        tree(
            Map.of(
                "ruleset.toml",
                    "format_version = 1\nname = \"mail\"\n[param.folder]\ntype = \"string\"\ndefault = \"News\"\n",
                "rules/mail.archive.toml", ARCHIVE,
                "rules/mail.spam.toml", SPAM)));
    Result update = admin("layers", "update");
    assertThat(update.exit()).as(update.err()).isZero();
    String out = admin("effective").out();
    assertThat(out)
        .contains("ruleset: mail+mine (2 rules)")
        .contains("mail.archive")
        .containsPattern("sha256:[0-9a-f]{64}")
        .contains("mail: add; mine: patch")
        .contains("folder (string) = News");
  }

  @Test
  void unknownCommandsAreUsageErrors() throws Exception {
    assertThat(admin("layers", "frobnicate").exit()).isEqualTo(2);
    assertThat(admin().err()).contains("usage:");
  }
}
