# Verification and trials

Rulesets may be written by a model, so Praksis checks them before they run.

## praksis verify

    praksis verify

Checks the ruleset that would run, its tests and `policy.toml`: it is valid, every rule has a
passing test that makes it fire, rules ask only for what they call, and grants name layers and
capabilities that exist. It exits with 1 on errors.

- `--replay` also runs the stored events through the ruleset in use and through the candidate
  and lists the events they treat differently (`-n` sets how many).
- `--update` checks what `layers update` would produce.
- `--layer name=DIR` or `--layer name=@branch` checks with one layer replaced, for example an AI's
  branch of your private layer.

`praksis test` runs only the tests.

## Shadow mode

With `[shadow] enabled = true` in `praksis.toml` (and `trial_days`, default 7, and `min_events`,
default 20), a new or changed rule runs as an observer first. It is evaluated for the events that
reach it, but its capabilities are not called and its `stop` does not end the event. If an older
version exists, that one keeps running.

    praksis shadow                  # per rule: events seen, what it would call, what would be refused
    praksis shadow promote <rule>   # it runs live from now on
    praksis shadow reset <rule>     # start the trial again

The daemon never promotes by itself.

## Proposals

A layer with `ai_maintained = true` takes changes as branches of its repository.

    praksis proposals                              # branches that are not part of the layer
    praksis proposals show mine@fix-newsletters    # who changed what and why, with checks and replay
    praksis proposals approve mine@fix-newsletters <commit>
    praksis proposals reject mine@fix-newsletters "reason"

Praksis never merges or pushes. Approving pins the layer to the commit you name, so a branch that
moved since you looked is not approved. Changes that widen grants, add destructive or external
rules, or edit rules you wrote need your approval; the rest runs in shadow mode first.
`praksis effective` shows for each rule who changed it last and whether the commit looks like AI
or human work; that is the author's claim, not proof.
