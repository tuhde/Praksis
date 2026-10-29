// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.stream.Stream;

final class Jars {
  private Jars() {}

  /** Writes the files below {@code classes} as a jar. */
  static void pack(Path classes, Path jar) throws IOException {
    try (OutputStream out = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(out);
        Stream<Path> files = Files.walk(classes)) {
      for (Path f : files.filter(Files::isRegularFile).toList()) {
        jos.putNextEntry(new JarEntry(classes.relativize(f).toString().replace('\\', '/')));
        jos.write(Files.readAllBytes(f));
        jos.closeEntry();
      }
    }
  }

  /**
   * Copies the libraries a mail module needs next to it, as they are in the module directory of the
   * image: the HTML sanitizer (a jar, or the classes of the reactor build) and jsoup. They are
   * found on the module path or class path of the test.
   */
  static void copyLibraries(Path moduleDir) throws IOException {
    String paths =
        System.getProperty("jdk.module.path", "")
            + java.io.File.pathSeparator
            + System.getProperty("java.class.path", "");
    boolean html = false;
    boolean jsoup = false;
    for (String entry : paths.split(java.io.File.pathSeparator)) {
      if (entry.isEmpty()) {
        continue;
      }
      Path p = Path.of(entry);
      String name = p.getFileName().toString();
      if (!html && name.startsWith("praksis-mail-html") && Files.isRegularFile(p)) {
        Files.copy(p, moduleDir.resolve(name));
        html = true;
      } else if (!html && Files.isDirectory(p) && p.toString().contains("praksis-mail-html")) {
        pack(p, moduleDir.resolve("praksis-mail-html.jar"));
        html = true;
      } else if (!jsoup && name.startsWith("jsoup-") && Files.isRegularFile(p)) {
        Files.copy(p, moduleDir.resolve(name));
        jsoup = true;
      }
    }
    if (!html || !jsoup) {
      throw new IOException("the HTML library and jsoup are not on the path of the test");
    }
  }
}
