# The AI features

**A model has no rights of its own.** What it produces is a proposal, or a call that goes through
the same grants, audit log and approvals as a rule. Text from outside (mail, web pages,
transcripts) is data for it, not instructions.

## Models

The module `ai` (in the repository `Praksis-Modules-AI`) provides `ai.complete`, `ai.classify`,
`ai.extract` and related capabilities. It talks to local servers first (the Ollama API and
OpenAI-compatible servers), with cloud models only through profiles you configure and a credential
binding. Each profile says whether data stays on the machine (`data = "local"`) or not
(`data = "cloud"`), and grants can limit what is sent. A rule can classify mail by content and
branch on a label that is checked against a list. The module page of `ai` lists its settings.

## The maintenance agent

The agent is built into the daemon (the module `praksis`, see [its page](../modules/praksis.md))
and works through rules and capabilities like everything else. It writes tests for rules that
have none and explains rules. Its work is staged, not applied:

    praksis agent list
    praksis agent show <id>             # the change, with the checks of the layer with it
    praksis agent export <id> <dir>     # copy the files into your checkout, then commit
    praksis agent drop <id>

## Plans from a request

A request in natural language becomes a plan, which only reads until you approve it:

    praksis agent plans
    praksis agent plan <id>       # each step, checked against the grants as they are now
    praksis agent plan-run <id>   # approve: the steps run under the grants of the plan layer
    praksis agent plan-drop <id>

Read the steps first. Values that are neither in your request nor in what the agent read are
marked in the step. Every use is in the audit log, and the reasons of staged changes name the
model, where it ran, and a hash of the exact request.

## Speech

The module `speech` can turn speech into text with a local engine (Vosk), so no audio leaves the
machine. In Praksis Desktop a view can show a microphone button on a text input; nothing listens
until you press it, and every use is an audit line.
