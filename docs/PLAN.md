# Praksis: Concept Summary and Project Plan

## Context

The goal is to replace the "one program per function" model of local software with one general local system. Modules provide capabilities, and rulesets define what the software actually does. Nothing is built yet (the repo is empty and clean), so this plan records the ideas gathered so far, lists the open questions, and proposes a sequence for design and implementation. It is a starting point to refine, not a final design.

## Core Concept

- **Capabilities** (what we are able to do) are the union of everything the installed modules provide. Modules may be composed into higher-level capabilities.
- **Policies** (what we are willing to do and have to) are expressed as rulesets. Rulesets are the only layer that calls modules.
- Capabilities being available does not mean they run. Rulesets decide what is used, when and under which conditions.

## Components Gathered So Far

1. **Modules**: plugins that provide capabilities, including access to external storage (S3, WebDAV, IMAP, ...).
2. **Rulesets**: define actions, with access to modules.
   - Hosted in git repos, so versioned and auditable.
   - Cascadable: a public upstream ruleset plus a private local layer (e.g. "use the public mail processing ruleset plus one extra rule of mine").
3. **Triggers**: user initiation, scheduler events, external events.
4. **Backend (engine)**: runs the rulesets, ideally on the same machine, ideally with a GUI. Apache NiFi was considered and rejected as the runtime (see Decisions).
5. **Frontend**: desktop app for all major OSes, starting with Linux. JavaFX is preferred.
6. **Local storage**: simple to maintain but flexible, with the same interface for local and external storage.
7. **Configuration store**: configuration, access control, credential management.

## Observations and Leanings (not decisions)

- **Frontend**: JavaFX fits (cross-platform, JGit, Java module system). Running the engine as its own local process makes the frontend one client among several and lets scheduled and external events run with no window open.
- **NiFi**: good for modules/processors, scheduling, GUI and provenance, but it is a dataflow tool, not a ruleset engine. It has no native layering of flows, is heavy for a desktop, and gives limited control over permissions for foreign rulesets. A lighter custom engine, with NiFi as an option or a reference, may fit better.
- **Storage**: SQLite for engine state (events, history, settings), files plus an index for user data, behind a common storage interface.
- **Credentials**: stored separately from rulesets. Rulesets reference credentials by name only. Use OS-native secret stores (libsecret, Keychain, Credential Manager) or an encrypted vault.
- **Trust**: public rulesets are untrusted input. Each ruleset is granted only specific capabilities and credentials.

## Decisions

### Cascading semantics
- Every rule has a **stable ID**.
- A ruleset is an **ordered stack of layers**, applied bottom to top (no multiple inheritance, so no diamond problems).
- A layer's operations are `add`, `replace`, `disable` and `patch` (change a single field of an upstream rule). A target that is missing or has changed is a visible error, never a silent skip.
- Upstream rulesets are **pinned** to a commit or tag in a lockfile. Updates are explicit.
- The engine offers an **effective view**: the merged result, with the layer each rule comes from.
- **Permissions are not overridable by rulesets.** Grants for capabilities and credentials stay local.
- Extension points that upstream authors declare may be added later as an optional feature.

### Rule execution
- **All matching rules fire**, ordered by layer position, then declaration order. There are no numeric priorities. Phases are deferred: plain ordering is enough for the first version.
- `stop` is an explicit action, so first-match behavior is available where wanted.
- **Conflicting effects** on the same target are an error. The verifier detects them statically where it can, and the engine catches them at runtime.
- Rules may **emit events**, with a depth limit and loop detection. There is no fixpoint (forward) chaining.
- Every event has an **idempotency key**, so reprocessing an event does nothing twice.
- Events are processed **one at a time per target** and in parallel across different targets.
- On failure the engine records it and continues with the other rules, unless the rule is marked as required. Retry and compensation are deferred.
- The same event and the same ruleset always produce the same actions in the same order, which keeps replay and shadow mode meaningful.

### Ruleset format
- **TOML** files with a published schema, **one file per rule**, named by its stable ID. Actions are written as arrays of tables (`[[action]]`) to keep nesting manageable.
- **Conditions and value expressions are written in CEL** (Common Expression Language): sandboxed, side-effect-free, always terminating, with an official Java implementation.
- **Actions are structured calls** to named module capabilities with typed parameters, never free-form code.
- Each rule declares its **effect classification** and the **capabilities and credentials** it needs, so the verifier can check them and compute the permission diff.
- Every ruleset carries a **format version**, so an engine can reject rulesets it cannot run.
- Rejected: a custom DSL (grammar and tooling cost, harder to layer, less reliable for an AI), embedded general-purpose scripting (not verifiable, code execution for public rulesets), and an existing rules engine's native format (ties the project to its execution model).

