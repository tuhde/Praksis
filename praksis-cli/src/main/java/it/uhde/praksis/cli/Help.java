// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code praksis help [topic]}: shows the documentation pages that were copied from {@code docs/}
 * into the resources at build time. Needs no daemon.
 */
@Command(
    name = "help",
    description =
        "Shows the documentation. Without a topic it lists the topics; a topic is the path of a"
            + " page without .md, for example user/concepts.")
final class Help implements Callable<Integer> {
  private static final Pattern LINK = Pattern.compile("^- \\[([^\\]]+)\\]\\(([^)]+)\\.md\\)\\s*$");

  /** One page of the index. */
  record Topic(String name, String title) {}

  @Parameters(arity = "0..1", paramLabel = "<topic>", description = "The page to show.")
  String topic;

  @Spec CommandSpec spec;

  @Override
  public Integer call() throws IOException {
    var out = spec.commandLine().getOut();
    if (topic == null) {
      out.println("Topics (praksis help <topic>):");
      for (Topic t : topics()) {
        out.printf("  %-24s %s%n", t.name(), t.title());
      }
      out.flush();
      return 0;
    }
    String name = topic.endsWith(".md") ? topic.substring(0, topic.length() - 3) : topic;
    if (topics().stream().noneMatch(t -> t.name().equals(name))) {
      spec.commandLine().getErr().println("No help topic '" + topic + "'. See: praksis help");
      return Praksis.EXIT_FAILED;
    }
    out.print(read(name));
    out.flush();
    return 0;
  }

  /** The pages listed in {@code docs/index.md}, in its order. */
  static List<Topic> topics() throws IOException {
    List<Topic> topics = new ArrayList<>();
    for (String line : read("index").split("\n")) {
      Matcher m = LINK.matcher(line);
      if (m.matches()) {
        topics.add(new Topic(m.group(2), m.group(1)));
      }
    }
    return topics;
  }

  static String read(String name) throws IOException {
    try (InputStream in = Help.class.getResourceAsStream("/docs/" + name + ".md")) {
      if (in == null) {
        throw new IOException("The page docs/" + name + ".md is missing from this build.");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
