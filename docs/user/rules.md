# Rules

A rule is one TOML file, `rules/<id>.toml` in a layer. The file name must be the rule id.

    id = "mail.archive-newsletters"
    description = "Move mailing-list mail to the newsletter folder."
    effect = "local-write"
    needs = ["mail.move"]

    [trigger]
    kind = "event"
    type = "mail.received"
    filter = "event.data.list_id != ''"

    [[action]]
    id = "move"
    capability = "mail.move"
    args = { message = { cel = "event.data.message" }, folder = { cel = "params.newsletter_folder" } }

## Parts

- **`effect`** is the strongest effect class of the rule's actions. It must be at least as strong
  as the strongest capability it calls.
- **`needs`** lists every capability the rule calls, so the verifier and your grants can be
  compared with it.
- **`[trigger]`** has a `kind`. `event` takes a `type` and an optional `filter`. Other kinds are
  `schedule` (`cron`, `timezone`, `catch_up`) and `command` (a name, a title and typed
  parameters, so a frontend can make a form; start one with `praksis run`).
- **`[[action]]`** is a call to a capability with an `id`, the `capability` and `args`, or
  `stop = true` to end the handling of the event. An argument is a literal or `{ cel = "..." }`.
- **`required = true`** makes a failure of the rule end the handling of the event.

## Expressions

Filters and computed arguments are written in CEL, a small language that cannot change anything
and always ends. A filter must give true or false. These names are available:

- `event`: `event.data` holds the typed fields of the event type, `event.extras` the rest;
- `results.<action id>`: the results of earlier actions of the same rule;
- `params`: the ruleset parameters;
- `now`.

Which fields an event has is in the module's page, for example [maildir](../modules/maildir.md).

## Order

All matching rules fire. They fire in id order, not by layer. Events of one target are handled one
at a time, in order; different targets in parallel. A failing action is recorded and the rule
stops there; the other rules go on.

## Parameters

`ruleset.toml` can declare `[param.<name>]` with a `type` and a `default`. A higher layer may
change the default, and a value in `praksis.toml` under `[param]` wins over both.

## Tests

Every rule needs a test: `tests/<rule id>.toml` in the same layer, with one or more `[[test]]`:

    format_version = 1

    [[test]]
    name = "moves a newsletter"
    [test.event]
    type = "mail.received"
    data = { message = "m1", folder = "INBOX", size = 10, list_id = "<news.example>" }
    [[test.call]]
    capability = "mail.move"
    args = { message = "m1", folder = "Newsletters" }

A test runs the rule with every capability replaced by a recorder. Only the arguments you list are
compared; no `[[test.call]]` means the rule must make no call. `[test.mock."capability"]` gives
the results a call returns. Run them with `praksis test`.