### Triggers and data model
- **One typed event bus.** Everything is an event: schedule ticks, user commands and events from modules (e.g. `mail.received`). Rules subscribe by event type with an optional CEL filter.
- **Event envelope** (CloudEvents-like): `id`, `type`, `source`, `time`, `target`, `key` (idempotency), `parent` and `depth` (for emitted events) and a `data` payload. The `target` drives the per-target serialization.
- **Hybrid data model.** The envelope and the core fields of each event type are typed through the declaring module's metadata. A free-form `data` map holds module-specific extras. The verifier type-checks CEL expressions and action arguments against the typed parts.
- **Triggers** live in a `[trigger]` section with one `kind`:
  - `event`: `type` plus an optional CEL `filter`.
  - `schedule`: `cron`, `timezone` and `catch_up = "skip" | "once"` (default `skip`) for runs missed while the daemon was down.
  - `command`: a name, a title and typed parameters, so a frontend can generate forms.
- **External event sources** (IMAP idle, file watcher, webhook endpoint via Camel) are provided by modules. Listening is itself a capability and needs a grant.
- **Actions** are `[[action]]` tables with an `id`, a `capability` and `args`. Each argument is a literal or an explicit expression, `{ cel = "..." }`, so there is no string-templating ambiguity.
- **CEL variables:** `event`, `results.<action_id>` (outputs of earlier actions in the same rule), `params` (ruleset parameters) and `now`.
- **Ruleset parameters:** a ruleset declares named parameters with defaults, and a layer can set them without patching rules.
- Rules communicate only through emitted events. There is no shared mutable state in the engine, and persistence goes through a storage capability. **No per-rule persistent state in the first version.**
- Not chosen: separate trigger kinds with separate semantics, and state reconciliation (polling against a desired state) as the core model.

Example rule:
```toml
id = "mail.archive-newsletters"
effect = "local-write"
needs = ["mail.move"]

[trigger]
kind = "event"
type = "mail.received"
filter = "event.data.headers['List-Id'] != ''"

[[action]]
id = "move"
capability = "mail.move"
args = { message = { cel = "event.data.message" }, folder = { cel = "params.newsletter_folder" } }
```

### Engine
- A **custom engine in Java, running as a daemon** (a separate local process), so scheduled and external events work with no window open. Frontends, including a CLI, connect through a local API.
- **Apache Camel** is used inside modules where it saves work (connectors for IMAP, S3, WebDAV, files and more). It is not the engine.
- The engine core stays **free of JavaFX and other UI dependencies**, so the runtime could be changed later.
- Events are **persisted in SQLite**, so a crash does not lose them.
- Packaging with a bundled runtime per OS (`jlink` and `jpackage`).
- Rejected: Apache NiFi as the runtime (its flow-graph model does not map onto flat rule ordering, layering and per-target serialization, and it is heavy for a desktop), a Go or Rust engine (Java preferred, one language with the frontend), and existing workflow engines such as Node-RED, n8n, Temporal and Camunda (they impose their own models). NiFi may still be added later as a module for heavy data jobs.
- The risks were checked in the **M0 spike** (verdict: go, see `spikes/m0/FINDINGS.md`): cel-java type-checks events whose types are built at runtime as protobuf descriptors, and a `jlink` image with a Camel layer used about 100 MB of memory and 0.8 s for a short run. cel-java is still 0.x, so pin its version and keep it behind our own expression interface. The footprint of the real engine is to be re-measured at M1.

### Module system
- **JPMS** (the Java module system). A `capability-api` module holds the capability interfaces and metadata types. Modules implement them with `provides ... with`, and the engine finds them through `ServiceLoader`.
- Modules are loaded from a modules directory at startup into **their own `ModuleLayer`**. There is no unload, so removing a module takes a restart.
- If Camel causes JPMS problems (automatic modules, split packages), its jars are **encapsulated inside our own JPMS modules**, and only our own service interfaces expose the capability. Rulesets and the engine never see Camel types.
- Each capability's **name, typed parameters, effect classification and dry-run support** are declared as metadata, because the verifier depends on them.
- JPMS gives encapsulation, not a sandbox. The working assumption is that **modules are trusted code** that the user installs deliberately. Rulesets are the untrusted part, and they can only reach modules through engine-checked capability calls. If untrusted third-party modules ever matter, process isolation can be added behind the same capability interface.

