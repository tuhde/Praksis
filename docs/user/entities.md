# Entities

An **entity** is a piece of data that Praksis keeps for you: a contact, a note, an order. Its
**type** says which fields it has. Rules, screens and you yourself create, read, change and delete
entities through the same checks as everything else: grants, the audit log, events.

## A type

A type is a file `entities/<id>.toml` in a layer (see [Layers](layers.md)):

    format_version = 1
    id = "contact"
    title = "Contact"
    description = "Someone I write to."

    [[field]]
    name = "name"
    type = "string"
    required = true

    [[field]]
    name = "email"
    type = "string"
    unique = true
    sensitive = true

    [[field]]
    name = "age"
    type = "int"

The id is the file name: lower case words joined by dots (`mail.contact`). A field has a name and a
type: `string`, `int`, `double`, `bool`, `timestamp`, `bytes`, `list` or `map`. Besides, a field can
be:

| Option | Meaning |
|---|---|
| `required = true` | A value must be given when the entity is made, and cannot be removed. |
| `unique = true` | No two entities of the type have the same value (not for `list`, `map`, `bytes`). |
| `sensitive = true` | Rules do not see the value unless they ask for it, see below. |
| `description` | What it is for. |
| `ref`, `on_delete` | The field refers to another type, see below. |

Every entity also has `id`, `created`, `updated`, `created_by` and `updated_by`, which Praksis sets;
a type cannot declare them. `created_by` and `updated_by` say who made the change: `rule:<id>` or
`user`.

A higher layer can replace a type of a lower one by naming its hash in `based_on`, as for a rule.
Because the entities that are stored must still fit, a replacement may add optional fields and
relax `required` or `unique`, but it may not remove a field, change its type, add a required field,
make a field required, unique or a reference, or make a sensitive field readable.

## Changing a type

Your own type file can be edited. Adding an optional field, or relaxing `required` or `unique`, needs
nothing else. A change that the stored entities do not fit (a field removed, renamed or of another
type, a new required field, a field that becomes required, unique or a reference) comes with
**migration steps**, which turn the stored entities into entities of the new version when the
daemon first starts with it:

    format_version = 1
    id = "contact"
    title = "Contact"
    migrates_from = "sha256:..."          # the version the entities were made under

    [[field]]
    name = "mobile"
    type = "string"
    # ... the other fields of the new version ...

    [[migration]]
    rename = { from = "phone", to = "mobile" }
    [[migration]]
    drop = "fax"
    [[migration]]
    convert = { field = "age", to = "int" }
    [[migration]]
    default = { field = "country", value = "DE" }
    [[migration]]
    check = "email"

| Step | Does |
|---|---|
| `rename` | The field gets another name. |
| `drop` | The field and its values go. |
| `convert` | The values become another type: text to `int`, `double`, `bool` or `timestamp`; `int` to `double` or `string`; `double` to `int` (whole numbers) or `string`; `bool` or `timestamp` to `string`. |
| `default` | An entity without a value gets this one (for a new required field). |
| `check` | Changes nothing. It says that the stored values are meant to fit a rule that the new version adds (unique, a reference), and fails the migration if they do not. |

The version is the hash that `praksis entities types` shows as `version`. A layer that replaces the
type of a layer below names that version in `based_on` and needs no `migrates_from`. The steps are
applied in order to every entity; then each is checked as a new entity is checked (types, required
and unique values, references). If one does not fit, nothing is changed, the daemon does not start
and says which entity; put the old type file back and it starts as before. No events are emitted.
The daemon notes in its database which version the entities have, so the steps run once. A change
without steps, or whose steps start from another version, leaves the data as it is.

`praksis verify` checks the steps against the type: a field that is not there, a name that is taken,
a conversion that does not exist, and whatever the steps still leave wrong. Migrations work for
entities in the daemon's own database. For a type kept in another storage the daemon refuses to
migrate (see [Where entities are kept](storage.md)).

## Export and import

    praksis entities export contact --out contacts.json
    praksis entities import contact --in contacts.json [--upsert] [--dry-run]

An export is a JSON file with every entity of a type: id, times, who made it and all values,
**also those of sensitive fields**, so the file is readable by you only. It is the format of the
`json-file` storage. An import writes the entities with their ids, times and authors and without
events; everything is checked first as for a new entity (types, required and unique values,
references, which may point into the file), an id that exists is an error unless `--upsert`, and
nothing is written if anything is wrong. Both are written to the audit log, without values.

