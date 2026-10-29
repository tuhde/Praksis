// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import static org.assertj.core.api.Assertions.assertThat;

import it.uhde.praksis.protocol.Response;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class UploadsTest {
  @TempDir Path dir;

  private static final Instant NOW = Instant.parse("2026-03-04T05:06:07Z");

  @Test
  void aTicketIsAnEmptyFileForThisUserAloneNamedByAToken() throws Exception {
    Path uploads = dir.resolve("uploads");

    Response answer = Uploads.ticket(uploads, NOW);

    Response.Uploaded ticket = (Response.Uploaded) answer;
    assertThat(UUID.fromString(ticket.token())).isNotNull();
    Path file = Path.of(ticket.path());
    assertThat(file).isEqualTo(uploads.resolve(ticket.token() + ".part")).isEmptyFile();
    assertThat(Files.getPosixFilePermissions(file))
        .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    assertThat(Files.getPosixFilePermissions(uploads))
        .containsExactlyInAnyOrder(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    assertThat(((Response.Uploaded) Uploads.ticket(uploads, NOW)).token())
        .isNotEqualTo(ticket.token());
  }

  @Test
  void whatIsOlderThanADayIsRemovedAndTooManyTicketsAreRefused() throws Exception {
    Path uploads = Files.createDirectories(dir.resolve("u2"));
    Path old = Files.write(uploads.resolve("old.part"), new byte[] {1});
    Files.setLastModifiedTime(old, FileTime.from(NOW.minusSeconds(25 * 3600)));
    Path recent = Files.write(uploads.resolve("recent.part"), new byte[] {1});
    Files.setLastModifiedTime(recent, FileTime.from(NOW.minusSeconds(3600)));

    Uploads.ticket(uploads, NOW);

    assertThat(old).doesNotExist();
    assertThat(recent).exists();

    for (int i = 0; i < Uploads.MAX_FILES; i++) {
      Files.write(uploads.resolve("f" + i + ".part"), new byte[0]);
    }
    assertThat(Uploads.ticket(uploads, NOW)).isInstanceOf(Response.Error.class);
  }
}