### Permission model
- **Default deny.** Grants live in a **local policy file outside the ruleset repos**, with **one grant set per layer**, so a private layer and its public upstream hold separate grants.
- **Scoped capabilities.** A grant names a capability plus constraints on its arguments (e.g. `mail.move` only on account "work" and only to folders under `Archive/`). Modules declare in their metadata which parameters can be scoped.
- **Credentials by local binding.** A rule uses a local name (e.g. `mail-account:work`). A local binding maps the name to the real account and secret and limits it to specific capabilities. Rulesets never contain or see secrets.
- **Effective permissions** are the intersection of what the rules declare and what the user granted. The engine enforces this on every capability call.
- **Destructive and external effects** require an explicit grant.
- **Only the user can apply grants.** The AI can propose grants but never apply them. A ruleset update that needs more access produces a **permission diff** and needs a new grant first.
- **Runtime prompting as a fallback.** When a rule needs something that is not granted, the engine does not block. It disables that rule, queues an approval request and notifies the user. The user answers in a frontend with allow once, allow always (which creates a scoped grant) or deny. The AI cannot answer prompts.
- **Audit log** in SQLite of every capability call and every grant change. Grants can be revoked.

### Frontend and views
- **The frontend is a generic renderer with no functionality of its own.** It displays what the daemon describes and sends user actions back as API calls (commands, approval answers). It holds no credentials and no rules.
- **FXML is not the source of UI descriptions.** FXML is JavaFX-specific and can reference controllers and scripts, which would bring code into the frontend and, for rulesets, execute untrusted code. The frontend may use bundled FXML only for its generic widget components, with no controllers beyond the generic ones.
- **The daemon describes the UI** through the local API. Sources:
  1. The engine's own screens: status, effective ruleset view, approval queue, grants, audit log.
  2. Commands: rules with a `command` trigger declare a title and typed parameters, and the frontend generates the form from them.
  3. Views declared by modules and rulesets.
- **Ownership split.** Modules provide data (read-only capability queries) and the default presentation of their data types as descriptions, not code. Rulesets decide what happens and which screens exist: a **view** composes data from queries into a screen with columns, buttons and commands. The daemon serves the description and runs the queries under the ruleset's grants.
- **View format.** A small vocabulary of our own in TOML, with a fixed catalog of components (table, list, detail, form, button, text). Values are literals or CEL expressions, the data source is a read-only capability call, and user actions are commands sent back to the daemon. These ideas are taken from A2UI, whose concepts match closely. A2UI (young, v0.8) and Adaptive Cards (SDKs for JavaScript, .NET, UWP, WinUI, Android and iOS, none for Java) were not adopted directly, because they are JSON-based and would not fit layering, CEL and the verifier without translation, and either way a JavaFX renderer would have to be written. The vocabulary stays simple enough to be translated to such formats later for other frontends.
- Views take part in layering and are checked by the verifier. The UI schema is versioned, so an older frontend can report that it does not understand a newer view.

Example view:
```toml
id = "view.newsletters"
title = "Newsletters this week"

[source]
capability = "mail.list"
args = { folder = { cel = "params.newsletter_folder" } }

[[component]]
type = "table"
rows = { cel = "source.items" }
columns = [
  { title = "From", value = "item.from" },
  { title = "Subject", value = "item.subject" },
  { title = "Date", value = "item.date", format = "datetime" },
]

[[component.row_action]]
label = "Open"
command = "mail.open"
args = { message = { cel = "item.id" } }
```

The daemon evaluates the source query and sends the frontend a resolved description. The frontend never runs CEL or queries anything itself.

### Build and runtime
- **Maven** with a multi-module build, on the latest LTS JDK.
- **Maven group ID `it.uhde`**, root package **`it.uhde.praksis`**. Artifact IDs are `praksis-<part>` (e.g. `praksis-capability-api`). JPMS module names equal the root package of the module (e.g. `it.uhde.praksis.capability.api`).
- The spike under `spikes/m0` still uses the placeholder `org.praksis.spike` and is not renamed.

### Approval policy for AI changes
Approval is tiered by what a change does:
- **Always needs the user's approval:** widening grants, new or changed rules with a destructive or external effect class, and edits to rules written by the user (the engine tracks the author of each rule).
- **Everything else** that passes the verifier can merge automatically, but it **runs in shadow mode first** and is promoted to live after a trial period if nothing unexpected shows up.
- The policy is a local setting. The strict version above is the default.

### AI scope
- Each layer has an **`ai_maintained` setting**, on by default for the user's private layers and off for upstream layers.
- For an upstream layer, the AI can prepare a change on a branch for the user to submit. It never pushes to a repo that is not the user's. Pushing to any remote always needs the user's action.

