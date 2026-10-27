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
4. **Backend (engine)**: runs the rulesets, ideally on the same machine, ideally with a GUI. Apache NiFi is a candidate.
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

1. **Approval policy** (proposed, not confirmed): changes always need human approval when they increase permissions, add destructive or external actions, or touch rules written by a human. Safe changes that pass all checks could merge automatically.
2. **AI scope**: does the AI maintain public upstream rulesets, only the private layer, or both?
3. **Ruleset format**: declarative (YAML/JSON), a DSL, or a rules engine (e.g. Drools)? How expressive must conditions be?
4. **Rule execution**: when several rules react to the same event, do all fire, does the first win, or do they chain? In what order?
5. **Engine**: NiFi, a custom engine, or a hybrid?
6. **Module system**: Java modules/ServiceLoader, process-isolated plugins, or something language-neutral?
7. **Permission model**: how are capabilities and credentials granted to rulesets, and who approves them?
8. **Scope of first version**: which single use case (e.g. mail processing) proves the concept?

## Proposed Next Steps

1. Settle the open questions above, starting with rule execution, the ruleset format and the engine choice.
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
