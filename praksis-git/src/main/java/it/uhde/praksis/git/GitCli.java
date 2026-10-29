// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.git;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A {@link GitSource} that runs the installed {@code git}. It keeps a bare repository per layer
 * under the cache directory and reads files out of it with {@code ls-tree} and {@code cat-file}, so
 * no working tree is ever checked out and no git hook, filter or attribute can run.
 *
 * <p>Authentication is whatever the user's git is set up with: SSH keys and agent, credential
 * helpers. Git never asks on a terminal ({@code GIT_TERMINAL_PROMPT=0}), because the daemon has
 * none; a repository that needs a password fails with git's message. Only the transports file, git,
 * http, https and ssh are allowed, which excludes {@code ext::}, a way to run commands.
 */
public final class GitCli implements GitSource {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  private static final Pattern COMMIT = Pattern.compile("[0-9a-f]{40}|[0-9a-f]{64}");
  private static final Pattern FILE =
      Pattern.compile("(rules|replace|patch|disable)/[A-Za-z0-9][A-Za-z0-9._-]*\\.toml");
  private static final Pattern REF = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/@^~+-]*");
  private static final Map<String, String> ENVIRONMENT =
      Map.of(
          "GIT_TERMINAL_PROMPT", "0",
          "GIT_ALLOW_PROTOCOL", "file:git:http:https:ssh",
          "GIT_OPTIONAL_LOCKS", "0",
          "LC_ALL", "C");

  private final Path cache;
  private final String executable;
  private final Duration timeout;
  private final List<String> settings;

  /**
   * @param cache the directory for the local copies
   * @param executable the git command, normally {@code git}
   * @param timeout the longest any single git command may run
   */
  public GitCli(Path cache, String executable, Duration timeout) {
    this(cache, executable, timeout, List.of());
  }

  /**
   * @param settings git settings for every command, as {@code key=value}, e.g. {@code
   *     gpg.ssh.allowedSignersFile=/path}; the settings of the user's own git apply as well
   */
  public GitCli(Path cache, String executable, Duration timeout, List<String> settings) {
    this.cache = cache;
    this.executable = executable;
    this.timeout = timeout;
    this.settings = List.copyOf(settings);
  }

  public GitCli(Path cache) {
    this(cache, "git", Duration.ofMinutes(2));
  }

  @Override
  public String resolve(String name, String url, String ref) throws GitException {
    if (!REF.matcher(ref).matches()) {
      throw new GitException("'" + ref + "' is not a branch, tag or commit id");
    }
    Path repo = repository(name);
    fetch(repo, url, null);
    String out;
    try {
      out = run(repo, "rev-parse", "--verify", "--quiet", "--end-of-options", ref + "^{commit}");
    } catch (GitException e) {
      throw new GitException("'" + ref + "' does not exist in " + url, e);
    }
    out = out.trim();
    if (!COMMIT.matcher(out).matches()) {
      throw new GitException("'" + ref + "' does not exist in " + url);
    }
    return out;
  }

  @Override
  public void export(String name, String url, String commit, Path target) throws GitException {
    if (Files.exists(target)) {
      return;
    }
    if (!COMMIT.matcher(commit).matches()) {
      throw new GitException("'" + commit + "' is not a full commit id");
    }
    Path repo = repository(name);
    if (!has(repo, commit)) {
      fetch(repo, url, null);
    }
    if (!has(repo, commit)) {
      try {
        fetch(repo, url, commit); // a commit that no branch or tag leads to any more
      } catch (GitException e) {
        // the server does not have it; reported below
      }
    }
    if (!has(repo, commit)) {
      throw new GitException(
          "commit " + commit + " is not in " + url + " any more (was history rewritten?)");
    }
    Path parent = target.toAbsolutePath().getParent();
    try {
      Files.createDirectories(parent);
      Path temp = Files.createTempDirectory(parent, ".export-");
      try {
        write(repo, commit, temp);
        Files.move(temp, target);
      } catch (IOException | GitException | RuntimeException e) {
        delete(temp);
        throw e;
      }
    } catch (IOException e) {
      throw new GitException("cannot write " + target + ": " + e.getMessage(), e);
    }
  }

  @Override
  public Optional<Change> lastChange(String name, String commit, String path) throws GitException {
    if (!COMMIT.matcher(commit).matches()) {
      throw new GitException("'" + commit + "' is not a full commit id");
    }
    if (!FILE.matcher(path).matches()) {
      throw new GitException("'" + path + "' is not a rule file");
    }
    Path repo = repository(name);
    if (!has(repo, commit)) {
      return Optional.empty();
    }
    String out = run(repo, "log", "-1", "--format=" + CHANGE_FORMAT, commit, "--", path);
    return out.isBlank() ? Optional.empty() : parseChange(out);
  }

  private static final String CHANGE_FORMAT =
      "%H%x00%an%x00%ae%x00%aI%x00%s%x00%G?%x00%GF%x00%GK%x00%b";

  private static Optional<Change> parseChange(String record) {
    String[] f = record.split("\0", 9);
    if (f.length < 9 || f[0].isBlank()) {
      return Optional.empty();
    }
    return Optional.of(
        new Change(
            f[0].strip(),
            f[1],
            f[2],
            java.time.OffsetDateTime.parse(f[3]).toInstant(),
            f[4],
            f[8].strip(),
            f[5].isBlank() ? "N" : f[5].strip(),
            f[6].isBlank() ? f[7].strip() : f[6].strip()));
  }

  @Override
  public List<Branch> branches(String name, String url) throws GitException {
    Path repo = repository(name);
    fetch(repo, url, null);
    List<Branch> out = new ArrayList<>();
    for (String line :
        run(repo, "for-each-ref", "--format=%(refname:short)%00%(objectname)", "refs/heads")
            .split("\n")) {
      String[] f = line.split("\0");
      if (f.length == 2 && COMMIT.matcher(f[1].strip()).matches()) {
        out.add(new Branch(f[0], f[1].strip()));
      }
    }
    return out;
  }

  @Override
  public List<Change> commits(String name, String base, String tip, int limit) throws GitException {
    if (!COMMIT.matcher(tip).matches() || (!base.isEmpty() && !COMMIT.matcher(base).matches())) {
      throw new GitException(
          "not a full commit id: " + (COMMIT.matcher(tip).matches() ? base : tip));
    }
    Path repo = repository(name);
    if (!has(repo, tip) || (!base.isEmpty() && !has(repo, base))) {
      return List.of();
    }
    String range = base.isEmpty() ? tip : base + ".." + tip;
    String out =
        run(
            repo,
            "log",
            "-" + Math.max(1, Math.min(limit, 200)),
            "--format=" + CHANGE_FORMAT + "%x1e",
            range,
            "--");
    List<Change> changes = new ArrayList<>();
    for (String record : out.split("\u001e")) {
      parseChange(record.strip()).ifPresent(changes::add);
    }
    return changes;
  }

  @Override
  public boolean isAncestor(String name, String ancestor, String descendant) throws GitException {
    if (!COMMIT.matcher(ancestor).matches() || !COMMIT.matcher(descendant).matches()) {
      throw new GitException("not a full commit id");
    }
    Path repo = repository(name);
    try {
      run(repo, "merge-base", "--is-ancestor", ancestor, descendant);
      return true;
    } catch (GitException e) {
      return false;
    }
  }

  private Path repository(String name) throws GitException {
    if (!NAME.matcher(name).matches()) {
      throw new GitException("'" + name + "' is not a usable layer name");
    }
    Path repo = cache.resolve(name + ".git");
    if (!Files.isDirectory(repo)) {
      try {
        Files.createDirectories(cache);
      } catch (IOException e) {
        throw new GitException("cannot create " + cache + ": " + e.getMessage(), e);
      }
      run(cache, "init", "--bare", "--quiet", repo.toString());
    }
    return repo;
  }

  /** Fetches all branches and tags, or just one commit if {@code only} is given. */
  private void fetch(Path repo, String url, String only) throws GitException {
    if (url.startsWith("-") || url.isBlank()) {
      throw new GitException("'" + url + "' is not a repository address");
    }
    List<String> args = new ArrayList<>(List.of("fetch", "--quiet", "--force", "--no-tags"));
    if (only == null) {
      args.addAll(
          List.of("--prune", "--", url, "+refs/heads/*:refs/heads/*", "+refs/tags/*:refs/tags/*"));
    } else {
      args.addAll(List.of("--", url, only));
    }
    try {
      run(repo, args.toArray(String[]::new));
    } catch (GitException e) {
      throw new GitException("cannot fetch " + url + ": " + e.getMessage(), e);
    }
  }

  private boolean has(Path repo, String commit) {
    try {
      run(repo, "cat-file", "-e", commit + "^{commit}");
      return true;
    } catch (GitException e) {
      return false;
    }
  }

  /** Writes every file of the commit below {@code dir}. */
  private void write(Path repo, String commit, Path dir) throws GitException, IOException {
    String listing = run(repo, "ls-tree", "-r", "-z", "--full-tree", commit);
    List<String[]> entries = new ArrayList<>();
    for (String record : listing.split("\0")) {
      if (record.isEmpty()) {
        continue;
      }
      int tab = record.indexOf('\t');
      String[] meta = record.substring(0, tab).split(" ");
      entries.add(new String[] {meta[0], meta[1], meta[2], record.substring(tab + 1)});
    }
    for (String[] e : entries) {
      if (e[0].equals("120000")) {
        throw new GitException("commit " + commit + " contains a symbolic link: " + e[3]);
      }
      if (!e[1].equals("blob")) {
        throw new GitException("commit " + commit + " contains a submodule: " + e[3]);
      }
    }

    Path errors = Files.createTempFile("praksis-git", ".err");
    Process p = process(repo, errors, "cat-file", "--batch").start();
    try (OutputStream in = p.getOutputStream();
        InputStream out = new BufferedInputStream(p.getInputStream())) {
      for (String[] e : entries) {
        Path file = safe(dir, e[3]);
        in.write((e[2] + "\n").getBytes(StandardCharsets.UTF_8));
        in.flush();
        String header = line(out);
        String[] parts = header.split(" ");
        if (parts.length != 3 || !parts[1].equals("blob")) {
          throw new GitException("unexpected answer from git for " + e[3] + ": " + header);
        }
        long size = Long.parseLong(parts[2]);
        Files.createDirectories(file.getParent());
        try (OutputStream f = Files.newOutputStream(file)) {
          long left = size;
          byte[] buffer = new byte[8192];
          while (left > 0) {
            int n = out.read(buffer, 0, (int) Math.min(buffer.length, left));
            if (n < 0) {
              throw new GitException("git ended early while reading " + e[3]);
            }
            f.write(buffer, 0, n);
            left -= n;
          }
        }
        if (out.read() != '\n') {
          throw new GitException("unexpected answer from git after " + e[3]);
        }
      }
    } finally {
      p.destroy();
      deleteQuietly(errors);
    }
  }

  /** The path of a file in the commit below {@code dir}; refuses anything that would leave it. */
  private static Path safe(Path dir, String name) throws GitException {
    Path file = dir.resolve(name).normalize();
    for (String part : name.split("/")) {
      if (part.equals("..")
          || part.equals(".")
          || part.equalsIgnoreCase(".git")
          || part.isEmpty()) {
        throw new GitException("refusing the file name '" + name + "'");
      }
    }
    if (!file.startsWith(dir) || file.equals(dir)) {
      throw new GitException("refusing the file name '" + name + "'");
    }
    return file;
  }

  private static String line(InputStream in) throws IOException {
    ByteArrayOutputStream line = new ByteArrayOutputStream();
    int b;
    while ((b = in.read()) >= 0 && b != '\n') {
      line.write(b);
    }
    return line.toString(StandardCharsets.UTF_8);
  }

  /** Runs git and returns what it printed; its error output becomes the exception message. */
  private String run(Path dir, String... args) throws GitException {
    Path errors = null;
    Process p;
    try {
      errors = Files.createTempFile("praksis-git", ".err");
      p = process(dir, errors, args).start();
    } catch (IOException e) {
      deleteQuietly(errors);
      throw new GitException(
          "cannot run '" + executable + "': " + e.getMessage() + " (is git installed?)", e);
    }
    try {
      p.getOutputStream().close();
      // git writes its error output to a file, so neither pipe can fill up and block it
      byte[] out = p.getInputStream().readAllBytes();
      if (!p.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        p.destroyForcibly();
        throw new GitException("git " + args[0] + " did not finish within " + timeout);
      }
      if (p.exitValue() != 0) {
        String why = Files.readString(errors, StandardCharsets.UTF_8).strip();
        throw new GitException(why.isEmpty() ? "git " + args[0] + " failed" : why);
      }
      return new String(out, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new GitException("git " + args[0] + ": " + e.getMessage(), e);
    } catch (InterruptedException e) {
      p.destroyForcibly();
      Thread.currentThread().interrupt();
      throw new GitException("interrupted while running git " + args[0], e);
    } finally {
      deleteQuietly(errors);
    }
  }

  private static void deleteQuietly(Path file) {
    if (file != null) {
      try {
        Files.deleteIfExists(file);
      } catch (IOException e) {
        // a leftover temporary file is harmless
      }
    }
  }

  private ProcessBuilder process(Path dir, Path errors, String... args) {
    List<String> command = new ArrayList<>();
    command.add(executable);
    for (String setting : settings) {
      command.add("-c");
      command.add(setting);
    }
    command.addAll(List.of(args));
    ProcessBuilder pb = new ProcessBuilder(command);
    pb.directory(dir.toFile());
    pb.environment().putAll(ENVIRONMENT);
    pb.redirectError(errors.toFile());
    return pb;
  }

  private static void delete(Path dir) {
    try (Stream<Path> files = Files.walk(dir)) {
      for (Path f : files.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(f);
      }
    } catch (IOException e) {
      // a leftover ".export-" directory is harmless
    }
  }
}