### Module trust and metadata
- Modules are **trusted code that the user installs deliberately**. There is no open third-party marketplace for now. A **local modules lockfile** records each module jar's hash, so a swapped jar is detected at startup.
- Capability metadata (name, typed parameters, effect class, scopable parameters, dry-run support, and the **JDK modules the module needs**, e.g. `java.sql`, which the runtime image must contain) is written as **annotations** in code. An **annotation processor in the Maven build generates a descriptor** (TOML or JSON) that ships inside the module. The verifier, the engine and the AI read the descriptor without running module code, and the build fails if annotations and descriptor disagree.

### Runtime prompting
- When a rule needs something that is not granted, **only that rule is disabled**. Other rules keep running, so nothing blocks and per-target ordering is preserved.
- Events the rule would have matched are kept in a **missed list**. When the user answers, they choose to **replay the missed events in order** or discard them. Retention is limited, by default 7 days or a count cap, and configurable.
- The approval request itself **never expires**.
- **Allow once** covers the single pending call. **Allow always** creates a grant scoped to exactly what was observed (the capability plus, for example, the specific account and folder). The user can narrow it. Widening beyond what was observed takes a deliberate edit, and there are no wildcard grants unless the user chooses one.

### Code structure (M1)
Maven modules, all under group `it.uhde`, in one repo (the spike under `spikes/` is not part of the build):

| Module | Purpose |
|---|---|
| `praksis-parent` | parent pom: versions, plugins, formatting, license headers |
| `praksis-capability-api` | the API modules implement; the only thing modules depend on |
| `praksis-expression` | our expression interface; the only place that knows cel-java and protobuf |
| `praksis-ruleset` | rule model, TOML loader, static validation |
| `praksis-store` | SQLite persistence; the only place that knows the SQLite driver |
| `praksis-engine` | module loader, event bus, dispatcher, capability invocation |
| `praksis-protocol` | request and response records shared by the daemon and the CLI |
| `praksis-daemon` | `main()`, lifecycle, local API server |
| `praksis-cli` | the `praksis` command |
| `modules/praksis-module-maildir` | first module: `mail.received` event, `mail.move` capability |
| `praksis-dist` | jlink image and footprint check |

- Dependency rules: modules depend only on `praksis-capability-api`. Only `praksis-expression` sees cel-java, and only modules see Camel. `praksis-ruleset` depends on `praksis-expression` and `praksis-capability-api`. `praksis-engine` depends on `praksis-ruleset`, `praksis-store`, `praksis-expression` and `praksis-capability-api`. The daemon and the CLI share only `praksis-protocol`.
- Hooks built into M1 so later milestones need no refactoring: every capability call goes through an `AccessPolicy` interface (allow-all until M3), the audit log is a hook with a no-op implementation, and the dry-run flag is part of the capability context.
- **Module descriptors** are written by hand as a `module.toml` inside each module in M1. The annotation processor that generates them is added before M4, and it targets the descriptor format defined in M1.
- **Local API** between the CLI and the daemon: a Unix domain socket with JSON messages, not HTTP on localhost, so access is controlled by file permissions.
- **Libraries:** Jackson (TOML and JSON), picocli (CLI), JUnit 5 with AssertJ (tests), the Xerial SQLite driver. Each is checked inside module layers when it is first introduced.
- **Build conventions:** Java 25, the Maven wrapper (Maven 3.9.9), `-Xlint:all` with warnings as errors, Spotless with google-java-format for formatting and SPDX headers (`./mvnw spotless:apply` fixes violations, and the check runs at `verify`), and an enforcer rule for the Maven and Java versions. google-java-format is pinned to 1.36.0 because 1.37.0 fails under the current Spotless Maven plugin. Modules are added to the parent pom with the milestone that introduces them.

### License
- **Apache-2.0 for all code.** One license, a patent grant, and it matches the dependency ecosystem (Camel, cel-java and others). Modules and rulesets by other authors may use any license. The consequence is that proprietary forks are allowed.
- Every source file carries `// SPDX-License-Identifier: Apache-2.0`. The repo has a `LICENSE` and a `NOTICE` file.
- Contributions are accepted under the **DCO** (a `Signed-off-by` line), not a CLA. Relicensing later would need every contributor's consent.
- Documentation may use CC-BY-4.0, and example rulesets published by the project may use CC0 or Apache-2.0. Not decided yet.
- To do before a release: run a license scanner over the dependency tree, and have the license of AI-assisted code confirmed by a lawyer if there are commercial plans.

