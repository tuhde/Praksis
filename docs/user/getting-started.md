# Getting started

Praksis is a daemon that handles events (a mail arrives, a schedule fires, a person starts a
command) by running rules. The `praksis` command talks to it. This page takes you from an empty
home directory to a rule that handled an event.

## The home directory

Everything local lives in one directory: `$PRAKSIS_HOME`, else `~/.praksis`, or the one given
with `--home DIR`.

| File or directory | What it is |
|---|---|
| `praksis.toml` | The configuration: which layers and modules to use, settings of modules, parameters. |
| `policy.toml` | The grants: what each layer's rules may do. Only you change it. See [Permissions](permissions.md). |
| `modules/` | Installed modules, and `modules.lock` with their hashes. |
| `rulesets.lock` | The commit each git layer is pinned to. |
| `praksis.db` | The event queue, the run log, the audit log. |
| `praksis.sock` | The socket of the running daemon. |

## A first configuration

A ruleset is a directory with a `ruleset.toml` and one file per rule under `rules/`. For a first
try, the simplest set-up is a directory layer and the Maildir module:

    # ~/.praksis/praksis.toml
    format_version = 1
    enabled_modules = ["maildir"]

    [module.maildir]
    root = "/home/me/Maildir"

    [[layer]]
    name = "mine"
    path = "/home/me/praksis-rules"

A layer can instead come from a git repository (`git = "https://..."` and `ref = "main"`); see
[Layers](layers.md). Modules are code that runs with the rights of the daemon, so a module is only
loaded if it is in `modules.lock`: run `praksis modules lock` after you put one there, or install
one with `praksis modules install <archive>`.

## The files of the rules directory

    praksis-rules/
      ruleset.toml
      rules/mail.archive-newsletters.toml   # the rule from the page "Rules"
      tests/mail.archive-newsletters.toml   # its test, see "Rules"

`ruleset.toml`:

    format_version = 1
    name = "mine"

    [param.newsletter_folder]
    type = "string"
    default = "Newsletters"

and in `~/.praksis/policy.toml` the grant that lets the layer `mine` move mail into that folder:

    format_version = 1

    [[grant]]
    id = "archive-mail"
    layer = "mine"
    capability = "mail.move"
    [grant.args]
    folder = { prefix = "Newsletters" }

Without the grant the rule is disabled at its first call and a request is opened.

## Check, start, watch

    praksis verify        # the ruleset, its tests and policy.toml must be in order
    praksis start         # the daemon runs in the background
    praksis status
    praksis log           # what happened per event, rule and action
    praksis stop

Drop a mail with a `List-Id` header into `new/` of the Maildir and the log shows the move.

If a rule needs something that no grant covers, the daemon does not stop: it disables that rule
and opens a request. `praksis approvals` lists it; see [Permissions](permissions.md).

You can hand the daemon an event yourself, as if a source had made it:

    praksis inject mail.received --data '{"message": "m1", "folder": "INBOX", "size": 100}'

## Where to go next

- [Concepts](concepts.md) for the vocabulary.
- [Rules](rules.md) to write one.
- [Command line](../reference/cli.md) for every command.
