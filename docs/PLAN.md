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

## Open Questions

1. **Cascading semantics**: override (later layers win), or inheritance/composition (one ruleset extends or includes others)? Can a layer remove an upstream rule?
2. **Ruleset format**: declarative (YAML/JSON), a DSL, or a rules engine (e.g. Drools)? How expressive must conditions be?
3. **Engine**: NiFi, a custom engine, or a hybrid?
4. **Module system**: Java modules/ServiceLoader, process-isolated plugins, or something language-neutral?
5. **Permission model**: how are capabilities and credentials granted to rulesets, and who approves them?
6. **Updates**: how does a private layer follow upstream changes without conflicts?
7. **Scope of first version**: which single use case (e.g. mail processing) proves the concept?

## Proposed Next Steps

1. Settle the open questions above, starting with cascading semantics, the ruleset format and the engine choice.
2. Write a short architecture document: components, interfaces (module API, storage interface, trigger/event model) and the trust model.
3. Build a thin vertical slice: engine core, one or two modules, a ruleset loader reading from git with one cascading layer, SQLite state, and a minimal CLI.
4. Prove it with one real use case (mail processing is the example Till gave).
5. Add the JavaFX frontend as a client of the engine, then the credential store and permission model.
6. Extend to Windows and macOS.

## Name Suggestions

The concept: a general core, behaviour defined by rules, built from layered modules. The working directory name "SoftwareNG" hints at "next generation".

| Name | Idea |
|---|---|
| **Praxis** | Greek for "putting theory into action": rules become actions |
| **Loom** | Weaves modules and layered rulesets into what you actually need |
| **Canon** | The authoritative body of rules, and also a nod to "cascading" layers |
| **Tessera** | A single tile of a mosaic: many small modules forming one picture |
| **Concord** | Parts working together under shared agreements (rules) |
| **Keystone** | The central piece that makes the whole structure work |
| **Weft** | The crosswise threads that give the weave its pattern: rulesets over a fixed base of modules |

Recommendation: **Praxis** or **Loom**. Both are short and easy to say, and their meaning matches the concept. Trademark, domain and existing-project conflicts are not checked yet and must be checked before choosing.

## Verification

Nothing is implemented yet, so there is nothing to test. The checks for this planning step are:
- Till confirms the concept summary matches their intent and corrects it where it does not.
- The open questions are answered or deliberately deferred.
- A name is chosen after a conflict check.