### Verification (rulesets are maintained by an AI)
The verifier is deterministic code in the core. It is not an AI, and the AI maintaining rulesets cannot change it.
1. **Static checks**: schema validity, unique rule IDs, resolvable patch targets, existing capabilities, credentials referenced by declared name only, no loops between rules that trigger each other.
2. **Permission diff**: effective permissions before and after a change. A change that adds access cannot pass automatically.
3. **Tests that live with the ruleset**: example cases (given this event, expect these actions) with mocked modules. Rule changes must come with test changes.
4. **Replay**: past events from the engine history are run through the proposed ruleset with modules in dry-run mode, producing a behavioral diff.
5. **Shadow mode**: a new or changed rule can first run observe-only and be promoted after a trial period.

Workflow: the AI works on a git branch, never directly on the active ruleset. The verifier produces a report (checks, permission diff, behavioral diff). Every change records its author (AI or human), the reasoning and the verification result. Rollback is a git revert.

Consequences for the design:
- Rules declare an **effect classification**: read-only, local write, destructive, or external side effect.
- Modules must provide a **dry-run mode**.

## Open Questions

1. **View details**: the exact widget vocabulary, how a view refers to its data source and formats values, and how a module declares the default presentation of its data types. To be settled when the frontend milestone (M6) comes up.

## Roadmap

The first version is **M0 to M4**: headless (CLI only), mail processing as the only use case. The aim is to prove the new ideas (layered rulesets, permissions, verification) first. The GUI is a later client of the daemon.

- **M0, spike (go/no-go): done, verdict go.** cel-java type-checking of runtime-built event types, a dummy JPMS module in its own `ModuleLayer`, a Camel file component and IMAP endpoint inside a layer, and memory and startup with `jlink`. See `spikes/m0/FINDINGS.md`. Not covered yet: a real IMAP connection, layer unloading and Windows/macOS.
- **M1, walking skeleton:** `capability-api`, the daemon, the event bus with a SQLite queue, a loader for TOML rules, CEL evaluation, and one module with a `mail.received` event and a `mail.move` action (a local **Maildir** source first). The CLI starts the daemon, injects events and shows the log. Done when a mail dropped into the source is moved by a rule and a daemon restart does not process it twice. Real **IMAP via Camel** follows right after M1, unless the spike shows trouble.
- **M2, layers:** load rulesets from git with JGit, pin upstreams in a lockfile, apply `add`, `replace`, `disable` and `patch` from a private layer, support ruleset parameters, and add an effective-view command. Done when a public mail ruleset plus one private rule behaves as expected and survives an upstream update.
- **M3, permissions:** the local policy file, grants per layer, scoped capabilities, credential bindings, enforcement at the capability call, the audit log, and the disable-and-queue behavior with a CLI to answer requests. Done when an ungranted rule is disabled, reported and can be approved.
- **M4, verifier:** static checks, permission diff, ruleset tests with mocked modules, dry-run, and replay with a behavioral diff. Shadow mode comes last. Done when the verifier rejects a deliberately broken or over-permissive change.
- **M5, AI maintenance:** the AI works on git branches, the verifier report is produced, and the user approves.
- **M6, JavaFX frontend:** a client of the local API for status, the effective view, the approval queue, commands and views (see the Frontend decision).
- **M7, extensions:** schedules, more triggers and modules, then Windows and macOS.

An architecture document (components, module API, storage interface, event model, trust model) is written alongside M0 and M1.

## Name Suggestions

The concept: a general core, behaviour defined by rules, built from layered modules. The project started under the working title "SoftwareNG" ("next generation"). "Praksis" is the provisional name.

| Name | Idea |
|---|---|
| **Praxis** | Greek for "putting theory into action": rules become actions |
| **Loom** | Weaves modules and layered rulesets into what you actually need |
| **Canon** | The authoritative body of rules, and also a nod to "cascading" layers |
| **Tessera** | A single tile of a mosaic: many small modules forming one picture |
| **Concord** | Parts working together under shared agreements (rules) |
| **Keystone** | The central piece that makes the whole structure work |
| **Weft** | The crosswise threads that give the weave its pattern: rulesets over a fixed base of modules |

Decision: **Praksis**, the Danish variant of Praxis, is the provisional name. Loom was rejected because of the Atlassian product and Java's Project Loom. Praxis was rejected because it is crowded (PraxisLIVE, several trademarks, a pending PRAXIS OS filing). Trademark, domain and existing-project checks for "Praksis" (DKPTO, EUIPO, USPTO, praksis.* domains) are still open.

## Verification

Nothing is implemented yet, so there is nothing to test. The checks for this planning step are:
- The concept summary is confirmed to match the intent and corrected where it does not.
- The open questions are answered or deliberately deferred.
- A name is chosen after a conflict check.
