// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The identity of this daemon towards other daemons, and the daemons it knows (cascading daemons).
 *
 * <p>A daemon is identified by an Ed25519 key pair and nothing else: the name and the socket path
 * of a peer are labels that the person chose, and the remote side gives grants to the key. The
 * private key is created on first use in {@code <home>/identity/key} (readable by the owner only)
 * and never leaves the machine. The peers are listed in {@code <home>/peers.toml}:
 *
 * <pre>
 * format_version = 1
 *
 * [[peer]]
 * name   = "acme"                    # the prefix of its types: acme/invoice
 * key    = "MCowBQYDK2VwAyEA..."     # its public key (see 'praksis peers show-key' there)
 * socket = "/run/praksis/acme.sock"  # where to reach it
 * standing_read = false              # read access to everything it offers that is safe
 * </pre>
 *
 * The same file is read by the remote side to know who may connect to it; there {@code socket} may
 * be left out.
 */
final class Peers {
  private static final TomlMapper TOML = new TomlMapper();
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,31}");
  static final String FILE = "peers.toml";

  /**
   * One known daemon.
   *
   * @param name the label and the prefix of its types
   * @param key its public key, X.509 encoded and Base64
   * @param socket where to reach it, empty if only it connects to us
   * @param standingRead whether the person allowed read access to everything safe that it offers
   */
  record Peer(String name, String key, Optional<String> socket, boolean standingRead) {
    /** A short form of the key to compare by eye. */
    String fingerprint() {
      return Peers.fingerprint(key);
    }
  }

  private final Path home;

  Peers(Path home) {
    this.home = home;
  }

  // ---------------------------------------------------------------------------- own key

  private Path keyFile() {
    return home.resolve("identity").resolve("key");
  }

  private Path pubFile() {
    return home.resolve("identity").resolve("key.pub");
  }

  /** The private key; the pair is made on first use. */
  PrivateKey privateKey() throws IOException {
    ensureKey();
    try {
      byte[] der = Base64.getDecoder().decode(Files.readString(keyFile()).strip());
      return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IOException("the key in " + keyFile() + " cannot be read: " + e.getMessage(), e);
    }
  }

  /** The public key of this daemon as other daemons list it. */
  String publicKey() throws IOException {
    ensureKey();
    return Files.readString(pubFile()).strip();
  }

  private synchronized void ensureKey() throws IOException {
    if (Files.exists(keyFile()) && Files.exists(pubFile())) {
      return;
    }
    try {
      KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
      Path dir = keyFile().getParent();
      Files.createDirectories(dir);
      try {
        Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
      } catch (UnsupportedOperationException ignored) {
        // not a POSIX file system
      }
      Path tmp = Files.createTempFile(dir, "key", ".tmp");
      try {
        Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
      } catch (UnsupportedOperationException ignored) {
        // not a POSIX file system
      }
      Files.writeString(tmp, Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
      Files.move(tmp, keyFile(), StandardCopyOption.REPLACE_EXISTING);
      Files.writeString(
          pubFile(), Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()) + "\n");
    } catch (GeneralSecurityException e) {
      throw new IOException("no Ed25519 key pair: " + e.getMessage(), e);
    }
  }

  /** Signs data with the key of this daemon. */
  byte[] sign(byte[] data) throws IOException {
    try {
      Signature s = Signature.getInstance("Ed25519");
      s.initSign(privateKey());
      s.update(data);
      return s.sign();
    } catch (GeneralSecurityException e) {
      throw new IOException(e.getMessage(), e);
    }
  }

  /** Whether the signature is from the owner of the (Base64, X.509) public key. */
  static boolean verify(String publicKey, byte[] data, byte[] signature) {
    try {
      PublicKey key =
          KeyFactory.getInstance("Ed25519")
              .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(publicKey)));
      Signature s = Signature.getInstance("Ed25519");
      s.initVerify(key);
      s.update(data);
      return s.verify(signature);
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      return false;
    }
  }

  /** The first 16 hex digits of the SHA-256 of the key, in groups of four. */
  static String fingerprint(String publicKey) {
    try {
      byte[] h =
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(publicKey.getBytes(StandardCharsets.UTF_8));
      String hex = java.util.HexFormat.of().formatHex(h).substring(0, 16);
      return hex.replaceAll("(.{4})(?!$)", "$1-");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  // ---------------------------------------------------------------------------- the list

  private Path file() {
    return home.resolve(FILE);
  }

  /** All known peers, in file order. */
  List<Peer> list() throws IOException {
    if (!Files.exists(file())) {
      return List.of();
    }
    Map<String, Object> root;
    try {
      root = TOML.readValue(file().toFile(), new TypeReference<LinkedHashMap<String, Object>>() {});
    } catch (IOException e) {
      throw new IOException(FILE + ": " + e.getMessage(), e);
    }
    Object fv = root.get("format_version");
    if (fv != null && !Integer.valueOf(1).equals(fv)) {
      throw new IOException(FILE + ": format_version " + fv + " is not supported (this is 1)");
    }
    List<Peer> peers = new ArrayList<>();
    Object raw = root.get("peer");
    if (raw instanceof List<?> items) {
      for (Object item : items) {
        peers.add(parse(item));
      }
    }
    return List.copyOf(peers);
  }

  private static Peer parse(Object item) throws IOException {
    if (!(item instanceof Map<?, ?> m)) {
      throw new IOException(FILE + ": [[peer]] must be a table");
    }
    for (Object k : m.keySet()) {
      if (!List.of("name", "key", "socket", "standing_read").contains(k)) {
        throw new IOException(FILE + ": unknown key '" + k + "' in [[peer]]");
      }
    }
    String name = text(m, "name");
    String key = text(m, "key");
    checkName(name);
    checkKey(key);
    Object sock = m.get("socket");
    Object sr = m.get("standing_read");
    if (sr != null && !(sr instanceof Boolean)) {
      throw new IOException(FILE + ": standing_read of '" + name + "' must be true or false");
    }
    return new Peer(
        name,
        key,
        sock == null ? Optional.empty() : Optional.of(sock.toString()),
        Boolean.TRUE.equals(sr));
  }

  private static String text(Map<?, ?> m, String k) throws IOException {
    Object v = m.get(k);
    if (!(v instanceof String s) || s.isBlank()) {
      throw new IOException(FILE + ": a [[peer]] needs '" + k + "'");
    }
    return s;
  }

  private static void checkName(String name) throws IOException {
    if (!NAME.matcher(name).matches()) {
      throw new IOException(
          "peer name '"
              + name
              + "' must be lower-case letters, digits and '-', start with a letter, 32 at most");
    }
  }

  private static void checkKey(String key) throws IOException {
    try {
      KeyFactory.getInstance("Ed25519")
          .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(key)));
    } catch (GeneralSecurityException | IllegalArgumentException e) {
      throw new IOException("not a valid Ed25519 public key: " + key);
    }
  }

  /** The peer that has this key. */
  Optional<Peer> byKey(String key) throws IOException {
    return list().stream().filter(p -> p.key().equals(key)).findFirst();
  }

  /** The peer with this name. */
  Optional<Peer> byName(String name) throws IOException {
    return list().stream().filter(p -> p.name().equals(name)).findFirst();
  }

  /** Adds a peer; refuses a name or a key that is listed already, and our own key. */
  void add(String name, String key, Optional<String> socket, boolean standingRead)
      throws IOException {
    checkName(name);
    checkKey(key);
    if (key.equals(publicKey())) {
      throw new IOException("that is the key of this daemon; a daemon is not its own peer");
    }
    List<Peer> peers = new ArrayList<>(list());
    for (Peer p : peers) {
      if (p.name().equals(name)) {
        throw new IOException("a peer named '" + name + "' is listed already");
      }
      if (p.key().equals(key)) {
        throw new IOException("that key is listed already as '" + p.name() + "'");
      }
    }
    peers.add(new Peer(name, key, socket, standingRead));
    write(peers);
  }

  /** Removes a peer; returns whether there was one. */
  boolean remove(String name) throws IOException {
    List<Peer> peers = new ArrayList<>(list());
    boolean removed = peers.removeIf(p -> p.name().equals(name));
    if (removed) {
      write(peers);
    }
    return removed;
  }

  private void write(List<Peer> peers) throws IOException {
    StringBuilder sb = new StringBuilder("format_version = 1\n");
    for (Peer p : peers) {
      sb.append("\n[[peer]]\n");
      sb.append("name = ").append(quote(p.name())).append('\n');
      sb.append("key = ").append(quote(p.key())).append('\n');
      p.socket().ifPresent(s -> sb.append("socket = ").append(quote(s)).append('\n'));
      sb.append("standing_read = ").append(p.standingRead()).append('\n');
    }
    Files.createDirectories(home);
    Path tmp = Files.createTempFile(home, "peers", ".tmp");
    Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
    Files.move(tmp, file(), StandardCopyOption.REPLACE_EXISTING);
  }

  private static String quote(String s) {
    return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
  }
}
