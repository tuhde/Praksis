# Command line

<!-- Generated from the command definitions. Do not edit. -->

`praksis [--home DIR] <command>`. Exit codes: 0 done, 1 failed, 2 wrong usage, 3 the daemon is not running.

Options of every command:

- `--home` `<home>`: The Praksis home directory (default: $PRAKSIS_HOME, else ~/.praksis). It holds the configuration, the ruleset, the modules and the daemon's socket.

## Commands

### praksis start

Starts the daemon in the background.

Options:

- `--wait` `<waitSeconds>`: Seconds to wait for it to be ready.

### praksis stop

Asks the daemon to finish its work and exit.

### praksis status

Shows how the daemon is doing.

### praksis inject

Hands the daemon an event, as if a source had made it.

Arguments:

- `<type>`: Event type.

Options:

- `--target` `<target>`: What the event is about (default: cli).
- `--key` `<key>`: Idempotency key; an event with a known key is dropped.
- `--data` `<json>`: The payload as a JSON object. Timestamps are ISO-8601 strings, bytes Base64.

### praksis modules

Manages the modules.

#### praksis modules lock

Accepts the modules as they are now: records their hashes in modules.lock. The daemon loads only modules that match.

#### praksis modules install

Installs a module from its archive (a .praksis-module file) in the home directory and accepts it in modules.lock. A module is code that runs with the rights of the daemon: install only what you trust. A module of the same name that ships with the runtime image is hidden by it.

Arguments:

- `<archive>`: The module archive.

Options:

- `--replace`: Replace the module of the same name in the home.

#### praksis modules remove

Removes a module that was installed in the home.

Arguments:

- `<name>`: The name of the module.

#### praksis modules list

Lists the modules and where each one is.

### praksis agent

What the maintenance agent has staged: tests that it wrote for rules without one. Nothing is changed in a layer; 'export' copies the files into your own checkout, where you commit them.

#### praksis agent list

Lists the staged changes.

#### praksis agent show

Shows a staged change and runs the checks on the layer with it.

Arguments:

- `<id>`: The id.

#### praksis agent export

Writes the files of a staged change into a directory (your checkout).

Arguments:

- `<id>`: The id.
- `<directory>`: Where.

#### praksis agent drop

Throws a staged change away.

Arguments:

- `<id>`: The id.

#### praksis agent plans

Lists the plans that the agent made from a request.

#### praksis agent plan

Shows a plan, with each step checked against the grants as they are now.

Arguments:

- `<id>`: The id.

#### praksis agent plan-run

Approves a plan: its steps run one after the other under the grants of the plan layer. Run `praksis agent plan <id>` first and read the steps.

Arguments:

- `<id>`: The id.

#### praksis agent plan-drop

Throws a plan away.

Arguments:

- `<id>`: The id.

### praksis layers

Manages the ruleset layers that come from git.

#### praksis layers status

Shows the commit every layer is pinned to.

#### praksis layers update

Moves layers to the latest commit of their branch or tag, but only if the ruleset that results is valid and gives no rule access that no grant covers. Otherwise the pins stay as they are.

Arguments:

- `<layer>`: Layers to move (default: all).

Options:

- `--dry-run`: Only show what would change.
- `--accept-new-access`: Move the pins although rules get access that no grant covers (their calls are denied until granted).

### praksis peers

Manages the other daemons this one knows, by their public keys.

#### praksis peers list

Lists the peers with the fingerprint of their keys.

#### praksis peers show-key

Shows the public key of this daemon, to give to the owner of a peer.

#### praksis peers add

Adds a peer by name and public key.

Arguments:

- `<name>`: Its prefix.
- `<key>`: Its public key.

Options:

- `--socket` `<socket>`: Where to reach it.
- `--standing-read`: Allow read access to what it offers.

#### praksis peers remove

Removes a peer.

Arguments:

- `<name>`: The peer.

### praksis effective

Shows the ruleset that would run: every rule in the order it fires, which layer it comes from, what later layers did to it, and its hash for 'based_on'.

