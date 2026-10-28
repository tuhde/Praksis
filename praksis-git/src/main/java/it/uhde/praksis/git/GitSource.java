// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.git;

import java.nio.file.Path;

/**
 * Gets the content of a git repository at an exact commit. The rest of Praksis knows only this
 * interface, so the implementation that runs the {@code git} command can be replaced, e.g. by one
 * that does not need git to be installed.
 */
public interface GitSource {
  /**
   * Fetches the repository and finds the commit a branch, a tag or a commit id points to.
   *
   * @param name the layer name; the local copy of the repository is kept under it
   * @param url where the repository is
   * @param ref a branch, a tag or a commit id
   * @return the full commit id
   */
  String resolve(String name, String url, String ref) throws GitException;

  /**
   * Writes the files of a commit into a new directory. Does nothing if the directory exists.
   * Fetches the commit first if the local copy does not have it. Refuses a commit that contains
   * symbolic links or submodules.
   *
   * @param target the directory to create; it is created complete or not at all
   */
  void export(String name, String url, String commit, Path target) throws GitException;
}
