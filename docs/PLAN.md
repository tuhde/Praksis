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
- Risks to check with a short spike before building on it: **cel-java** support for the CEL features the rules need, and the **JVM memory footprint and startup time** with `jlink`.

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

1. **Approval policy for AI changes**: changes that widen grants always need human approval (decided, see Permission model). Still open: do destructive or external actions, or edits to human-written rules, also always need approval when they stay inside existing grants?
2. **AI scope**: does the AI maintain public upstream rulesets, only the private layer, or both?
3. **Module trust and metadata**: confirm that modules are always trusted, installed code (no open third-party marketplace for now). How is capability metadata declared: annotations, a descriptor file, or both?
4. **Runtime prompting details**: while a prompt is pending, is the triggering event held in the queue or skipped? Does a prompt time out? What scope can "allow always" create, and how does the user narrow it?
5. **Scope of first version**: which single use case (e.g. mail processing) proves the concept?

## Proposed Next Steps

1. Settle the open questions above, starting with the scope of the first version. Run a short spike first to check cel-java (including type-checking of custom event types) and the JVM footprint (see the Engine decision).
2. Write a short architecture document: components, interfaces (module API, storage interface, trigger/event model) and the trust model.
3. Build a thin vertical slice: engine core, one or two modules, a ruleset loader reading from git with one cascading layer, SQLite state, and a minimal CLI.
4. Prove it with one real use case (mail processing is the working example).
5. Add the JavaFX frontend as a client of the engine, then the credential store and permission model.
6. Extend to Windows and macOS.

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