### praksis verify

Runs the checks on the ruleset that would run (or on a candidate, see the options), on its tests and on policy.toml: it must be valid, every rule needs a passing test, rules may only ask for what they call, and grants must name layers and capabilities that exist. Exit code 1 if there are errors.

Options:

- `--replay`: Also run the stored events through the ruleset in use and through the candidate, and list the events that they treat differently.
- `-n` `<events>`: How many of the newest stored events to replay (200).
- `--update`: Check the ruleset that `layers update` would produce (asks the servers).
- `--layer` `<name>=<directory>|<name>=@<ref>`: Check with this layer replaced by a directory, or by a branch or tag of its repository, e.g. an AI's branch of the private layer. Repeatable.

### praksis test

Runs the tests in the tests/ directory of every layer on the real engine, with the capabilities replaced by recorders. The tests of a layer run against the ruleset up to that layer. Exit code 1 if a test fails or cannot be read.

### praksis log

Shows the latest lines of the run log, oldest first.

Options:

- `-n, --lines` `<lines>`: How many lines.

### praksis audit

Shows the latest lines of the audit log: every capability call with its arguments and whether it was allowed, and the grants found at start. Newest first.

Options:

- `-n, --lines` `<lines>`: How many lines.

### praksis approvals

Lists the requests for permissions, with the disabled rules and the events they missed. A rule whose call no grant covers is disabled until a request is answered.

#### praksis approvals allow

Allows what a rule asked for. --once lets the one denied event through. --always adds a grant to policy.toml for exactly the call that was seen and needs --replay or --discard for the events the rule missed meanwhile.

Arguments:

- `<request>`: The number.

Options:

- `--once`: Let the one denied event through.
- `--always`: Add a grant for what was seen.
- `--replay`: Handle the missed events now, in order.
- `--discard`: Drop the missed events.

#### praksis approvals deny

Refuses a request. The rule stays disabled, also after a restart.

Arguments:

- `<request>`: The number.

Options:

- `--discard`: Drop the events the rule missed.

#### praksis approvals replay

Handles the events a rule missed, in order, for a rule that runs again.

Arguments:

- `<rule>`: The rule id.

#### praksis approvals discard

Drops the events a rule missed.

Arguments:

- `<rule>`: The rule id.

### praksis grants

Lists the grants and credential bindings in policy.toml that are in effect.

#### praksis grants revoke

Removes a grant from policy.toml.

Arguments:

- `<grant>`: The grant id.

#### praksis grants reload

Reads policy.toml again after it was edited by hand and checks it.

### praksis shadow

Shows the rules on trial: new and changed rules run in shadow mode first, which means they are observed and do nothing. For each one: how many events it was seen with, what it would have called and which rules really handled the same events.

#### praksis shadow promote

Ends the trial of a rule: its new version runs live from now on.

Arguments:

- `<rule>`: The rule id.

#### praksis shadow reset

Forgets what a rule on trial was seen to do and starts the trial again.

Arguments:

- `<rule>`: The rule id.

### praksis proposals

Changes that an AI proposes as branches of a layer with ai_maintained = true. Without a subcommand it lists the branches that are not part of the layer yet. Praksis never merges or pushes: approving pins the layer to the commit you looked at.

#### praksis proposals show

Shows who changed what and why in a branch, runs the checks, the tests and the replay on it, and says what it needs: your approval, shadow mode, or nothing more.

Arguments:

- `<layer>@<branch>`: The proposal.

#### praksis proposals approve

Pins the layer to the commit of the branch, if the checks find no errors. You name the commit you looked at, so a branch that moved since is not approved. Restart the daemon afterwards; with shadow mode on, new and changed rules are observed first.

Arguments:

- `<layer>@<branch>`: The proposal.
- `<commit>`: The commit you looked at (7 characters at least).

Options:

- `--without-shadow`: Approve although shadow mode is off, so new rules run at once.

#### praksis proposals reject

Records that you do not want this branch. It is not listed again unless it changes.

Arguments:

