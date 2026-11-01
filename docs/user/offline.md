# Offline mode

    # praksis.toml
    offline = true

With this setting nothing leaves the machine through a module. Every module lists in its
`module.toml` which of its capabilities and event sources reach other machines (`network`). When
the program is offline:

- those capabilities refuse, with a message that names the setting, and a rule stops cleanly;
- those event sources do not start;
- everything else in the module works, so composing a mail and saving a draft locally still do;
- rules, views and grants are checked as before, so a ruleset behaves the same on both sides of
  the switch.

An entity storage that is a database on another machine is refused at the start; a database in a
file or in memory is not (see [Where entities are kept](storage.md)).

A module that cannot know in advance treats the setting as its own: the `ai` module refuses
profiles with `data = "cloud"` and keeps the local ones.

**Not covered:** `praksis layers update` fetches git layers when you run it, and offline mode does
not stop that. The list of network capabilities is the module author's word.
