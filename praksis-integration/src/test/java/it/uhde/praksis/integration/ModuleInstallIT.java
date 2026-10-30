// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * {@code praksis modules install} with the runtime image and the archives that the build made: a
 * module from an archive is unpacked in the home, accepted in the lockfile and loaded by the daemon
 * instead of the one that ships with the image, a changed one is refused, and a bad archive
 * installs nothing.
 */
class ModuleInstallIT {
  @Test
  void aModuleFromAnArchiveHidesTheOneOfTheImageAndIsHeldToItsHash() throws Exception {
    try (Harness h = new Harness()) {
      Path archive = h.archive("maildir");
      assertThat(archive).exists();

      Harness.CliResult installed = h.cli("modules", "install", archive.toString());
      assertThat(installed.exit()).as(installed.output()).isZero();
      assertThat(installed.output()).contains("Installed module 'maildir'");
      assertThat(h.home.resolve("modules/maildir/module.toml")).exists();
      assertThat(Files.readString(h.home.resolve("modules.lock"))).contains("maildir");

      // listed twice, the one of the image hidden
      String listed = h.cli("modules", "list").output();
      assertThat(listed.lines().filter(l -> l.startsWith("maildir"))).hasSize(2);
      assertThat(listed).contains("hidden by a module of the same name");

      // the daemon runs with it: the rule of the harness moves a mail
      Process daemon = h.startDaemon();
      Files.writeString(h.mail.resolve("new/1.a"), "From: x@example.org\nSubject: hi\n\nbody\n");
      Harness.await("the mail to be archived", () -> !h.names(".Archive/cur").isEmpty());
      h.stop(daemon);

      // a repeated install needs --replace
      Harness.CliResult again = h.cli("modules", "install", archive.toString());
      assertThat(again.exit()).isEqualTo(1);
      assertThat(again.output()).contains("already installed");
      assertThat(h.cli("modules", "install", archive.toString(), "--replace").exit()).isZero();

      // the copy in the home is the one that is held to its hash: change it and the daemon refuses
      Path jar;
      try (var files = Files.list(h.home.resolve("modules/maildir"))) {
        jar =
            files
                .filter(f -> f.getFileName().toString().startsWith("praksis-module-maildir"))
                .findFirst()
                .orElseThrow();
      }
      Files.write(jar, new byte[] {0}, java.nio.file.StandardOpenOption.APPEND);
      assertThatThrownBy(h::startDaemon)
          .hasMessageContaining("has changed since it was locked")
          .hasMessageContaining(h.home.resolve("modules/maildir").toString());

      // removing it brings the one of the image back, and the daemon starts again
      Harness.CliResult removed = h.cli("modules", "remove", "maildir");
      assertThat(removed.exit()).isZero();
      // the lockfile has one entry per name: the one of the image has to be accepted again
      assertThat(removed.output()).contains("is used again; accept it with 'praksis modules lock'");
      assertThat(h.cli("modules", "lock").exit()).isZero();
      assertThat(h.home.resolve("modules/maildir")).doesNotExist();
      assertThat(h.cli("modules", "list").output().lines().filter(l -> l.startsWith("maildir")))
          .hasSize(1);
      Process back = h.startDaemon();
      h.stop(back);
    }
  }

  @Test
  void anArchiveWithAPathInstallsNothing() throws Exception {
    try (Harness h = new Harness()) {
      Path evil = h.home.resolve("evil.praksis-module");
      try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(evil))) {
        zip.putNextEntry(new ZipEntry("module.toml"));
        zip.write(
            "format_version = 1\nname = \"evil\"\nversion = \"1\"\nmodule = \"x\"\n".getBytes());
        zip.closeEntry();
        zip.putNextEntry(new ZipEntry("../../evil.jar"));
        zip.write(1);
        zip.closeEntry();
      }

      Harness.CliResult refused = h.cli("modules", "install", evil.toString());

      assertThat(refused.exit()).isEqualTo(1);
      assertThat(refused.output()).contains("only files at the top level");
      assertThat(h.home.resolve("modules/evil")).doesNotExist();
      assertThat(h.home.getParent().resolve("evil.jar")).doesNotExist();
      assertThat(Files.readString(h.home.resolve("modules.lock"))).doesNotContain("evil");
    }
  }
}
