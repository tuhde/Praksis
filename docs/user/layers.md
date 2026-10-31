# Layers

A ruleset is a stack of layers, listed bottom to top in `praksis.toml`:

    [[layer]]
    name = "mail"
    git = "https://github.com/tuhde/Praksis-Mail"
    ref = "main"

    [[layer]]
    name = "mine"
    path = "/home/me/praksis-rules"

A layer with `git` and `ref` follows a branch or tag and is pinned to a commit in `rulesets.lock`.
A layer with `path` is read as it is and never pinned. Praksis fetches git layers with your
installed `git`, so your SSH keys and credential helpers work; nothing is checked out and no hook
runs. Without any `[[layer]]`, the directory `ruleset` in the home is used.

## What a layer can do

A layer is a directory with `ruleset.toml` and any of these directories, one file per operation:

| Directory | Meaning |
|---|---|
| `rules/<id>.toml` | Add a rule whose id does not exist below. |
| `replace/<id>.toml` | A complete new rule, with `based_on`. |
| `patch/<id>.toml` | Change single fields of a rule: `[[change]]` with `path`, `expect` and `value` (or `remove = true`). |
| `disable/<id>.toml` | Switch a rule off, with `based_on` and an optional `reason`. |

`based_on` is the hash (`sha256:...`) of the rule below, as `praksis effective` shows it. If the
rule below has changed, the operation is an error and nothing is silently skipped. A patch checks
field by field: an upstream change to another field does not matter, a change to the patched
field is reported. Permissions are never part of a layer.

## Looking at the result

    praksis effective     # each rule in firing order, its layer, what later layers did, its hash
    praksis layers status # the commit every layer is pinned to
    praksis layers update # move to the newest commit, if the result is valid

`layers update` refuses a result that is invalid or that gives a rule access no grant covers, and
shows the difference. `--dry-run` only shows what would change; `--accept-new-access` moves the
pins anyway, and the calls stay denied until you grant them.
