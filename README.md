# Praksis

Praksis (provisional name) is an idea for a general local software system that replaces the "one program per function" model.

Modules provide **capabilities** (what the software is able to do). Git-hosted, cascadable **rulesets** define the **policy** (what it is willing to do and has to do). A public ruleset can be adapted in a private layer without editing the original, for example "use the public mail processing ruleset, plus one extra rule of mine". Actions are started by the user, by a scheduler, or by external events.

The project is in the idea-gathering phase and nothing is implemented yet. See [docs/PLAN.md](docs/PLAN.md) for the concept, open questions and proposed next steps.

## License

Praksis is open source under the [Apache License 2.0](LICENSE). Source files carry an `SPDX-License-Identifier: Apache-2.0` header.
