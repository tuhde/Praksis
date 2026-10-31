# Permissions

The default is no. A rule can call a capability only if `policy.toml` in your home grants it for
the rule's layer. The file is never part of a ruleset, an update cannot change it, and only you
(or `praksis approvals`) write to it.

## Grants

    format_version = 1

    [[grant]]
    id = "archive-mail"
    layer = "mine"
    capability = "mail.move"
    note = "Archive only."
    [grant.args]
    folder = { prefix = "Archive/" }

A matcher is `{ equals = ... }`, `{ one_of = [...] }` or `{ prefix = "..." }` (strings only). Only
parameters that the module marks as limitable can have one; the module's page says which. An
argument without a matcher is not restricted. Destructive and external effects need an explicit
grant.

    praksis grants            # what is in effect
    praksis grants revoke <id>
    praksis grants reload     # after editing the file by hand

## When a rule needs more

If a rule calls something no grant covers, only that rule is disabled, the events it missed are
kept, and a request is opened.

    praksis approvals                          # the requests
    praksis approvals allow 3 --always --replay
    praksis approvals deny 3

`--once` lets the one denied event through. `--always` adds a grant for exactly the call that was
seen; you must say whether the missed events are handled now (`--replay`) or dropped
(`--discard`). Only the local command line answers requests. The AI cannot.

## Credentials

Rules name a credential (`imap:work`); `policy.toml` binds the name to a command that prints the
secret and to the capabilities that may use it:

    [credential."imap:work"]
    command = ["pass", "show", "mail/work"]

Nothing secret is stored, and the secret never appears in a rule, a message or the logs.

## The audit log

    praksis audit

shows every capability call with its arguments and whether it was allowed, and every change to
the grants, newest first. At each start the daemon also records a hash of `policy.toml`, so a
change made outside the daemon shows up.
