# Other daemons

A Praksis daemon can use what another daemon offers: a company's, or the one of another role of
yours (private, teacher, ...). Each daemon keeps its own home, data, grants and audit log. A
connection gives access to nothing by itself; every use is allowed twice, by the daemon that is
asked and by the daemon that asks, and the narrower of the two wins.

What another daemon offers appears under its name: a type `invoice` of the daemon `acme` is
`acme/invoice` here, a function `entity.read` is `acme/entity.read`. What is local has no prefix.
Nothing of the other daemon is installed here: no code of it ever runs on this machine, and its
rules stay its own.

## Who is who

Each daemon has a key pair; the key is its identity, and the name and the socket path are only labels
you choose. The private key is made on first use in `identity/key` of the home and never leaves it.
Both sides need the key of the other:

    praksis peers show-key                       # on each side; read the fingerprint out loud
    praksis peers add acme <key> --socket /run/praksis/acme.sock

The peers are listed in `peers.toml` in the home. When two daemons connect, each proves that it holds
the key the other has listed; a daemon with an unknown key, or a wrong proof, only gets "not
accepted". The two also agree on the version of the protocol and of the catalog, or refuse each
other. For now daemons reach each other through the Unix socket on one machine.

## Several identities on one machine

If you run one daemon per role of yours (private, work, ...), each is an identity with its own home
`~/.praksis-<name>`, store, grants, credentials and audit log, and the identities are peers of each
other. `praksis identity create work` makes a home, `praksis --identity work start` runs it (or set
`$PRAKSIS_IDENTITY`), `praksis identity list` shows which run. `praksis identity link private work`
introduces two identities to each other: each lists the key and socket of the other. That allows
nothing; what one may do at the other is a peer grant on each side, as below.

On Linux, systemd can start the identities for you. The runtime image contains the unit
`lib/praksis/service/praksis@.service`; copy it to `~/.config/systemd/user/` (it expects the image in
`~/.local/share/praksis`, change the paths if it is elsewhere), then `systemctl --user enable --now
praksis@private` starts the daemon of the identity `private` and again at every login. Each identity
needs about 150 MB when idle. They can start in any order: a peer that is not up yet is offline until
it is, and is then connected by itself.

An account belongs to exactly one identity: two daemons on the same mailbox would both act on it.
`praksis identity check` warns when two identities use the same user, address or directory in their
module settings. Credentials are never shared, and neither are settings; only layers (for example
`Praksis-Mail`) and one directory of installed modules (`shared_modules` in `praksis.toml`) can be
used by all. A company you are connected to as one identity is not visible to another identity unless
the first offers it on as a function of its own.

## What the other side lets you do

The daemon you connect to decides first, with `[[peer_grant]]` in its `policy.toml`, naming you as
the peer it has in its list:

```toml
[[peer_grant]]
id = "me-read-invoices"
peer = "me"
capability = "entity.read"
note = "my private daemon may read the invoices"

[peer_grant.args]
type = { one_of = ["invoice"] }
```

Without a grant a peer can do nothing, and a grant can name only a function of this daemon itself: a daemon is not a relay, so what you have from `acme` is never offered on to your own peers. This is the ceiling: your own policy can only narrow it. A
peer grant must name a listed peer, or the daemon does not start. Every call of a peer, allowed or
denied, is in the audit log with the actor `peer:<name>`. A capability beyond the grants and one that
does not exist get the same answer, so a peer cannot find out what exists beyond what it may use.

## What you see

`praksis peers catalog acme` asks what the peer offers: the capabilities, entity types and entity sets
that its grants for you cover, and nothing else. The answer is kept in the home. When the peer cannot
be reached, the kept catalog is shown, marked stale. Text from a peer is cleaned and shown as text;
it is never given to a model as an instruction.

A running daemon follows the peer's catalog by itself. If the peer stops offering something, it
stays in the list marked unavailable, with the time, until it comes back. A set keeps the member,
marked, so that a rule that depends on it fails when you verify it and not silently. A peer that
cannot be reached marks nothing as gone, because nothing is known.

## What you allow

A function that a peer offers anew can be seen but not used until you accept it.
`praksis peers requests` lists what waits; `praksis peers answer <number> approve` or `refuse`
answers. Accepting grants nothing by itself: your own grants still decide who may use the function.
A refused function is not asked again unless it changes, and one peer can have 20 requests waiting.

For a peer you trust to read, set `standing_read = true` in `peers.toml`: the functions that only
read are then accepted as they appear. Whatever writes, sends or deletes always asks.

### Sets of a peer

A grant of yours can name a set of a peer, but only a **pinned** one. `praksis peers pin acme finance`
records the members the set has now, and a grant with `type = { set = "acme/finance" }` covers exactly
those. If the company adds a type to its set, the pin does not move and your grant does not widen:
`praksis peers pins` shows the difference, a request is opened, and `praksis peers accept-pin acme
finance` (or approving the request) is your approval. A grant that names a set that is not pinned
stops the daemon from starting. Nothing about your own sets or pins is sent to the peer.

### Calls by a person

`praksis peers call acme entity.read --arg type=invoice` calls a function as yourself, after
`praksis peers catalog acme` once. The peer's grants decide; you need no grant of your own.

## In rules

A rule calls a function of a peer under its name, and a grant in `policy.toml` names it the same way:

```toml
[[grant]]
id = "mine-read-acme-invoices"
layer = "mine"
capability = "acme/entity.read"

[grant.args]
type = { set = "acme/finance" }
```

The call runs at the peer, under the peer's grants. If the peer cannot be reached, the rule does not
fail and is not disabled: the event is skipped for that rule and kept as missed, and
`praksis approvals replay <rule>` handles it once the peer is back. If the peer stops offering the
function, `praksis verify` reports every rule that calls it as an error. The functions that rules can name are
those in the kept catalogs when the daemon starts; a function that a peer offers later can be used in
rules after the next start.

Reading data of a peer into a local entity or storage is not possible yet. A remote value can be used
in the rule that fetched it, and nothing is copied out of the control of its owner.

## The desktop program

With several identities the program reads their names from `identities.toml` in
`~/.config/praksis-desktop`:

    format_version = 1

    [[identity]]
    name = "private"

    [[identity]]
    name = "work"
    colour = "#1565c0"
    home = "~/.praksis-work"      # optional; this is the default for the name

It shows a list to pick from (`praksis-desktop --identity work` opens one at once) and opens one
window per identity, each connected to its own daemon. A window has a strip in the colour of the
identity with its name on top, and every confirmation and notification names the identity, so that
nothing is approved or sent as the wrong one.

The **Peers** tab shows each peer with its key and whether it can be reached, what it offers under its
name with anything unusable marked and the reason, the pins (pin a set, accept a change, remove a pin)
and the requests of the peers to approve or refuse.
