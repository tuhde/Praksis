// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.ruleset.Table;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * One {@code [[layer]]} of {@code praksis.toml}: a ruleset layer that comes from a git repository
 * (pinned to a commit) or from a directory (read as it is).
 *
 * @param name the layer name, used in the effective view and in messages
 * @param git the address of the repository, or empty
 * @param ref the branch or tag that {@code praksis layers update} follows, for a git layer
 * @param path the directory, for a path layer
 */
public record LayerConfig(
    String name, Optional<String> git, Optional<String> ref, Optional<Path> path) {
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  /** True if the layer is pinned to a commit of a git repository. */
  public boolean isGit() {
    return git.isPresent();
  }

  static Optional<LayerConfig> parse(Table t, Path home) {
    t.onlyKeys(Set.of("name", "git", "ref", "path"));
    Optional<String> name = t.string("name", true);
    Optional<String> git = t.string("git", false);
    Optional<String> ref = t.string("ref", false);
    Optional<String> path = t.string("path", false);
    if (name.isPresent() && !NAME.matcher(name.get()).matches()) {
      t.problem("layer name '" + name.get() + "' must match " + NAME.pattern());
    }
    if (git.isPresent() == path.isPresent()) {
      t.problem("give exactly one of 'git' and 'path'");
    }
    if (git.isPresent() && ref.isEmpty()) {
      t.problem("a git layer needs 'ref', the branch or tag to follow");
    }
    if (path.isPresent() && ref.isPresent()) {
      t.problem("'ref' is only for git layers");
    }
    if (name.isEmpty() || git.isPresent() == path.isPresent()) {
      return Optional.empty();
    }
    return Optional.of(
        new LayerConfig(name.get(), git, ref, path.map(p -> home.resolve(p).normalize())));
  }
}
