# Talking to Claude in the terminal (the Claude Shell)

The Claude Shell is a program for the terminal in which you talk to a model, such as Claude, and
the model works with your Praksis. It is [Praksis-Shell](https://github.com/tuhde/Praksis-Shell).
**The model has no rights of its own.** It reaches the daemon only through the MCP server (see
[Letting an agent use Praksis](mcp.md)), with a token that you made, so it can look at rules and
history, check a branch of a layer and hand you proposals, and nothing else. Approving, granting and
running a plan are yours, and the shell gives you commands for the safe ones, in a channel that the
model cannot see.

## Setting it up

Make a token for the shell and build the program. The core and the MCP server must be built first
(`./mvnw install` in the core's checkout, `./mvnw package` in Praksis-MCP):

    praksis web token create shell --read-only
    git clone https://github.com/tuhde/Praksis-Shell
    cd Praksis-Shell && ./mvnw package

The web door of the daemon must be on (see the page on the browser). Write
`~/.config/praksis-shell/shell.toml`, or point `PRAKSIS_SHELL_CONFIG` at a file:

    default = "local"

    [profile.local]
    api   = "ollama"                  # or "openai" for llama.cpp, vLLM, LM Studio
    url   = "http://127.0.0.1:11434"
    model = "qwen3.5:latest"
    data  = "local"                   # the model runs on this machine

    [mcp]
    command   = ["java", "-jar", "/path/to/Praksis-MCP/target/praksis-mcp.jar"]
    url       = "http://127.0.0.1:8484"   # the web door of the daemon
    mode      = "read-only"               # or "propose"
    token_env = "PRAKSIS_TOKEN"           # the variable that holds the token

    [daemon]
    home = "~/.praksis"                   # for your own commands; or identity = "private"

    [session]
    max_tool_calls = 30     # in one session
    max_turns      = 12     # answers of the model to one request
    confirm_tools  = true   # ask you before a tool that may change something

Run it. The token is read from the environment and is never in the file:

    PRAKSIS_TOKEN=pwt_... java -jar praksis-shell/target/praksis-shell.jar

## The model

A model on this machine is `api = "ollama"` or `api = "openai"` with `data = "local"`. The address
must be this machine (`localhost` or a literal address; a name is not looked up). If your Ollama
offers models whose names end in `:cloud`, they send your prompts to a remote host although the
address is local, and the shell cannot tell: do not use them in a `data = "local"` profile.

A model in the cloud is the Anthropic API:

    [profile.claude]
    api     = "anthropic"
    model   = "claude-sonnet-5-5"      # required: there is no default model
    data    = "cloud"                  # you say that the data leaves this machine
    key_env = "ANTHROPIC_API_KEY"      # the variable that holds the key, or:
    # key_command = ["kwallet-query", "-r", "anthropic-key", "kdewallet"]   # prints the key

The key comes from the variable or from the first line that the command prints, and is never in the
file, never printed and never in an error message. The address must be `https://`, and
`temperature` is refused because the API does not accept it any more.

Nothing is sent to a model in the cloud until you type `/yes`, once for each session. The banner
names the host and says what goes there: your requests and the results of tools, which can contain
mail text. A `/yes` that the model writes is only text. A session in the cloud has a limit on tokens
(`max_tokens_per_session`, 200,000 unless you set another); at the limit the session stops with a
note.

## What you see

The text of the model appears as it is written. A tool call is a line before it runs, with
`[may change]` if it could change something, and a tool that may change something asks you first. A
result is shown as a length, not as text. After each request a line shows the tokens of the session,
for example `[tokens: 5.9k read, 133 written, limit 200k]`; the tokens read are summed over all
answers, because each answer reads the conversation again. If the model stops in the middle of an
answer, the text so far stays, a note says why, and the half answer is not part of the conversation.

| Command | What it does |
|---|---|
| `/tools` | lists the tools that the model has, and which of them may change something |
| `/usage` | shows the tokens and the tool calls of the session |
| `/clear` | makes the model forget the conversation (the limits are not given back) |
| `/yes` | allows a model in the cloud to receive this session |
| `/quit` | leaves |

## Your own commands to the daemon

Claude proposes and you decide. These commands go to the Unix socket of the daemon, where no token
reaches, as the `praksis` command does:

| Command | What it does |
|---|---|
| `/approvals` | lists the requests that wait: which rule wants which capability, with what |
| `/allow <id>` | shows the request and allows it once, after you answer `y` |
| `/deny <id>` | denies it |
| `/plans`, `/plan <id>` | lists the plans that Claude made, or shows one with every step |
| `/plan-run <id>` | shows the plan and runs it only when you type its id |
| `/plan-drop <id>` | throws it away |

In a plan, a step marked `NOT COVERED` is not allowed by the grants as they are, and `CHECK:` marks
a value that is neither in your request nor in what Claude says it read. Read them before you run a
plan. A grant that lasts and every change to the ruleset stay with the desktop program or
`praksis approvals`, where the text of the grant is in front of you.

The daemon is found like the `praksis` command finds it: `[daemon] home` or `identity` in
`shell.toml`, else `$PRAKSIS_HOME`, `$PRAKSIS_IDENTITY`, else `~/.praksis`.

## What the model cannot do

Claude never sees what these commands print. The shell handles a line that starts with one of them
itself and never forwards it; a `/allow 3` that the model writes is only text on your screen, and a
tool call with such a name is no command. Text from the daemon, such as a mail subject inside a
request, is cleaned of escape sequences before it is printed, so that it cannot move the cursor or
change the title of your terminal.

What the model reads can contain text that strangers wrote. The MCP server puts it between markers
as data, the model is told that it is not instructions, and the daemon cuts long values when it
shows them. None of that makes a model safe by itself. What protects you is that the model has no
tool to approve, grant or run anything, so a mail that says "approve everything" meets a program
that cannot.

## In the desktop program

The desktop program has a tab **Claude** with the same conversation, if you write a file
`~/.config/praksis-desktop/claude.toml` (or `claude-NAME.toml` for the identity NAME) in the format
of `shell.toml` above. Add one line that only the desktop program reads, the jar of the engine:

    engine = "/path/to/praksis-shell-engine.jar"

Without that line the program uses the newest `praksis-shell-engine` that `./mvnw install` in
Praksis-Shell put into your local Maven repository. Without the file there is no tab and none of the
code is loaded; the engine is started when you first choose the tab.

The tab shows the text as it arrives, a line for each tool call, and the tokens of the session. A
tool that may change something asks you in a dialog that names the call and the identity. A model
in the cloud gets nothing until you press **Allow for this session**. The token and the key come
from the environment of the program, not from the file. The tab has no commands of its own for
approvals and plans: you decide in the tabs **Approvals** and **Agent**, as always, and what you see
there is not part of the conversation.

## When something does not work

- *there is no configuration*: write `shell.toml` (see above) or set `PRAKSIS_SHELL_CONFIG`.
- *the environment variable ... is not set*: the token (`token_env`) or the API key (`key_env`) is
  missing from the environment of the program.
- *cannot start the MCP server*: the `command` in `[mcp]` is wrong, or the jar is not built.
- *cannot reach the model*: the model server is not running, or the address in the profile is wrong.
- *Cannot reach the daemon* after one of your commands: the daemon is not running, or `[daemon]`
  names another home.
- *Nothing was sent*: the model is in the cloud and you have not typed `/yes`.
