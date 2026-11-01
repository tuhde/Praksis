# Writing an entity storage

A module can provide a place to keep entities. Praksis does the thinking (it checks types,
required and unique values and references, sets the id, the times and who made a change, writes to
one storage at a time and handles events and permissions); a storage only keeps and finds.

## The interface

Implement `it.uhde.praksis.capability.EntityStorage` from the capability API:

| Method | Does |
|---|---|
| `name()` | The name in `backend = "..."` of `praksis.toml`. |
| `configure(settings)` | Reads the settings of the storage; throw `CapabilityException` for a wrong one. |
| `useCredentials`, `useDataDirectory`, `useOffline` | Told once, before `open`. |
| `open(types)` | Makes what is needed (tables, files) and checks what is there. |
| `get`, `insert`, `replace`, `delete` | One entity, by type and id. |
| `query(type, query)` | The entities that match a `Filter`, in order, as a page with the total. |
| `findUnique(type, field, value)` | The id that has a unique value (the default asks `query`). |
| `atomically(work)` | Runs the work so that its writes happen together, if you can. |

The values of a `StoredEntity` have the Java types of the value types: `String`, `Long`, `Double`,
`Boolean`, `Instant` (milliseconds), `byte[]`, `List`, `Map`; a field without a value is not in the
map. Inside a list or a map a number may come back as another number type. `FilterMatcher` in the
API evaluates filters and sorts in memory, for a storage that cannot do it itself. Sorting puts
entities without a value first when going up and last when going down, then by id.

## The module

List the storage in `module.toml` and provide it as a service:

    backends = ["my-storage"]

    // module-info.java
    provides it.uhde.praksis.capability.EntityStorage with my.module.MyStorage;

A storage that always reaches other machines also lists its name in `network`, and is then not used
in offline mode. A storage that depends on its settings (a JDBC database may be a file) leaves it out
and refuses in `open` when `useOffline` was called.

## The tests

The module `praksis-storage-testkit` has the class `EntityStorageContract`: twelve tests that every
storage must pass (every kind of value, replace and delete, types apart, unique lookup, every filter
operation, sorting and paging, many entities, closing and opening again, texts that look like
other things). Extend it and say how to open your storage:

    class MyStorageTest extends EntityStorageContract {
      @Override
      protected EntityStorage open(Path dir, List<StorageType> types) throws Exception {
        MyStorage s = new MyStorage();
        s.configure(Map.of());
        s.open(types);
        return s;
      }
    }

The repository [Praksis-Modules-Storage](https://github.com/tuhde/Praksis-Modules-Storage) has
three examples, and `FileStorage` there is a base class for storages that keep one file per type.
