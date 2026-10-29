// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitCliTest {
  @TempDir Path dir;

  private Path upstream;
  private GitCli git;

  @BeforeEach
  void setUp() throws Exception {
    upstream = dir.resolve("upstream");
    Files.createDirectories(upstream);
    git(upstream, "init", "--quiet", "--initial-branch=main");
    git = new GitCli(dir.resolve("cache"));
  }

  /** Runs the git command line for building test repositories. */
  private static String git(Path in, String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
    command.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(command).directory(in.toFile()).redirectErrorStream(true);
    pb.environment().put("GIT_AUTHOR_NAME", "Test");
    pb.environment().put("GIT_AUTHOR_EMAIL", "test@example.org");
    pb.environment().put("GIT_COMMITTER_NAME", "Test");
    pb.environment().put("GIT_COMMITTER_EMAIL", "test@example.org");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as(String.join(" ", command) + "\n" + out).isZero();
    return out.strip();
  }

  private String commit(String message, String... filesAndContents) throws Exception {
    for (int i = 0; i < filesAndContents.length; i += 2) {
      Path file = upstream.resolve(filesAndContents[i]);
      Files.createDirectories(file.getParent());
      Files.writeString(file, filesAndContents[i + 1]);
    }
    git(upstream, "add", "--all");
    git(upstream, "commit", "--quiet", "-m", message);
    return git(upstream, "rev-parse", "HEAD");
  }

  private String url() {
    return upstream.toUri().toString();
  }

  private static List<String> files(Path root) throws IOException {
    try (Stream<Path> all = Files.walk(root)) {
      return all.filter(Files::isRegularFile)
          .map(f -> root.relativize(f).toString())
          .sorted()
          .toList();
    }
  }

  @Test
  void resolvesBranchesTagsAndCommitIds() throws Exception {
    String first = commit("one", "ruleset.toml", "a");
    git(upstream, "tag", "v1");
    String second = commit("two", "rules/x.toml", "b");
    assertThat(git.resolve("mail", url(), "main")).isEqualTo(second);
    assertThat(git.resolve("mail", url(), "v1")).isEqualTo(first);
    assertThat(git.resolve("mail", url(), first)).isEqualTo(first);
    assertThat(git.resolve("mail", url(), first.substring(0, 10))).isEqualTo(first);
  }

  @Test
  void followsTheBranchAfterTheUpstreamMovesOn() throws Exception {
    String first = commit("one", "a.toml", "1");
    assertThat(git.resolve("mail", url(), "main")).isEqualTo(first);
    String second = commit("two", "a.toml", "2");
    assertThat(git.resolve("mail", url(), "main")).isEqualTo(second);
  }

  @Test
  void exportsExactlyTheFilesOfTheCommit() throws Exception {
    String first =
        commit("one", "ruleset.toml", "name", "rules/a.toml", "rule a", "docs/ü ß.md", "x");
    commit("two", "rules/b.toml", "rule b");
    git.resolve("mail", url(), "main");
    Path target = dir.resolve("snapshots/mail/" + first);

    git.export("mail", url(), first, target);

    assertThat(files(target)).containsExactly("docs/ü ß.md", "rules/a.toml", "ruleset.toml");
    assertThat(Files.readString(target.resolve("rules/a.toml"))).isEqualTo("rule a");
    assertThat(files(target.getParent())).doesNotContain(".export-");
  }

  @Test
  void exportKeepsBinaryContentAndAnExistingSnapshot() throws Exception {
    byte[] bytes = new byte[100_000];
    for (int i = 0; i < bytes.length; i++) {
      bytes[i] = (byte) (i * 31);
    }
    Files.write(upstream.resolve("blob.bin"), bytes);
    String c = commit("bin", "empty.txt", "");
    git.resolve("mail", url(), "main");
    Path target = dir.resolve("snap");
    git.export("mail", url(), c, target);
    assertThat(Files.readAllBytes(target.resolve("blob.bin"))).isEqualTo(bytes);
    assertThat(Files.size(target.resolve("empty.txt"))).isZero();

    Files.writeString(target.resolve("marker"), "x");
    git.export("mail", url(), c, target); // already there: left alone
    assertThat(target.resolve("marker")).exists();
  }

  @Test
  void anOldCommitCanStillBeExportedAfterAFreshFetch() throws Exception {
    String first = commit("one", "a.toml", "1");
    git.resolve("mail", url(), "main");
    commit("two", "a.toml", "2");
    git.resolve("mail", url(), "main");
    git.export("mail", url(), first, dir.resolve("old"));
    assertThat(Files.readString(dir.resolve("old/a.toml"))).isEqualTo("1");
  }

  @Test
  void aMachineThatNeverFetchedFetchesTheCommitOnExport() throws Exception {
    String first = commit("one", "a.toml", "1");
    commit("two", "a.toml", "2");
    git.export("mail", url(), first, dir.resolve("fresh"));
    assertThat(Files.readString(dir.resolve("fresh/a.toml"))).isEqualTo("1");
  }

  @Test
  void aCommitThatIsGoneFromTheRepositoryIsReported() throws Exception {
    commit("one", "a.toml", "1");
    assertThatThrownBy(
            () ->
                git.export(
                    "mail", url(), "0123456789012345678901234567890123456789", dir.resolve("x")))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("is not in")
        .hasMessageContaining("any more");
    assertThat(dir.resolve("x")).doesNotExist();
  }

  @Test
  void unknownRefsAndUnreachableRepositoriesAreReported() throws Exception {
    commit("one", "a.toml", "1");
    assertThatThrownBy(() -> git.resolve("mail", url(), "nope"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("'nope' does not exist");
    assertThatThrownBy(() -> git.resolve("mail", dir.resolve("missing").toUri().toString(), "main"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("cannot fetch");
  }

  @Test
  void refusesSymbolicLinks() throws Exception {
    Files.createSymbolicLink(upstream.resolve("link"), Path.of("/etc/passwd"));
    String c = commit("link", "a.toml", "1");
    git.resolve("mail", url(), "main");
    assertThatThrownBy(() -> git.export("mail", url(), c, dir.resolve("snap")))
        .hasMessageContaining("symbolic link: link");
    assertThat(dir.resolve("snap")).doesNotExist();
  }

  @Test
  void refusesWhatCouldBeUsedToRunCommandsOrEscape() throws Exception {
    commit("one", "a.toml", "1");
    Path marker = dir.resolve("marker");
    assertThatThrownBy(() -> git.resolve("mail", "--upload-pack=touch " + marker, "main"))
        .hasMessageContaining("is not a repository address");
    assertThatThrownBy(() -> git.resolve("mail", "ext::sh -c 'touch " + marker + "'", "main"))
        .isInstanceOf(GitException.class);
    assertThat(marker).doesNotExist();
    assertThatThrownBy(() -> git.resolve("mail", url(), "--output=x"))
        .hasMessageContaining("is not a branch, tag or commit id");
    assertThatThrownBy(() -> git.resolve("../escape", url(), "main"))
        .hasMessageContaining("not a usable layer name");
    assertThatThrownBy(() -> git.export("mail", url(), "main", dir.resolve("x")))
        .hasMessageContaining("is not a full commit id");
  }

  @Test
  void saysSoWhenGitIsNotInstalled() {
    GitCli broken = new GitCli(dir.resolve("c2"), "git-that-does-not-exist", Duration.ofSeconds(5));
    assertThatThrownBy(() -> broken.resolve("mail", url(), "main"))
        .isInstanceOf(GitException.class)
        .hasMessageContaining("is git installed?");
  }

  @Test
  void tellsWhoLastChangedAFileAndWhy() throws Exception {
    commit("Add the rule", "ruleset.toml", "a", "rules/x.toml", "one");
    String second =
        commit(
            "Narrow the filter\n\nToo many mails matched.\n\nPraksis-Author: ai",
            "rules/x.toml",
            "two");
    commit("Unrelated", "rules/y.toml", "y");
    String head = git.resolve("mail", url(), "main");

    var change = git.lastChange("mail", head, "rules/x.toml").orElseThrow();

    assertThat(change.commit()).isEqualTo(second);
    assertThat(change.authorName()).isEqualTo("Test");
    assertThat(change.authorEmail()).isEqualTo("test@example.org");
    assertThat(change.subject()).isEqualTo("Narrow the filter");
    assertThat(change.body()).contains("Too many mails matched.").contains("Praksis-Author: ai");
    assertThat(git.lastChange("mail", head, "rules/none.toml")).isEmpty();
    assertThatThrownBy(() -> git.lastChange("mail", head, "../x")).isInstanceOf(GitException.class);
    assertThatThrownBy(() -> git.lastChange("mail", "main", "rules/x.toml"))
        .isInstanceOf(GitException.class);
  }

  @Test
  void tellsWhetherTheCommitIsSignedAndByWhichKey() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        new ProcessBuilder("ssh-keygen", "-?").redirectErrorStream(true).start().waitFor() >= 0);
    Path key = dir.resolve("key");
    new ProcessBuilder("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-f", key.toString())
        .start()
        .waitFor();
    String pub = Files.readString(dir.resolve("key.pub")).strip();
    Path allowed = dir.resolve("allowed_signers");
    Files.writeString(allowed, "test@example.org " + pub + "\n");

    commit("unsigned", "rules/x.toml", "one");
    String unsigned = git.resolve("mail", url(), "main");
    Files.writeString(upstream.resolve("rules/x.toml"), "two");
    git(upstream, "add", "--all");
    git(
        upstream,
        "-c",
        "gpg.format=ssh",
        "-c",
        "user.signingkey=" + key,
        "commit",
        "--quiet",
        "-S",
        "-m",
        "signed");
    GitCli verifying =
        new GitCli(
            dir.resolve("cache2"),
            "git",
            Duration.ofMinutes(1),
            List.of("gpg.ssh.allowedSignersFile=" + allowed));
    String signed = verifying.resolve("mail", url(), "main");

    var good = verifying.lastChange("mail", signed, "rules/x.toml").orElseThrow();
    assertThat(good.signed()).isTrue();
    assertThat(good.signer()).startsWith("SHA256:");
    var none = verifying.lastChange("mail", unsigned, "rules/x.toml").orElseThrow();
    assertThat(none.signed()).isFalse();
    assertThat(none.signer()).isEmpty();
    // the same commit with no list of signers is a signature that cannot be checked
    var unknown = git.lastChange("mail", signed, "rules/x.toml");
    assertThat(unknown).isEmpty(); // this copy has not fetched it
    git.resolve("mail", url(), "main");
    assertThat(git.lastChange("mail", signed, "rules/x.toml").orElseThrow().signed()).isFalse();
  }

  @Test
  void listsBranchesAndTheCommitsOfOneThatAreNotInAnother() throws Exception {
    String base = commit("Base", "ruleset.toml", "a");
    git(upstream, "checkout", "--quiet", "-b", "ai-change");
    commit("First step\n\nBecause.", "rules/x.toml", "one");
    String tip = commit("Second step", "rules/x.toml", "two");
    git(upstream, "checkout", "--quiet", "main");

    var branches = git.branches("mail", url());

    assertThat(branches)
        .extracting(GitSource.Branch::name)
        .containsExactlyInAnyOrder("main", "ai-change");
    assertThat(
            branches.stream().filter(b -> b.name().equals("ai-change")).findFirst().get().commit())
        .isEqualTo(tip);
    var commits = git.commits("mail", base, tip, 10);
    assertThat(commits)
        .extracting(GitSource.Change::subject)
        .containsExactly("Second step", "First step");
    assertThat(commits.get(1).body()).isEqualTo("Because.");
    assertThat(git.commits("mail", tip, base, 10)).isEmpty();
    assertThat(git.commits("mail", "", base, 10)).hasSize(1);

    assertThat(git.isAncestor("mail", base, tip)).isTrue();
    assertThat(git.isAncestor("mail", tip, base)).isFalse();
    assertThat(git.isAncestor("mail", tip, tip)).isTrue();
    assertThatThrownBy(() -> git.isAncestor("mail", "main", tip)).isInstanceOf(GitException.class);
  }
}