- `<layer>@<branch>`: The proposal.
- `<reason>`: Why, for the audit log.

### praksis commands

Lists the commands that a person can start (rules with a command trigger), with their arguments. Start one with: `praksis run <command> name=value ...`

### praksis run

Starts a command and waits for its result. Arguments are name=value; the types come from the command. Lists and maps are given through the API, not here.

Arguments:

- `<command>`: The command id.
- `<name=value>`: Arguments.

Options:

- `--no-wait`: Do not wait for the result.

### praksis views

Lists the views of the ruleset in use: screens made of data and components.

### praksis view

Shows a view as text, with its data: the daemon runs its sources and evaluates it. The state of the view is set with name=value.

Arguments:

- `<view>`: The view id.
- `<name=value>`: State.

### praksis entities

Works with entities, the data that Praksis keeps for rules and screens, as a person: no grant is needed, every change is written to the audit log and starts the same events as a rule`s change. Values of sensitive fields are shown only with `show --reveal'.

#### praksis entities types

Lists the entity types and their fields.

#### praksis entities sets

Lists the entity sets: named groups of entity types (and of other sets) that a grant or a storage can name once. A set gives no rights of its own.

#### praksis entities list

Lists the entities of a type.

Arguments:

- `<type>`: The entity type.

Options:

- `--filter` `<object>`: A condition as JSON, e.g. `{"op": "prefix", "field": "name", "value": "Mei"}`. Operations: equals, one_of, prefix, range (gt, gte, lt, lte), and, or, not.
- `--sort` `<sort>`: The field to sort by (default: created).
- `--reverse`: Sort from high to low.
- `--limit` `<limit>`: How many to show (default 100, at most 1000).
- `--offset` `<offset>`: How many to skip.

#### praksis entities show

Shows one entity. Sensitive fields are left out unless named with --reveal, which is written to the audit log.

Arguments:

- `<type>`: The entity type.
- `<id>`: The entity.

Options:

- `--reveal` `<field>`: Sensitive fields whose values to show.

#### praksis entities create

Creates an entity and prints it.

Arguments:

- `<type>`: The entity type.

Options:

- `--json` `<object>`: The fields as a JSON object, e.g. `{"age": 41, "tags": ["a"]}`.
- `--set` `<name=value>`: Sets a field. The text is read as the type of the field: a number, true or false, a time like 2026-11-01T10:00:00Z, or Base64 for bytes. Can be repeated.

#### praksis entities update

Changes fields of an entity and prints it. A change that changes no value writes nothing.

Arguments:

- `<type>`: The entity type.
- `<id>`: The entity.

Options:

- `--json` `<object>`: The fields as a JSON object, e.g. `{"age": 41, "tags": ["a"]}`.
- `--set` `<name=value>`: Sets a field. The text is read as the type of the field: a number, true or false, a time like 2026-11-01T10:00:00Z, or Base64 for bytes. Can be repeated.
- `--unset` `<field>`: Removes a field (not a required one). Can be repeated.

#### praksis entities delete

Deletes an entity.

Arguments:

- `<type>`: The entity type.
- `<id>`: The entity.

#### praksis entities export

Writes all entities of a type to a file (or to the output) as JSON, with their ids, times and who made them, and with the values of sensitive fields too: the file is as secret as they are, so it is readable by you only. The file can be imported into another storage, or used as the file of the json-file storage.

Arguments:

- `<type>`: The entity type.

Options:

- `--out` `<file>`: The file (default: the output).

#### praksis entities import

Writes entities from an export file, with their ids, times and who made them, and without events. Everything is checked first; an entity that exists is an error unless --upsert. The type must be defined, and a type that others refer to must be imported after the types it refers to.

Arguments:

- `<type>`: The entity type.

Options:

- `--in` `<file>`: The file.
- `--upsert`: Replace an entity that exists.
- `--dry-run`: Only check.

### praksis help

Shows the documentation. Without a topic it lists the topics; a topic is the path of a page without .md, for example user/concepts.

Arguments:

- `<topic>`: The page to show.
