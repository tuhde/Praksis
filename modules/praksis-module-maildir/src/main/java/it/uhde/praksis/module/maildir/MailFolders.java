// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.module.maildir;

import it.uhde.praksis.capability.Capability;
import it.uhde.praksis.capability.CapabilityContext;
import it.uhde.praksis.capability.CapabilityException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Lists the folders of the Maildir with the number of unread and of all mails. Only reads. */
public final class MailFolders implements Capability {
  private final Map<String, Settings> accounts = new java.util.LinkedHashMap<>();

  @Override
  public String name() {
    return "mail.folders";
  }

  @Override
  public void configure(Map<String, Object> config) throws CapabilityException {
    accounts.clear();
    accounts.putAll(Accounts.parse(config, Settings::parse));
  }

  @Override
  public Map<String, Object> invoke(CapabilityContext context, Map<String, Object> args)
      throws CapabilityException {
    if (accounts.isEmpty()) {
      throw new CapabilityException("mail.folders is not configured", false);
    }
    Settings settings = Accounts.settingsFor(accounts, args);
    try {
      List<Path> dirs = new ArrayList<>();
      dirs.add(settings.root());
      try (Stream<Path> list = Files.list(settings.root())) {
        list.filter(Files::isDirectory)
            .filter(d -> d.getFileName().toString().startsWith("."))
            .filter(d -> Files.isDirectory(d.resolve("cur")) || Files.isDirectory(d.resolve("new")))
            .sorted(Comparator.comparing(d -> d.getFileName().toString().toLowerCase()))
            .forEach(dirs::add);
      }
      List<Object> folders = new ArrayList<>();
      for (Path dir : dirs) {
        Map<String, Object> folder = new LinkedHashMap<>();
        boolean inbox = dir.equals(settings.root());
        folder.put(
            "name", inbox ? "INBOX" : dir.getFileName().toString().substring(1).replace('.', '/'));
        long[] counts = count(dir);
        folder.put("unread", counts[0]);
        folder.put("total", counts[1]);
        folders.add(folder);
      }
      return Map.of("folders", folders);
    } catch (IOException e) {
      throw new CapabilityException("cannot list the folders: " + e.getMessage(), true, e);
    }
  }

  /** The unread and the total number of mails of a folder. */
  private static long[] count(Path folder) throws IOException {
    long unread = 0;
    long total = 0;
    for (String dir : new String[] {"new", "cur"}) {
      Path d = folder.resolve(dir);
      if (!Files.isDirectory(d)) {
        continue;
      }
      try (Stream<Path> files = Files.list(d)) {
        for (Path f : files.filter(Files::isRegularFile).toList()) {
          total++;
          if (isUnread(dir, f.getFileName().toString())) {
            unread++;
          }
        }
      }
    }
    return new long[] {unread, total};
  }

  /** A mail is unread if it is still in {@code new} or its flags have no {@code S} (seen). */
  static boolean isUnread(String dir, String fileName) {
    if (dir.equals("new")) {
      return true;
    }
    int info = fileName.lastIndexOf(":2,");
    return info < 0 || fileName.indexOf('S', info) < 0;
  }
}
