# Concepts

**Module.** Code that you install on purpose. It provides *capabilities* (what the software is able
to do, such as `mail.move`) and *event sources* (what it can notice, such as a new mail). A module
describes its capabilities in a `module.toml`: name, typed parameters, effect class, and whether
it supports a dry run. A module decides nothing by itself. See [Modules](../modules/overview.md).

**Event.** Everything that happens is an event: a mail arrives, a schedule fires, a person starts
a command. An event has a type (`mail.received`), a target (what it is about), a payload, and an
idempotency key, so handling the same event twice does nothing twice. Events are stored before
they are handled, so a crash loses none.

**Rule.** "When this event happens (and this condition holds), call these capabilities." A rule is
a TOML file with a stable id. All rules that match an event fire, in a fixed order. See
[Rules](rules.md).

**Ruleset and layer.** A ruleset is a stack of layers, bottom to top. A layer can add rules and
can replace, disable or patch the rules below it, so a private layer can adapt a public ruleset
without editing it. Layers that come from git are pinned to a commit in a lockfile and move only
when you say so. See [Layers](layers.md).

**Entity.** A piece of typed data that Praksis keeps: a contact, a note. A type says which fields it
has, which are required or unique, and which refer to other types. Entities are created, read,
changed and deleted by rules and by you with the capabilities `entity.*`, under the same grants
and audit log, and every change is an event. A **set** names some types, so that a grant or a
storage can name them once. See [Entities](entities.md).

**Storage.** Where the entities of a type are kept: the database of the daemon, or a JSON file, a
CSV file or a database table that a module provides. You choose in `praksis.toml`. See
[Where entities are kept](storage.md).

**Effect class.** What a call can change: `read-only`, `local-write`, `destructive` or
`external` (effects outside this machine). A rule declares the strongest class it uses.

**Grant.** What a layer's rules are allowed to call, possibly limited by argument (only to folders
under `Archive/`). The default is no. Grants are in `policy.toml` on your machine and are never
part of a ruleset. See [Permissions](permissions.md).

**Verifier.** Checks a ruleset before it runs: it is valid, every rule has a passing test, rules
ask only for what they call, grants name things that exist. See
[Verification and trials](verification.md).

**Shadow mode.** A new or changed rule first runs as an observer: it records what it would have
done and does nothing. You promote it when the trial looks right.

**Agent.** Praksis can maintain rulesets with an AI model, but a model has no rights of its own:
its output is a proposal or a call that goes through the same grants, audit log and approvals as
everything else. See [The AI features](ai.md).

**Offline mode.** One setting that stops everything that reaches another machine. See
[Offline mode](offline.md).
