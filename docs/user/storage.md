# Where entities are kept

Unless you say otherwise, entities are kept in the SQLite database of the daemon. You can keep the
entities of a type, or of a [set](entities.md) of types, somewhere else: a readable JSON file, a
CSV file that a spreadsheet opens, or a table of a database. Where data lives is your decision, so
it is in `praksis.toml` and never in a ruleset.

    [entities.storage.contacts]        # a storage, named by you
    backend = "json-file"              # what a module provides
    directory = "/home/me/contacts"    # settings of that backend

    [entities.bind]                    # type = storage
    contact = "contacts"

    [entities.bind_set]                # set = storage: every type in the set
    people = "contacts"

The module that provides a storage counts as in use without a `[module.*]` section (unless
`enabled_modules` is set; then list it). A missing backend, an unknown type or set in a binding, or
a storage that cannot be opened stops the start and says which section.

A type that is bound itself is kept where it is bound. Otherwise the bound sets that hold it decide,
the smallest first; two equally small sets that are bound to different storages stop the start, and
then you bind the type itself.

The storages come from the repository
[Praksis-Modules-Storage](https://github.com/tuhde/Praksis-Modules-Storage). Build it and install
the archives you want:

    praksis modules install praksis-module-json-file/target/json-file.praksis-module

## json-file

One file `<type>.json` per type. Times are ISO-8601 text and bytes are Base64.

| Setting | Meaning |
|---|---|
| `directory` | Where the files are. Default: the module's directory in the home (`data/storage/<name>`); a relative path is below that. |
| `pretty` | Indented text. Default `true`. |

## csv-file

One file `<type>.csv` per type, with the columns `id`, `created`, `updated`, `created_by`,
`updated_by` and then the fields. A missing value is an empty cell and an empty text is `""`. A
list or a map is JSON in the cell. The columns may be in any order when the file is read.

A spreadsheet runs a cell that starts with `=`, `+`, `-`, `@`, a tab or a carriage return as a
formula, and entity texts can come from mail. Such a text is written with a `'` in front and read
back without it.

| Setting | Meaning |
|---|---|
| `directory` | As above. |
| `delimiter` | One character. Default `,`; `;` suits a German spreadsheet. |
| `escape_formulas` | The protection above. Default `true`. |

## The files

Both file storages write a whole file to a temporary file that then replaces the old one, so a
crash leaves the old or the new file, never half of one. The files are readable by you only. A file
that you change by hand, or another program changes, is read again before the next operation, so a
file is also a way to import and to export; Praksis is the only writer that is safe to run at the
same time. A file that is not right stops the start with the file and the entity or row named.

## jdbc

One table per type in a database that a JDBC driver reaches. The table is `praksis_<type>` with a
column per field (times as milliseconds since 1970); unique fields have a unique index; the
database does the filtering and sorting, so other programs can read and write the table. A row that
another program inserts is an entity.

    [entities.storage.shop]
    backend = "jdbc"
    url = "jdbc:postgresql://db.example.org/shop"
    user = "praksis"
    password_credential = "shop-db"

| Setting | Meaning |
|---|---|
| `url` | The JDBC url. Required. |
| `user` | The user name. |
| `password_credential` | The name of a credential binding that prints the password. |
| `driver` | The class of the driver, if several are there. |
| `dialect` | `sqlite`, `h2` or `postgresql`, if the driver reports another product name. |
| `table_prefix` | Default `praksis_`. |

The module ships no driver. The daemon has the driver of SQLite (`jdbc:sqlite:/path/file`); for any
other database add the jar of its driver to the module archive before you install it. The password
comes from a credential binding in `policy.toml` that allows the component `entity-storage:<name>`:

    [credential."shop-db"]
    command = ["secret-tool", "lookup", "service", "shop-db"]
    capabilities = ["entity-storage:shop"]

SQLite and H2 are tested. A PostgreSQL dialect exists but has not been run against a live server by
its author. MariaDB and MySQL are not supported yet.

## What a storage changes

Nothing about the rules: types, required and unique values, references, events, grants and the
audit log work the same everywhere. Two things differ:

- With the daemon's database a change and its event are written together. With another storage the
  change is written first and the event after it, so a crash in between loses the event but never
  the change.
- A storage that is on another machine cannot be used in [offline mode](offline.md). A file or an
  in-memory database can.

## Moving a type to another storage

    praksis entities export contact --out contacts.json    # while it is where it is
    # change [entities.bind] (or bind_set) in praksis.toml, restart the daemon
    praksis entities import contact --in contacts.json

The ids, times and authors are kept, so references to the entities stay valid. Delete the old data
yourself when you have checked the new place. A type that others refer to must be imported after the
types it refers to.

## Changing a type that is kept elsewhere

Migration steps for a changed type (see [Entities](entities.md)) work for the daemon's own database
only. For a type in a file or a table: export the entities before you change the type, edit the
export (or the file or the table) to the new shape, change the type, and import.
