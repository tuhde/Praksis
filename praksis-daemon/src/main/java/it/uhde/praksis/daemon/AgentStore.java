// SPDX-License-Identifier: Apache-2.0
package it.uhde.praksis.daemon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * What the maintenance agent leaves behind: changes that it proposes to an AI-maintained layer,
 * staged in the data directory of the daemon (never in the layer, and never in git), and notes
 * (explanations that a rule wrote for a person to read). A staged change is a set of new files plus
 * a description; it only becomes part of a layer when the person exports it into their own checkout
 * and commits it themselves: Praksis writes no commit and pushes nothing.
 */
final class AgentStore {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final SecureRandom RANDOM = new SecureRandom();
  static final int MAX_NOTES = 200;
  static final int MAX_STAGED = 100;

  private final Path root;

  AgentStore(Path root) {
    this.root = root;
  }

  /** One staged change. */
  record Staged(
      String id,
      String layer,
      String rule,
      String reason,
      String created,
      String base,
      List<String> files) {}

  // ---------------------------------------------------------------------------- proposals

  Path proposals() {
    return root.resolve("proposals");
  }

  /** Stages the files; returns the id. Refuses when too many are waiting. */
  Staged stage(String layer, String rule, String reason, String base, Map<String, String> files)
      throws IOException {
    if (list().size() >= MAX_STAGED) {
      throw new IOException(
          "there are "
              + MAX_STAGED
              + " staged changes already: drop some (praksis agent drop <id>)");
    }
    String id = nextId();
    Path dir = proposals().resolve(layer).resolve(id);
    Files.createDirectories(dir.resolve("files"));
    for (var e : files.entrySet()) {
      Path target = dir.resolve("files").resolve(e.getKey());
      Files.createDirectories(target.getParent());
      Files.writeString(target, e.getValue(), StandardCharsets.UTF_8);
    }
    Staged staged =
        new Staged(
            id, layer, rule, reason, Instant.now().toString(), base, List.copyOf(files.keySet()));
    Map<String, Object> meta = new LinkedHashMap<>();
    meta.put("id", staged.id());
    meta.put("layer", staged.layer());
    meta.put("rule", staged.rule());
    meta.put("reason", staged.reason());
    meta.put("created", staged.created());
    meta.put("base", staged.base());
    meta.put("files", staged.files());
    JSON.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("proposal.json").toFile(), meta);
    return staged;
  }

  List<Staged> list() throws IOException {
    List<Staged> out = new ArrayList<>();
    if (!Files.isDirectory(proposals())) {
      return out;
    }
    try (Stream<Path> layers = Files.list(proposals())) {
      for (Path layer : (Iterable<Path>) layers::iterator) {
        try (Stream<Path> ids = Files.list(layer)) {
          for (Path dir : (Iterable<Path>) ids::iterator) {
            Path meta = dir.resolve("proposal.json");
            if (Files.isRegularFile(meta)) {
              JsonNode m = JSON.readTree(meta.toFile());
              List<String> files = new ArrayList<>();
              m.path("files").forEach(f -> files.add(f.asText()));
              out.add(
                  new Staged(
                      m.path("id").asText(),
                      m.path("layer").asText(),
                      m.path("rule").asText(),
                      m.path("reason").asText(),
                      m.path("created").asText(),
                      m.path("base").asText(),
                      files));
            }
          }
        }
      }
    }
    out.sort(Comparator.comparing(Staged::created).reversed());
    return out;
  }

  Optional<Staged> find(String id) throws IOException {
    return list().stream().filter(s -> s.id().equals(id)).findFirst();
  }

  /** The staged files, by their path in the layer. */
  Map<String, String> files(Staged staged) throws IOException {
    Map<String, String> out = new LinkedHashMap<>();
    Path base = proposals().resolve(staged.layer()).resolve(staged.id()).resolve("files");
    for (String file : staged.files()) {
      out.put(file, Files.readString(base.resolve(file), StandardCharsets.UTF_8));
    }
    return out;
  }

  void drop(Staged staged) throws IOException {
    Path dir = proposals().resolve(staged.layer()).resolve(staged.id());
    try (Stream<Path> all = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) all.sorted(Comparator.reverseOrder())::iterator) {
        Files.delete(p);
      }
    }
  }

  // ---------------------------------------------------------------------------- notes

  Path notes() {
    return root.resolve("notes");
  }

  /** A note that a rule wrote for a person to read. */
  record Note(String id, String at, String title, String text) {}

  Note saveNote(String title, String text) throws IOException {
    Files.createDirectories(notes());
    String id = nextId();
    Note note = new Note(id, Instant.now().toString(), title, text);
    Path tmp = Files.createTempFile(notes(), ".note", ".tmp");
    JSON.writeValue(
        tmp.toFile(),
        Map.of("id", note.id(), "at", note.at(), "title", note.title(), "text", note.text()));
    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
    Files.move(tmp, notes().resolve(id + ".json"), StandardCopyOption.ATOMIC_MOVE);
    List<Path> all = noteFiles();
    for (Path old : all.subList(0, Math.max(0, all.size() - MAX_NOTES))) {
      Files.deleteIfExists(old);
    }
    return note;
  }

  /** The newest notes first. */
  List<Note> notes(int limit) throws IOException {
    List<Path> files = noteFiles();
    List<Note> out = new ArrayList<>();
    for (Path f : files.reversed()) {
      if (out.size() == limit) {
        break;
      }
      JsonNode n = JSON.readTree(f.toFile());
      out.add(
          new Note(
              n.path("id").asText(),
              n.path("at").asText(),
              n.path("title").asText(),
              n.path("text").asText()));
    }
    return out;
  }

  private List<Path> noteFiles() throws IOException {
    if (!Files.isDirectory(notes())) {
      return List.of();
    }
    try (Stream<Path> files = Files.list(notes())) {
      return files.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
    }
  }

  // ---------------------------------------------------------------------------- plans

  static final int MAX_PLANS = 100;

  /** One call of a plan, written out: what it is, whether the grants cover it, what came of it. */
  record Step(
      String capability,
      Map<String, Object> args,
      String effect,
      boolean allowed,
      String refusal,
      String outcome,
      String warning) {
    Step withOutcome(String outcome) {
      return new Step(capability, args, effect, allowed, refusal, outcome, warning);
    }
  }

  /**
   * What a person asked in words and what the agent made of it: literal capability calls that the
   * person looks at and approves. {@code status}: PROPOSED, RUNNING, DONE or FAILED.
   */
  record Plan(
      String id,
      String created,
      String request,
      String reason,
      String layer,
      String status,
      List<Step> steps) {
    Plan with(String status, List<Step> steps) {
      return new Plan(id, created, request, reason, layer, status, steps);
    }
  }

  Path plans() {
    return root.resolve("plans");
  }

  /** Keeps a new plan; returns it with its id. */
  synchronized Plan savePlan(String request, String reason, String layer, List<Step> steps)
      throws IOException {
    if (listPlans().size() >= MAX_PLANS) {
      throw new IOException(
          "there are " + MAX_PLANS + " plans already: drop some (praksis agent plan-drop <id>)");
    }
    Plan plan =
        new Plan(nextId(), Instant.now().toString(), request, reason, layer, "PROPOSED", steps);
    writePlan(plan);
    return plan;
  }

  synchronized void updatePlan(Plan plan) throws IOException {
    writePlan(plan);
  }

  private void writePlan(Plan plan) throws IOException {
    Files.createDirectories(plans());
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("id", plan.id());
    m.put("created", plan.created());
    m.put("request", plan.request());
    m.put("reason", plan.reason());
    m.put("layer", plan.layer());
    m.put("status", plan.status());
    List<Object> steps = new ArrayList<>();
    for (Step st : plan.steps()) {
      Map<String, Object> o = new LinkedHashMap<>();
      o.put("capability", st.capability());
      o.put("args", st.args());
      o.put("effect", st.effect());
      o.put("allowed", st.allowed());
      o.put("refusal", st.refusal());
      o.put("warning", st.warning());
      o.put("outcome", st.outcome());
      steps.add(o);
    }
    m.put("steps", steps);
    Path tmp = Files.createTempFile(plans(), ".plan", ".tmp");
    JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), m);
    Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
    Files.move(
        tmp,
        plans().resolve(plan.id() + ".json"),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING);
  }

  /** The plans, newest first. */
  synchronized List<Plan> listPlans() throws IOException {
    if (!Files.isDirectory(plans())) {
      return new ArrayList<>();
    }
    List<Plan> out = new ArrayList<>();
    try (Stream<Path> files = Files.list(plans())) {
      for (Path f :
          (Iterable<Path>)
              files.filter(x -> x.getFileName().toString().endsWith(".json")).sorted()::iterator) {
        out.add(readPlan(JSON.readTree(f.toFile())));
      }
    }
    java.util.Collections.reverse(out);
    return out;
  }

  synchronized Optional<Plan> plan(String id) throws IOException {
    return listPlans().stream().filter(p -> p.id().equals(id)).findFirst();
  }

  synchronized boolean dropPlan(String id) throws IOException {
    if (!id.matches("[0-9]{13}-[0-9a-f]{6}")) {
      return false;
    }
    return Files.deleteIfExists(plans().resolve(id + ".json"));
  }

  @SuppressWarnings("unchecked")
  private static Plan readPlan(JsonNode n) {
    List<Step> steps = new ArrayList<>();
    for (JsonNode st : n.path("steps")) {
      steps.add(
          new Step(
              st.path("capability").asText(),
              JSON.convertValue(st.path("args"), Map.class),
              st.path("effect").asText(),
              st.path("allowed").asBoolean(),
              st.path("refusal").asText(),
              st.path("outcome").asText(),
              st.path("warning").asText()));
    }
    return new Plan(
        n.path("id").asText(),
        n.path("created").asText(),
        n.path("request").asText(),
        n.path("reason").asText(),
        n.path("layer").asText(),
        n.path("status").asText(),
        steps);
  }

  private static final java.util.concurrent.atomic.AtomicLong LAST =
      new java.util.concurrent.atomic.AtomicLong();

  /** An id that sorts by when it was made: milliseconds, never the same twice in this process. */
  private static String nextId() {
    long millis = LAST.updateAndGet(last -> Math.max(last + 1, System.currentTimeMillis()));
    return String.format("%013d", millis) + "-" + random(3);
  }

  private static String random(int bytes) {
    byte[] b = new byte[bytes];
    RANDOM.nextBytes(b);
    return HexFormat.of().formatHex(b);
  }
}
