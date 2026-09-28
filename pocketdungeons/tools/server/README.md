# Test server control (for agents and humans)

One small tool to start the Pocket Dungeons **test server**, talk to the player in
Minecraft chat, read what they say and do, and shut it down cleanly. It is what
the live playtest interview uses, and any agent that can run a shell command can
use it (Claude Code, Devin, a script).

## Rules

- **Test server only.** This controls the local server in `pocketdungeons/run`,
  built from the current source. Never point it at the owner's live server.
- **Always stop with `stop`.** It saves the world first. Killing the process
  skips the save and can lose rooms and inventories.
- **Ask before changing the player's game.** Commands that teleport, give items,
  or reset things need the player's yes in chat first.

## Requirements

- Node 22 or newer, and the JDK the Gradle build uses (it downloads its own
  dependencies on first run).
- About 3 GB of free RAM for the build plus the server. `start` warns when less
  is free.
- Windows (use `server.bat`) or Linux/macOS (`node pdserver.mjs`).

## Commands

Run from this folder, or give the full path. On Windows `server <command>`, anywhere
else `node pdserver.mjs <command>`.

| Command | What it does |
|---|---|
| `start` | Builds the mod from source and starts the server in the background, waits until it answers, prints "Ready". Console output goes to `run/logs/agent-console.log`. Does nothing if it is already running. `--timeout <s>` (default 300). |
| `start --window` | Windows only: starts it in a visible console window instead (the same one `pocketdungeons/Run Test Server.cmd` opens), so a person can type into it. |
| `stop` | Saves and stops the server cleanly, waits until it is down. |
| `status` | `UP` with the players online, or `DOWN`, plus the last five log lines. |
| `say <player> <text>` | Sends a chat line to one player (or `@a` for everyone), tagged `[Interviewer]` in purple. |
| `cmd <command>` | Runs a console command (no leading slash) and prints the reply, for example `cmd list` or `cmd dungeon admin list`. |
| `chat` | Prints the events since the last time anyone ran `chat` (a cursor is kept in `run/.agent-chat-cursor.json`), then exits. |
| `chat --all` | Prints every event in the current log. |
| `chat --follow` | Prints new events as they happen, until stopped. |
| `sync` | Skips every event logged so far, so the next `chat` or `wait` starts from now. |
| `wait [--player <p>] [--timeout <s>]` | Blocks until there are new events (default up to 240 s), prints them, exits. The loop for agents that cannot stream. With `--player`, keeps that player's Lemon in llm mode while the server is up. `chat` and `wait` share one cursor: use one or the other. |
| `context <player>` | Prints the player's context snapshot as one line of JSON: where they are, every room on the floor, omen, spawners, inventory essentials, Lemon's state and their last 20 journal events (`docs/LEMON_SPEC.md` section 3). |
| `lemon say <player> <text>` | Lemon appears beside the player, says it (a speech bubble only they see, plus a yellow `Lemon:` chat line), then vanishes. |
| `lemon ask <player> <text>` | The same, then Lemon stays lit and waits for the reply; the player's next line to Lemon comes back as a `lemon answer` event. |
| `lemon quiet <player>` | Lemon vanishes now. |
| `lemon mode <player> <guide|llm>` | `llm` holds the player's questions to Lemon for you to answer (with `lemon say`). It lapses back to `guide` after 5 minutes (`lemonLlmLapseSeconds`), so set it again every few minutes while you are connected. A question you leave unanswered for 45 seconds (`lemonFallbackSeconds`) gets Lemon's own honest "I do not know that one yet." |

### Event lines

`chat` prints one compact line per event and nothing else:

```
19:03:48 join MrPinoy123456789
19:03:56 chat <MrPinoy123456789> hey
19:12:45 floor MrPinoy123456789 completed floor 1 of slot 0 (run #1, chests 3, tier 1)
19:40:02 error Refusing door preview: content reload in progress
20:05:13 leave MrPinoy123456789
22:05:30 server ready
```

Kinds: `chat`, `join`, `leave`, `floor`, `lemon`, `report`, `error` (a Pocket Dungeons
WARN or ERROR, or an exception), `server ready`, `server stopping`.

Lemon lines look like this:

```
19:20:01 lemon mode <MrPinoy123456789> llm
19:20:14 lemon ask <MrPinoy123456789> where do I bank?
19:20:30 lemon says <MrPinoy123456789> Pull the HOME lever in the staging room.
19:21:02 lemon asks <MrPinoy123456789> How did that floor feel?
19:21:20 lemon answer <MrPinoy123456789> too many skeletons
19:25:40 lemon unanswered <MrPinoy123456789> what is a keystone
```

`lemon ask` is the player talking to Lemon, `lemon answer` their reply to your `lemon ask`,
`lemon says` and `lemon asks` are Lemon speaking, `lemon held` a line of yours held back (the
player is in a fight, or has Lemon quiet), `lemon unanswered` a question nobody answered in
time. Solo, everything the player types in chat goes to Lemon and is not broadcast, so it
shows up here as `lemon ask`, never as `chat`; in a party only lines that start with "Lemon" do.

## A live interview session

1. `server start`, then tell the player: Minecraft 26.2, Multiplayer, Direct
   Connection, `localhost` (port 25565).
2. Watch for them: poll `server chat` every 10 to 30 seconds, or run
   `server chat --follow` if your environment can stream a long-running
   command's output (Claude Code: the Monitor tool).
3. When they join, greet them once with `server say <name> "..."` and tell them
   they can just type in chat. To speak through Lemon instead, set
   `server lemon mode <name> llm` (and again every few minutes), then use
   `server lemon say` and `server lemon ask`; their replies arrive as `lemon` events.
   `server context <name>` shows what they are standing in before you ask.
4. Ask at natural breaks (a `floor` event, after they go home), never mid-fight
   unless they start it. Short questions, one at a time, a few per hour, and stop
   when asked. The full interview method is in
   `.claude/skills/playtest/SKILL.md` (sections 2, 3 and 6).
5. When they are done: `server stop`, then write the findings to
   `pocketdungeons/docs/playtests/<yyyy-mm-dd>-<n>.md` as the skill describes.

## Where things are

| What | Where |
|---|---|
| World, settings, logs | `pocketdungeons/run/` (git-ignored) |
| Server log | `pocketdungeons/run/logs/latest.log` |
| Build and startup output | `pocketdungeons/run/logs/agent-console.log` |
| RCON port and password | `pocketdungeons/run/server.properties` (`rcon.port`, `rcon.password`); the server binds to 127.0.0.1 only |
| Playtest journal | `pocketdungeons/run/world/pocketdungeons/playtest/<yyyy-mm-dd>/<uuid>.jsonl` (format: `pocketdungeons/docs/PLAYTEST_EVENTS.md`) |

## Troubleshooting

- **`start` never gets to Ready:** read the last lines it prints (or
  `run/logs/agent-console.log`). "Paging file is too small" or Gradle waiting on
  memory means the machine is short of RAM: close other large programs.
- **`RCON authentication failed`:** the password in `server.properties` changed
  while the server was running; restart the server.
- **`lemon say` text is cut off or refused:** a console command over RCON is limited to
  about 1400 bytes. Keep Lemon's lines short; it splits long ones into bubbles anyway.
- **The player cannot connect:** check `server status` says `UP`, and that their
  game is 26.2. Use `127.0.0.1` if `localhost` fails.
