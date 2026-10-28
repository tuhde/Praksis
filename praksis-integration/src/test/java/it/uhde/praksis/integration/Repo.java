// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** A git repository made for a test, used as the upstream of a ruleset layer. */
final class Repo {
  final Path dir;
  private Path signingKey;

  Repo(Path dir) throws Exception {
    this.dir = Files.createDirectories(dir);
    git("init", "--quiet", "--initial-branch=main");
  }

  /** From now on commits are signed with this SSH key. */
  void signWith(Path key) {
    this.signingKey = key;
  }

  /** The address to put in {@code praksis.toml}. */
  String url() {
    return dir.toUri().toString();
  }

  /** Writes the files (name, content, name, content, ...) and commits; returns the commit id. */
  String commit(String message, String... filesAndContents) throws Exception {
    for (int i = 0; i < filesAndContents.length; i += 2) {
      Path file = dir.resolve(filesAndContents[i]);
      Files.createDirectories(file.getParent());
      Files.writeString(file, filesAndContents[i + 1]);
    }
    git("add", "--all");
    if (signingKey == null) {
      git("commit", "--quiet", "-m", message);
    } else {
      git(
          "-c",
          "gpg.format=ssh",
          "-c",
          "user.signingkey=" + signingKey,
          "commit",
          "--quiet",
          "-S",
          "-m",
          message);
    }
    return git("rev-parse", "HEAD");
  }

  /** Starts a branch from the current one and switches to it. */
  void branch(String name) throws Exception {
    git("checkout", "--quiet", "-b", name);
  }

  void checkout(String name) throws Exception {
    git("checkout", "--quiet", name);
  }

  private String git(String... args) throws Exception {
    List<String> command = new ArrayList<>(List.of("git", "-c", "commit.gpgsign=false"));
    command.addAll(List.of(args));
    ProcessBuilder pb =
        new ProcessBuilder(command).directory(dir.toFile()).redirectErrorStream(true);
    pb.environment().put("GIT_AUTHOR_NAME", "Test");
    pb.environment().put("GIT_AUTHOR_EMAIL", "test@example.org");
    pb.environment().put("GIT_COMMITTER_NAME", "Test");
    pb.environment().put("GIT_COMMITTER_EMAIL", "test@example.org");
    Process p = pb.start();
    String out = new String(p.getInputStream().readAllBytes());
    assertThat(p.waitFor()).as(String.join(" ", command) + "\n" + out).isZero();
    return out.strip();
  }
}
