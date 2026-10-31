// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.cli;

import java.util.List;
import picocli.CommandLine;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;

/**
 * The page {@code docs/reference/cli.md}, written from the command definitions so that it cannot
 * differ from what {@code praksis --help} says. A test checks the file.
 */
final class CliDocs {
  private CliDocs() {}

  static String page() {
    CommandSpec root = new CommandLine(new Praksis()).getCommandSpec();
    StringBuilder b = new StringBuilder();
    b.append("# Command line\n\n");
    b.append("<!-- Generated from the command definitions. Do not edit. -->\n\n");
    b.append("`praksis [--home DIR] <command>`. Exit codes: 0 done, 1 failed, 2 wrong usage, ");
    b.append("3 the daemon is not running.\n\n");
    b.append("Options of every command:\n\n");
    options(b, root.options());
    b.append("\n## Commands\n");
    for (CommandLine sub : root.subcommands().values().stream().distinct().toList()) {
      command(b, sub.getCommandSpec(), 3);
    }
    return b.toString().replaceAll("\n{3,}", "\n\n").stripTrailing() + "\n";
  }

  private static void command(StringBuilder b, CommandSpec spec, int level) {
    b.append("\n").append("#".repeat(level)).append(" praksis ").append(path(spec)).append("\n\n");
    String description = String.join(" ", spec.usageMessage().description());
    if (!description.isBlank()) {
      b.append(description).append("\n\n");
    }
    if (!spec.positionalParameters().isEmpty()) {
      b.append("Arguments:\n\n");
      for (PositionalParamSpec p : spec.positionalParameters()) {
        b.append("- `").append(p.paramLabel()).append("`").append(text(p)).append("\n");
      }
      b.append("\n");
    }
    List<OptionSpec> options =
        spec.options().stream().filter(o -> !o.inherited() && !o.usageHelp()).toList();
    if (!options.isEmpty()) {
      b.append("Options:\n\n");
      options(b, options);
    }
    for (CommandLine sub : spec.subcommands().values().stream().distinct().toList()) {
      command(b, sub.getCommandSpec(), level + 1);
    }
  }

  private static void options(StringBuilder b, List<OptionSpec> options) {
    for (OptionSpec o : options) {
      if (o.usageHelp() || o.versionHelp()) {
        continue;
      }
      b.append("- `").append(String.join(", ", o.names())).append("`");
      if (o.arity().max() > 0) {
        b.append(" `").append(o.paramLabel()).append("`");
      }
      b.append(text(o)).append("\n");
    }
  }

  private static String text(ArgSpec a) {
    String d = String.join(" ", a.description());
    return d.isBlank() ? "" : ": " + d;
  }

  private static String path(CommandSpec spec) {
    String qualified = spec.qualifiedName(" ");
    return qualified.startsWith("praksis ") ? qualified.substring("praksis ".length()) : qualified;
  }
}
