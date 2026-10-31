// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import it.uhde.praksis.docs.Docs;
import java.util.concurrent.Callable;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.Spec;

/**
 * {@code praksis help [topic]}: shows the documentation pages that ship with the program. Needs no
 * daemon.
 */
@Command(
    name = "help",
    description =
        "Shows the documentation. Without a topic it lists the topics; a topic is the path of a"
            + " page without .md, for example user/concepts.")
final class Help implements Callable<Integer> {
  @Parameters(arity = "0..1", paramLabel = "<topic>", description = "The page to show.")
  String topic;

  @Spec CommandSpec spec;

  @Override
  public Integer call() {
    var out = spec.commandLine().getOut();
    if (topic == null) {
      out.println("Topics (praksis help <topic>):");
      for (Docs.Topic t : Docs.topics()) {
        out.printf("  %-24s %s%n", t.name(), t.title());
      }
      out.flush();
      return 0;
    }
    var found = Docs.find(topic);
    if (found.isEmpty()) {
      spec.commandLine().getErr().println("No help topic '" + topic + "'. See: praksis help");
      return Praksis.EXIT_FAILED;
    }
    out.print(Docs.read(found.get().name()));
    out.flush();
    return 0;
  }
}