## Working with entities yourself

    praksis entities types                  # the types, their fields, the sets that hold them
    praksis entities create contact --set name=Mei --set age=41
    praksis entities create contact --json '{"name": "Ole", "tags": ["a"]}'
    praksis entities list contact --filter '{"op":"prefix","field":"name","value":"Me"}' --sort name
    praksis entities show contact <id>
    praksis entities update contact <id> --set age=42 --unset email
    praksis entities delete contact <id>

`--set name=value` is read as the type of the field: a number, `true` or `false`, a time like
`2026-11-01T10:00:00Z`, or Base64 for bytes. Lists and maps need `--json`. You need no grant for
this, because the data is yours; every change is written to the audit log, and a change starts the
same events as a rule's change does.

A filter is `equals`, `one_of` (`values`), `prefix` and `range` (`gt`, `gte`, `lt`, `lte`), joined by
`and`, `or` (`of` is a list) and `not` (`of` is one filter). It can use the declared fields that are
not sensitive and `id`, `created`, `updated`, `created_by` and `updated_by`. An entity without a
value for a field does not match a condition on it. A list shows at most 1000 entities (100 by
default); `--offset` skips some.

## In rules

The module `entity` is part of the daemon: [entity.create, entity.read, entity.reveal, entity.query,
entity.update and entity.delete](../modules/entity.md). The type is a parameter that a grant can
limit, so you allow a layer to create `note` entities and nothing else.

    id = "mail.note"
    effect = "local-write"
    needs = ["entity.create"]

    [trigger]
    kind = "event"
    type = "mail.received"

    [[action]]
    id = "make"
    capability = "entity.create"
    args = { type = "note", values = { subject = "from a mail" } }

If a value comes from the event, the whole `values` is an expression:
`values = { cel = "{'subject': event.data.subject}" }`.

## Events

Every change is an event: `entity.created`, `entity.updated` (with the names of the changed
fields) and `entity.deleted`. They carry the type and the id and no values, so an event cannot be
used to read around a permission: a rule reads the entity with `entity.read`, which needs a grant.
An update that changes nothing writes nothing and emits nothing, so a rule that sets a value on
every update does not trigger itself. Rules that change entities and react to the change can loop;
a change that would be the ninth step of such a chain is refused.

    id = "note.log"
    effect = "local-write"
    needs = ["entity.create"]
    [trigger]
    kind = "event"
    type = "entity.created"
    filter = "event.data.type == 'note'"

## Sensitive fields

The value of a sensitive field is left out of what `entity.read`, `entity.query` and the results
of a write return, and it cannot be used in a filter or to sort (that would let a rule find a value
by trying). A rule that needs it calls `entity.reveal` with the field names, which has its own grant
that can name the fields. Neither the audit log nor the record of a rule in trial holds the value.
`praksis entities show <type> <id> --reveal email` shows it to you, and the audit log notes that you
looked.

## References between types

A `string` field with `ref = "author"` holds the id of an author; a `list` field with `ref` holds
several (many-to-many is a list on one side). The target must exist when the entity is made or the
reference is set. What happens when the target is deleted is `on_delete`:

| `on_delete` | Meaning |
|---|---|
| `restrict` (the default) | The delete is refused while something refers to it. |
| `cascade` | The entity with the field is deleted too, and what refers to that. Not for a list. |
| `set_null` | The field is cleared, or the id leaves the list. Not for a required single reference. |

    [[field]]
    name = "book"
    type = "string"
    ref = "book"
    on_delete = "cascade"
    required = true

A delete is planned in full before anything is done, and every deleted or changed entity is an
event. A rule is held to its grants: if deleting a book would also delete notes, the rule needs the
right to delete notes, or the whole delete is refused and nothing is done. You are not held to
grants.

## Sets of types

A **set** names some types, and other sets, so that a grant or a storage can name them once. A set
is a file `entity-sets/<id>.toml`:

    format_version = 1
    id = "people"
    title = "People"
    types = ["contact", "author"]
    sets = ["staff"]

A set gives no rights of its own and is not a boundary: it is a name. A type can be in many sets.
See [Permissions](permissions.md) for grants on a set, and
[Where entities are kept](storage.md) for storages. `praksis entities sets` lists them.

## Checks

`praksis verify` checks the types (valid files, references to types that exist, replacements that
fit), the sets (members exist, no cycles), and what rules write in the arguments of `entity.*`: a
type or a field that does not exist, a value of the wrong type, a required field left out, a filter
that is not valid. A daemon does not start with an invalid type. See
[Verification and trials](verification.md).
