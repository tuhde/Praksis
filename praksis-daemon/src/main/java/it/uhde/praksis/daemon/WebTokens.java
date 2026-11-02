// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The tokens with which web clients log in to the daemon.
 *
 * <p>A person creates a token for a client at the daemon ({@code praksis web token create}); the
 * token is shown once and only its SHA-256 hash is kept in {@code <home>/web/tokens.json} (readable
 * by the owner only), so a copy of the file does not log anybody in. A token is random (256 bits),
 * so a plain hash is enough. Every token has a name, which shows in the audit log as {@code
 * web:<name>}, and can be revoked at any time; the file is read at each login, so a revocation
 * takes effect at once.
 */
final class WebTokens {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
  private static final String PREFIX = "pwt_";
  static final String FILE = "tokens.json";

  /**
   * One token, without its secret.
   *
   * @param name the label of the client
   * @param hash the SHA-256 of the token, in hex
   * @param readOnly whether this client may only look
   * @param created when it was made, ISO-8601
   */
  record Token(String name, String hash, boolean readOnly, String created) {}

  private final Path file;
  private final SecureRandom random = new SecureRandom();

  WebTokens(Path home) {
    this.file = home.resolve("web").resolve(FILE);
  }

  /** Makes a token and returns its secret, which is not kept anywhere. */
  synchronized String create(String name, boolean readOnly, Instant now) throws DaemonException {
    if (!NAME.matcher(name).matches()) {
      throw new DaemonException(
          "'" + name + "' is not a name: lower case letters, digits and '-', 32 at most");
    }
    List<Token> all = new ArrayList<>(list());
    if (all.stream().anyMatch(t -> t.name().equals(name))) {
      throw new DaemonException("there is a token named '" + name + "' already");
    }
    byte[] raw = new byte[32];
    random.nextBytes(raw);
    String secret = PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    all.add(new Token(name, hash(secret), readOnly, now.toString()));
    write(all);
    return secret;
  }

  /** The tokens, oldest first. */
  synchronized List<Token> list() throws DaemonException {
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      return JSON.readValue(file.toFile(), new TypeReference<List<Token>>() {});
    } catch (IOException e) {
      throw new DaemonException("cannot read " + file + ": " + e.getMessage());
    }
  }

  synchronized void revoke(String name) throws DaemonException {
    List<Token> all = new ArrayList<>(list());
    if (!all.removeIf(t -> t.name().equals(name))) {
      throw new DaemonException("no token named '" + name + "'");
    }
    write(all);
  }

  /** The token that belongs to this secret, if any. */
  synchronized Optional<Token> authenticate(String secret) {
    if (secret == null || !secret.startsWith(PREFIX) || secret.length() > 128) {
      return Optional.empty();
    }
    byte[] given = hash(secret).getBytes(StandardCharsets.US_ASCII);
    try {
      Token found = null;
      for (Token t : list()) {
        // every entry is compared, so the time does not tell where a match was
        if (MessageDigest.isEqual(given, t.hash().getBytes(StandardCharsets.US_ASCII))) {
          found = t;
        }
      }
      return Optional.ofNullable(found);
    } catch (DaemonException e) {
      return Optional.empty();
    }
  }

  private void write(List<Token> all) throws DaemonException {
    try {
      Files.createDirectories(file.getParent());
      Path tmp = file.resolveSibling(FILE + ".tmp");
      Files.deleteIfExists(tmp);
      Files.createFile(
          tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
      JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new DaemonException("cannot write " + file + ": " + e.getMessage());
    }
  }

  private static String hash(String secret) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
