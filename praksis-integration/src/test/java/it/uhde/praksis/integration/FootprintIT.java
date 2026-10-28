// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Measures the memory of the daemon from the runtime image after it has done real work, and fails
 * if it grows far beyond what was measured when this test was written (about 200 MB). The numbers
 * are printed so a build log shows the trend. Linux only: it reads {@code /proc}.
 */
class FootprintIT {
  private static long kilobytes(Path status, String field) throws Exception {
    for (String line : Files.readAllLines(status)) {
      if (line.startsWith(field + ":")) {
        return Long.parseLong(line.replaceAll("\\D+", ""));
      }
    }
    throw new AssertionError(field + " not in " + status);
  }

  @Test
  void theDaemonStaysWithinItsMemoryBudgetAfterHandlingMail() throws Exception {
    Path status;
    try (Harness h = new Harness()) {
      long begin = System.nanoTime();
      Process daemon = h.startDaemon();
      long readyMillis = (System.nanoTime() - begin) / 1_000_000;
      status = Path.of("/proc", String.valueOf(daemon.pid()), "status");
      assumeTrue(Files.exists(status), "needs /proc");

      int mails = 300;
      for (int i = 0; i < mails; i++) {
        h.deliver(String.format("17000%05d.M1P%d.host", i, i), "mail " + i);
      }
      Harness.await("all mails to be archived", () -> h.names(".Archive/cur").size() == mails);
      Harness.await("the queue to be empty", () -> h.status().pending() == 0);
      Thread.sleep(3000);

      long rss = kilobytes(status, "VmRSS") / 1024;
      long peak = kilobytes(status, "VmHWM") / 1024;
      System.out.printf(
          "footprint: ready after %d ms, %d mails handled, RSS %d MB, peak RSS %d MB%n",
          readyMillis, mails, rss, peak);
      assertThat(h.log()).extracting(l -> l.outcome()).doesNotContain("FAILED");
      assertThat(peak).as("peak RSS in MB").isLessThan(400);
      assertThat(readyMillis).as("start to answering, in ms").isLessThan(10_000);
    }
  }
}
