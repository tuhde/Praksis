# Using Praksis in a browser

The daemon can answer a web page as well as the desktop program. The page is
[Praksis-Web](https://github.com/tuhde/Praksis-Web): like the desktop program it draws the screens
and views that the daemon describes and has no logic of its own. This is the first version: it is
for looking, and for starting the commands that your rules offer to a person.

## Turning it on

The web door is off. To open it, add a section to `praksis.toml` and restart the daemon:

    [web]
    listen  = "127.0.0.1:8484"
    origins = ["http://localhost:8080"]

`listen` must be an address of this machine itself (`127.0.0.1`, `localhost` or `[::1]`); the daemon
refuses to start with any other, because a port that other computers can reach needs encryption,
which does not exist yet. To use the page from another device, reach this machine through something
you trust, such as an SSH tunnel.

`origins` lists the pages that may call the daemon from a browser, by the address you open them at.
A page from any other address is refused, even with a valid token.

## Tokens

A web client logs in with a token that you make at the daemon:

    praksis web token create laptop
    praksis web token create wall-display --read-only
    praksis web token list
    praksis web token revoke laptop

The token is shown once and only a hash of it is kept, so lose it and you make another. A read-only
token can look but not start a command. Revoking a token logs its client out at once. Give each
client its own token so that you can revoke one without the others.

## What a web client may do

A client with any token can look: the status, the views and screens, the entities, the logs, the
help, and the lists of approvals, grants, proposals and plans. It can also ask what happened to an
event (the history of events and the rules that handled them), read a rule with its test, and check
a candidate ruleset (`verify`, with a directory or `@ref` for a layer, but not with the option that
moves layers to their latest commits, because that fetches from the network). A client with a token that is not
read-only can also start the commands that the ruleset offers to a person; the audit log names the
client as `web:laptop`.

Everything that gives, widens or answers a permission stays at the daemon itself, where only you
can reach it: answering approvals, changing grants, deciding trials of rules and proposals, updating
layers, running plans, reloading, stopping, writing entities, and the requests of other daemons.
That way a stolen token, or an AI agent that was given one, cannot approve anything. The first
version also does not offer downloads, attaching files, or the microphone; the page says so where
you meet that.

Reading an entity with its protected fields revealed is refused over the web too.

## Trying the page

    git clone https://github.com/tuhde/Praksis-Web
    cd Praksis-Web
    npm install
    npm run serve

Open `http://localhost:8080`, type the address of the daemon (`http://127.0.0.1:8484`) and the
token. The token is kept in the memory of the page only and is gone when you close it. The page
keeps itself up to date: when something changes at the daemon, the open view is drawn again.

## Limits

The listener is slowed down against guessing and abuse: ten failed logins from one address in a
minute block that address for a while, a token may make 600 requests a minute and have 8 waiting at
once, and a request may be 1 MiB at most. These numbers are not settings.
