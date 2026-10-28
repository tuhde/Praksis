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

  /**
   * Who last changed a file, and why, as far as the commit says. Uses only what is in the local
   * copy; it never asks the server.
   *
   * @param commit the commit to look back from
   * @param path a file in the repository, e.g. {@code rules/mail.10-spam.toml}
   * @return the last commit up to {@code commit} that changed the file, or empty if there is none
   *     or the local copy does not have the commit
   */
  default java.util.Optional<Change> lastChange(String name, String commit, String path)
      throws GitException {
    return java.util.Optional.empty();
  }

  /**
   * A commit that changed a file.
   *
   * @param commit the full commit id
   * @param authorName the author's name
   * @param authorEmail the author's email address
   * @param time when it was authored
   * @param subject the first line of the message
   * @param body the rest of the message, trailers included
   * @param signature how git judges the signature: {@code G} good, {@code U} good but the key is
   *     not trusted, {@code N} none, anything else is bad or unverifiable (see {@code %G?} in git
   *     log)
   * @param signer the fingerprint of the signing key, empty if there is none
   */
  record Change(
      String commit,
      String authorName,
      String authorEmail,
      java.time.Instant time,
      String subject,
      String body,
      String signature,
      String signer) {
    /** Whether git found the signature good (the key may be of unknown validity). */
    public boolean signed() {
      return signature.equals("G") || signature.equals("U");
    }
  }
}
