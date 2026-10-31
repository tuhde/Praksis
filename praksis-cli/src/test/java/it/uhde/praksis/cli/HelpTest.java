// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.StringWriter;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

class HelpTest {
  private final StringWriter out = new StringWriter();
  private final StringWriter err = new StringWriter();

  private int run(String... args) {
    CommandLine cli = new CommandLine(new Praksis());
    cli.setOut(new PrintWriter(out));
    cli.setErr(new PrintWriter(err));
    return cli.execute(args);
  }

  @Test
  void listsTheTopicsOfTheIndex() {
    assertThat(run("help")).isZero();
    assertThat(out.toString()).contains("user/concepts").contains("Getting started");
  }

  @Test
  void showsAPage() {
    assertThat(run("help", "user/concepts")).isZero();
    assertThat(out.toString()).startsWith("# Concepts");
  }

  @Test
  void anUnknownTopicFails() {
    assertThat(run("help", "nope")).isEqualTo(1);
    assertThat(err.toString()).contains("No help topic 'nope'");
  }
}
