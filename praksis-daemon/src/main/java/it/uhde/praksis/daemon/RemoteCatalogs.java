// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import it.uhde.praksis.protocol.Request;
import it.uhde.praksis.protocol.Response;
import it.uhde.praksis.protocol.Wire;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The catalogs of the daemons we are connected to. What a remote offers appears under its name
 * ({@code acme/invoice}); a local type has no prefix. Everything in a catalog is text from another
 * daemon: it is cleaned before use (control characters and escape sequences removed, lengths and
 * counts bounded, names that are not plain identifiers dropped), shown as text and never handed to
 * a model as instructions. The last catalog is kept in {@code <home>/remote/<peer>.catalog} so that
 * it can be shown when the remote cannot be reached; it is then marked stale.
 */
final class RemoteCatalogs {
  static final int MAX_ITEMS = 500;
  static final int MAX_FIELDS = 100;
  static final int MAX_NAME = 100;
  static final int MAX_TEXT = 2000;
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9._-]*");

  /**
   * A catalog as it is used here.
   *
   * @param peer the name of the daemon
   * @param fetched when it was received
   * @param stale true if it comes from the cache because the remote could not be asked
   * @param dropped how many entries were left out because their names were not plain identifiers
   * @param catalog the cleaned catalog
   */
  record Remote(
      String peer, Instant fetched, boolean stale, int dropped, Response.PeerCatalog catalog) {
    /** The name of a remote item as the local catalog shows it. */
    String qualified(String name) {
      return peer + "/" + name;
    }
  }

  private final Path home;

  RemoteCatalogs(Path home) {
    this.home = home;
  }

  private Path file(String peer) {
    return home.resolve("remote").resolve(peer + ".catalog");
  }

  /** Asks the peer for its catalog, keeps it, and returns it cleaned. */
  Remote fetch(PeerLink link) throws IOException {
    Response r = link.send(new Request.PeerCatalog());
    if (r instanceof Response.Error e) {
      throw new IOException("peer '" + link.peer().name() + "' refused: " + e.message());
    }
    if (!(r instanceof Response.PeerCatalog c)) {
      throw new IOException("peer '" + link.peer().name() + "' did not send a catalog");
    }
    if (c.version() != link.agreed().catalog()) {
      throw new IOException(
          "peer '"
              + link.peer().name()
              + "' sent catalog version "
              + c.version()
              + " but "
              + link.agreed().catalog()
              + " was agreed");
    }
    Instant now = Instant.now();
    Remote remote = clean(link.peer().name(), now, false, c);
    store(remote);
    return remote;
  }

  /** The last catalog that was kept, marked stale. */
  Optional<Remote> cached(String peer) throws IOException {
    Path f = file(peer);
    if (!Files.exists(f)) {
      return Optional.empty();
    }
    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
    if (lines.size() != 2) {
      throw new IOException(f + " is damaged; fetch the catalog again");
    }
    Response r = Wire.decodeResponse(lines.get(1));
    if (!(r instanceof Response.PeerCatalog c)) {
      throw new IOException(f + " is damaged; fetch the catalog again");
    }
    // cleaned again: the file can be changed by anyone who can write to the home
    return Optional.of(clean(peer, Instant.parse(lines.get(0)), true, c));
  }

  void store(Remote remote) throws IOException {
    Path f = file(remote.peer());
    Files.createDirectories(f.getParent());
    Path tmp = Files.createTempFile(f.getParent(), "catalog", ".tmp");
    Files.writeString(
        tmp,
        remote.fetched() + "\n" + Wire.encode(remote.catalog()) + "\n",
        StandardCharsets.UTF_8);
    Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
  }

  // ---------------------------------------------------------------------------- cleaning

  static Remote clean(String peer, Instant fetched, boolean stale, Response.PeerCatalog c) {
    int[] dropped = {0};
    List<Response.PeerCatalog.CapabilityInfo> capabilities = new ArrayList<>();
    for (var x : bounded(c.capabilities())) {
      if (!plain(x.name())) {
        dropped[0]++;
        continue;
      }
      capabilities.add(
          new Response.PeerCatalog.CapabilityInfo(
              x.name(),
              text(x.effect(), MAX_NAME),
              x.supportsDryRun(),
              fields(x.params()),
              fields(x.results())));
    }
    List<Response.EntityTypeList.EntityTypeInfo> types = new ArrayList<>();
    for (var t : bounded(c.types())) {
      if (!plain(t.id())) {
        dropped[0]++;
        continue;
      }
      List<Response.EntityTypeList.EntityField> fs = new ArrayList<>();
      for (var f : limit(t.fields(), MAX_FIELDS)) {
        if (!plain(f.name())) {
          dropped[0]++;
          continue;
        }
        fs.add(
            new Response.EntityTypeList.EntityField(
                f.name(),
                text(f.type(), MAX_NAME),
                f.required(),
                f.unique(),
                f.sensitive(),
                text(f.description(), MAX_TEXT),
                // a reference is to a type of the same remote
                f.ref() == null ? null : plainOrNull(f.ref()),
                f.onDelete() == null ? null : text(f.onDelete(), MAX_NAME)));
      }
      types.add(
          new Response.EntityTypeList.EntityTypeInfo(
              t.id(),
              text(t.title(), MAX_NAME),
              text(t.description(), MAX_TEXT),
              null,
              text(t.hash(), MAX_NAME),
              fs,
              names(t.sets())));
    }
    List<Response.EntitySetList.EntitySetInfo> sets = new ArrayList<>();
    for (var s : bounded(c.sets())) {
      if (!plain(s.id())) {
        dropped[0]++;
        continue;
      }
      sets.add(
          new Response.EntitySetList.EntitySetInfo(
              s.id(),
              text(s.title(), MAX_NAME),
              text(s.description(), MAX_TEXT),
              null,
              text(s.hash(), MAX_NAME),
              names(s.types()),
              List.of(),
              names(s.members())));
    }
    return new Remote(
        peer,
        fetched,
        stale,
        dropped[0],
        new Response.PeerCatalog(c.version(), capabilities, types, sets));
  }

  private static List<Response.PeerCatalog.Field> fields(List<Response.PeerCatalog.Field> in) {
    List<Response.PeerCatalog.Field> out = new ArrayList<>();
    for (var f : limit(in, MAX_FIELDS)) {
      if (plain(f.name())) {
        out.add(
            new Response.PeerCatalog.Field(
                f.name(), text(f.type(), MAX_NAME), f.required(), f.scopable()));
      }
    }
    return out;
  }

  private static List<String> names(List<String> in) {
    List<String> out = new ArrayList<>();
    for (String n : limit(in, MAX_ITEMS)) {
      if (plain(n)) {
        out.add(n);
      }
    }
    return out;
  }

  private static <T> List<T> bounded(List<T> in) {
    return limit(in, MAX_ITEMS);
  }

  private static <T> List<T> limit(List<T> in, int max) {
    if (in == null) {
      return List.of();
    }
    return in.size() > max ? in.subList(0, max) : in;
  }

  private static boolean plain(String name) {
    return name != null && name.length() <= MAX_NAME && NAME.matcher(name).matches();
  }

  private static String plainOrNull(String name) {
    return plain(name) ? name : null;
  }

  /**
   * Text from a remote: control characters other than newline and tab are removed (which takes the
   * escape character out of terminal sequences), and the length is bounded.
   */
  static String text(String s, int max) {
    if (s == null) {
      return null;
    }
    StringBuilder out = new StringBuilder();
    s.codePoints()
        .filter(
            cp ->
                cp == '\n'
                    || cp == '\t'
                    || (!Character.isISOControl(cp) && Character.getType(cp) != Character.FORMAT))
        .forEach(out::appendCodePoint);
    String t = out.toString();
    return t.length() > max ? t.substring(0, max) + "…" : t;
  }
}
