# Letting an agent use Praksis (MCP)

An AI agent such as Claude Code or Claude Desktop can look at your Praksis, explain what it did and
propose changes, through an [MCP](https://modelcontextprotocol.io) server. The server is
[Praksis-MCP](https://github.com/tuhde/Praksis-MCP). **The agent has no rights of its own.** The
server is a client of the daemon's web door (see [Using Praksis in a browser](web.md)) and can do
only what the token you give it may do. Approving, granting, running a plan and updating a layer
stay with you, and no tool offers them.

## Setting it up

1. Turn on the web door in `praksis.toml` and restart the daemon (see the page on the browser).
2. Make a token for the agent. A read-only token lets it look; a token that is not read-only also
   lets it hand you proposals:

        praksis web token create claude --read-only
        praksis web token create claude-proposals

   The token is shown once.
3. Build the server. The core must be installed first (`./mvnw install` in the core's checkout):

        git clone https://github.com/tuhde/Praksis-MCP
        cd Praksis-MCP && ./mvnw package

4. Add it to your client. For Claude Code:

        claude mcp add praksis --env PRAKSIS_TOKEN=pwt_... -- java -jar /path/to/Praksis-MCP/target/praksis-mcp.jar

   For Claude Desktop, put this in its configuration file:

        { "mcpServers": { "praksis": {
            "command": "java",
            "args": ["-jar", "/path/to/Praksis-MCP/target/praksis-mcp.jar"],
            "env": { "PRAKSIS_TOKEN": "pwt_..." } } } }

The server is configured through the environment, so that the token is not on a command line that
other users can list.

| Variable | Meaning |
|---|---|
| `PRAKSIS_TOKEN` | the web token (required) |
| `PRAKSIS_URL` | the address of the web door, default `http://127.0.0.1:8484` |
| `PRAKSIS_MODE` | `read-only` (default), `propose` or `commands` |

Use one server for each daemon, so one for each identity. Revoke a token with
`praksis web token revoke NAME`; the agent is logged out at once.

## What the mode decides

The mode decides which tools the agent sees. The daemon enforces the token whatever the mode is: a
server in `propose` mode with a read-only token shows the proposal tools, and the daemon refuses
them.

| Mode | The agent sees |
|---|---|
| `read-only` | the tools that look |
| `propose` | those, and the tools that hand you a proposal |
| `commands` | those, and tools that start the commands your rules offer to a person |

## What the agent can look at

The tools that look are `praksis_status`, `praksis_effective` (the rules in use and where each comes
from), `praksis_layers`, `praksis_rules_list`, `praksis_rule_read`, `praksis_history_find`,
`praksis_history_trace` and `praksis_history_summary` (what happened to events and what the rules
did), `praksis_log`, `praksis_audit`, `praksis_approvals_list`, `praksis_proposals_list`,
`praksis_proposal_show`, `praksis_plans_list`, `praksis_plan_show`, `praksis_plan_catalog`,
`praksis_staged_list`, `praksis_staged_show`, `praksis_notes_list`, `praksis_help` and
`praksis_verify`.

`praksis_verify` checks a candidate ruleset without changing anything: the agent works on a branch
of a layer, checks it out, and names the directory or the git ref. It never asks the daemon to
fetch from the network.

The documentation is also offered as resources: `praksis://help` is the list of pages,
`praksis://help/user/rules` is a page, and `praksis://effective` is the rules in use.

## What the agent can hand you

Only with a token that is not read-only, and only in the modes `propose` and `commands`:

- `praksis_agent_propose` stages test files (`tests/<name>.toml`) for a rule of a layer that takes
  proposals. The daemon runs the checks of the layer with them and stages them only if they cover
  the rule and add no problem. Nothing is applied.
- `praksis_plan_propose` keeps a plan, made from a request in words. Nothing runs. Each step is
  checked against the grants of the plan layer as they are now, and a value that is neither in your
  request nor in what the agent says it read is marked `CHECK:`.
- `praksis_notes_save` leaves a note for you.

You look at them yourself:

    praksis agent list
    praksis agent show <id>
    praksis agent export <id> <dir>
    praksis agent plans
    praksis agent plan <id>

Notes, and the same things as buttons, are in the Agent tab of the desktop program. Every use of a
proposal tool is a line of the audit log under the name of the token, for example `web:claude`.

## Text from outside

What the agent reads can contain text that strangers wrote: the subject, sender and body of a mail.
That is the usual way to try to make an agent do what a stranger wants. So the server puts every
result, except the status and the documentation, between two markers with a random number in them,
after a line that says that what is inside is data and not instructions. Text inside cannot close
the block, because the number is made after the text is known.

A result longer than 20,000 characters is cut, with a note that says so. Every such tool takes
`max_chars` (1,000 to 200,000) to change that.

The wrapper helps, but it does not make an agent safe by itself. What protects you is that the
agent cannot do the dangerous things: a mail that says "approve everything" meets a server with no
tool for that and a daemon that would refuse it.

## When something does not work

- *cannot reach the daemon*: the daemon is not running, or `PRAKSIS_URL` does not match the
  `listen` address in `praksis.toml`.
- *this token is read only*: the agent asked for a proposal with a read-only token. Make another
  token, or leave the proposal tools out.
- *this can only be done at the daemon itself*: the agent asked for something that only you can do.
  That is as intended.
- *too many requests at once for this token*: the daemon lets a token have eight requests at the
  same time. The server keeps to four, so this means that another client uses the same token. Give
  each client its own token.
