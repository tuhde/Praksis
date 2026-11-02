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
grant. If an argument is a list, a grant covers it only if it covers every element.

## Entities

The type of an entity is a parameter that a grant can limit, so each layer is allowed the types it
needs and no more:

    [[grant]]
    id = "notes"
    layer = "mine"
    capability = "entity.create"
    [grant.args]
    type = { one_of = ["note", "memo"] }

A grant can also name an entity set (`type = { set = "docs" }`, only on the parameter `type` of the
`entity.*` capabilities). It covers the types that the set holds now, also those of the sets inside
it, and it follows the set when the set changes. A type that is added to the set is covered without
anyone deciding it, which is why a layer update or a verification that adds a type to a set that a
grant names counts as new access and is refused until you accept it.

The values of fields marked `sensitive` are not returned by `entity.read` or `entity.query`; a rule
needs the capability `entity.reveal` and a grant for it, which can name the fields
(`fields = { one_of = ["email"] }`). A rule that deletes an entity is also held to the grants for
everything that the delete takes along (the entities that cascade, the references that are
cleared). See [Entities](entities.md).

    praksis grants            # what is in effect
    praksis grants revoke <id>
    praksis grants reload     # after editing the file by hand

## Other daemons

A daemon that connects to this one (see `praksis peers`) can do nothing until you give it a grant. A
peer grant is like a grant for a layer, but it names the peer as it is listed in `peers.toml`:

```toml
[[peer_grant]]
id = "acme-read-invoices"
peer = "acme"
capability = "entity.read"
note = "the company may read the invoices it sent me"

[peer_grant.args]
type = { one_of = ["invoice"] }
```

This is the ceiling: what the peer's own policy lets its rules do is decided there, and the effective
permission is the narrower of the two. A peer grant must name a peer that is listed, or the daemon
does not start. Every call of a peer, allowed or denied, is in the audit log with the actor
`peer:<name>`.

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

Nothing secret is stored, and the secret never appears in a rule, a message or the logs. The
password of a database that holds entities is bound the same way, to the component
`entity-storage:<name>` (see [Where entities are kept](storage.md)).

## The audit log

    praksis audit

shows every capability call with its arguments and whether it was allowed, and every change to
the grants, newest first. At each start the daemon also records a hash of `policy.toml`, so a
change made outside the daemon shows up.
