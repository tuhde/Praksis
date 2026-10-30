// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.engine;

import it.uhde.praksis.capability.CapabilityApi;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/**
 * Installs a module from its archive, removes it and lists what is there.
 *
 * <p>A module archive is a zip file (by convention {@code <name>.praksis-module}) with the files of
 * a module directory at its top level: {@code module.toml}, the jar of the module and the jars it
 * needs. Nothing else is accepted: no directories, no paths, no other file types. A module is code
 * that runs with the rights of the daemon, so installing one is the decision of a person; the
 * command that does it is the acceptance, and only that module is recorded in the lockfile (what
 * else is in the modules directories stays as it was).
 *
 * <p>A module is installed below the home directory and hides a module of the same name that the
 * runtime image ships (see {@link ModuleLoader#load(List, Path, java.util.Map, Set)}).
 */
public final class ModuleInstaller {
  /** What a module is called: it is also the name of its directory. */
  private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]{0,39}");

  private static final Pattern FILE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}");
  private static final int MAX_ENTRIES = 200;
  private static final long MAX_FILE = 128L << 20;
  private static final long MAX_TOTAL = 512L << 20;

  private ModuleInstaller() {}

  /** What was installed. */
  public record Installed(String name, String version, Path directory, String sha256) {}

  /** One module that is there, and whether another one of the same name hides it. */
  public record Entry(
      String name, String version, int api, Path directory, boolean hidden, boolean locked) {}

  /**
   * Installs the archive below {@code root} and records the module in the lockfile.
   *
   * @param replace whether a module of the same name in {@code root} may be replaced (an update)
   */
  public static Installed install(Path archive, Path root, Path lockfile, boolean replace)
      throws ModuleLoadException {
    Path staging = null;
    try {
      Files.createDirectories(root);
      staging = Files.createTempDirectory(root, ".install-");
      unpack(archive, staging);
      ModuleDescriptorReader.Result read =
          ModuleDescriptorReader.read(staging.resolve("module.toml"));
      String name = read.descriptor().name();
      var mismatch = CapabilityApi.mismatch(name, read.apiVersion());
      if (mismatch.isPresent()) {
        throw new ModuleLoadException(mismatch.get());
      }
      if (!NAME.matcher(name).matches()) {
        throw new ModuleLoadException(
            "the module name '" + name + "' must be lower case letters, digits and '-'");
      }
      Path target = root.resolve(name);
      if (Files.exists(target) && !replace) {
        throw new ModuleLoadException(
            "module '"
                + name
                + "' is already installed in "
                + root
                + "; use --replace to update it");
      }
      Path old = null;
      if (Files.exists(target)) {
        old = Files.createTempDirectory(root, ".old-");
        Files.move(target, old.resolve(name));
      }
      try {
        Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
        staging = null;
        permissions(target);
      } catch (IOException e) {
        if (old != null) {
          Files.move(old.resolve(name), target); // put back what was there
        }
        throw e;
      }
      if (old != null) {
        delete(old);
      }
      String hash = Lockfile.hashDirectory(target);
      Lockfile locked = Lockfile.read(lockfile);
      locked.put(name, hash);
      locked.write(lockfile);
      return new Installed(name, read.descriptor().version(), target, hash);
    } catch (IOException e) {
      throw new ModuleLoadException("cannot install " + archive + ": " + e.getMessage(), e);
    } finally {
      if (staging != null) {
        delete(staging);
      }
    }
  }

  /** Removes the module that was installed below {@code root} and its entry in the lockfile. */
  public static void remove(String name, Path root, Path lockfile) throws ModuleLoadException {
    if (!NAME.matcher(name).matches()) {
      throw new ModuleLoadException("'" + name + "' is not a module name");
    }
    Path dir = root.resolve(name);
    if (!Files.isDirectory(dir)) {
      throw new ModuleLoadException("module '" + name + "' is not installed in " + root);
    }
    delete(dir);
    Lockfile locked = Lockfile.read(lockfile);
    locked.remove(name);
    locked.write(lockfile);
  }

  /** The modules of the directories, the first directory first, with what hides what. */
  public static List<Entry> list(List<Path> roots, Path lockfile) throws ModuleLoadException {
    Lockfile locked = Lockfile.read(lockfile);
    Set<String> seen = new HashSet<>();
    List<Entry> out = new ArrayList<>();
    for (Path root : roots) {
      for (Path dir : ModuleLoader.subdirectories(root)) {
        ModuleDescriptorReader.Result read =
            ModuleDescriptorReader.read(dir.resolve("module.toml"));
        String name = read.descriptor().name();
        boolean hidden = !seen.add(name);
        boolean ok = Lockfile.hashDirectory(dir).equals(locked.hashOf(name));
        out.add(new Entry(name, read.descriptor().version(), read.apiVersion(), dir, hidden, ok));
      }
    }
    return out;
  }

  // ------------------------------------------------------------------------------------------

  /** Unpacks flat files only, with limits on count and size that hold for what is really read. */
  private static void unpack(Path archive, Path into) throws IOException, ModuleLoadException {
    if (!Files.isRegularFile(archive)) {
      throw new ModuleLoadException("no such file: " + archive);
    }
    Set<String> names = new HashSet<>();
    long total = 0;
    try (ZipFile zip = new ZipFile(archive.toFile())) {
      var entries = zip.entries();
      while (entries.hasMoreElements()) {
        ZipEntry entry = entries.nextElement();
        String name = entry.getName();
        if (names.size() >= MAX_ENTRIES) {
          throw new ModuleLoadException("the archive has more than " + MAX_ENTRIES + " files");
        }
        if (entry.isDirectory() || name.contains("/") || name.contains("\\")) {
          throw new ModuleLoadException(
              "the archive holds '" + name + "': only files at the top level are allowed");
        }
        if (!FILE.matcher(name).matches()
            || !(name.equals("module.toml") || name.endsWith(".jar"))) {
          throw new ModuleLoadException(
              "the archive holds '" + name + "': only module.toml and jar files are allowed");
        }
        if (!names.add(name)) {
          throw new ModuleLoadException("the archive holds '" + name + "' twice");
        }
        Path out = into.resolve(name);
        long size = 0;
        try (InputStream in = zip.getInputStream(entry);
            var os = Files.newOutputStream(out)) {
          byte[] buffer = new byte[16 * 1024];
          for (int n; (n = in.read(buffer)) > 0; ) {
            size += n;
            total += n;
            if (size > MAX_FILE || total > MAX_TOTAL) {
              throw new ModuleLoadException("the archive is too large when unpacked");
            }
            os.write(buffer, 0, n);
          }
        }
      }
    } catch (ZipException e) {
      throw new ModuleLoadException(archive + " is not a module archive: " + e.getMessage(), e);
    }
    if (!names.contains("module.toml")) {
      throw new ModuleLoadException("the archive has no module.toml");
    }
    if (names.stream().noneMatch(n -> n.endsWith(".jar"))) {
      throw new ModuleLoadException("the archive has no jar file");
    }
  }

  private static void permissions(Path dir) throws IOException {
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"));
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : (Iterable<Path>) files::iterator) {
        Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-r--r--"));
      }
    }
  }

  private static void delete(Path dir) throws ModuleLoadException {
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) all.sorted(Comparator.reverseOrder())::iterator) {
        Files.deleteIfExists(p);
      }
    } catch (IOException e) {
      throw new ModuleLoadException("cannot remove " + dir + ": " + e.getMessage(), e);
    }
  }
}
