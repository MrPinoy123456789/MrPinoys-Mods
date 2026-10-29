# Lemon: the suite companion

Lemon is a standalone server-side Fabric mod for Minecraft 26.2. Vanilla clients
need no mod. This implements the engine extraction approved in
`../../docs/2026-10-27-lemon-decouple-spec.md`; Charter's source integration and the
two-game live acceptance check remain separate work.

## Body and speech

One allay and one text-display bubble belong to each player, visible only to that
player. Both carry the scoreboard tag `lemon.part`. They are unsaved, invulnerable,
silent except for deliberate chirps, cannot collect items, and are removed on
logout, respawn, level change and server shutdown. Lemon owns no mixins.

She appears when she has something to say, glows yellow while speaking or waiting,
and leaves after her linger or idle deadline. She stays at an anchor, turning and
bobbing in place; a stale anchor or combat-state change chooses a new spot. Long
text is split into short bubbles and also sent privately to the player's chat.
Players within 48 blocks in the same level have each other's speech journaled
as overheard chat. They do not see another player's body or private replies.

Unprompted speech waits for combat to end, with a two-minute expiry. Quiet blocks
unprompted lines unless the player is already in conversation. Replies are always
shown. Body behavior, fallback timing, pending questions and llm leases retain the
existing Pocket Dungeons engine behavior.

## Chat and modes

Without another player within 48 blocks in the same level, plain chat reaches
Lemon. Otherwise only a leading `Lemon` (case insensitive, optional comma or colon)
or `/lemon <text>` addresses her. Other nearby chat is journaled for listeners
with the speaker's name; ordinary chat remains broadcast.

Guide mode admits when it does not know an answer. General FAQ, keyed hints and
per-game guide-data loading are not built. In llm mode questions wait for an agent.
The mode expires unless refreshed. Think extends the fallback deadline; a reply
resolves pending questions, including a late reply after fallback. The lease
survives relog until its deadline, but shutdown clears it.

## Commands

Player commands: `/lemon <text>`, `/lemon quiet`, `/lemon on`, and
`/lemon report <text>`.

Operator commands:

| Command | Behavior |
|---|---|
| `/lemon say <player> <text>` | Unprompted speech, held for combat or quiet |
| `/lemon ask <player> <text>` | Speech followed by waiting for an answer |
| `/lemon reply <player> <text>` | Immediate reply, resolves pending questions |
| `/lemon think <player> [text]` | Acknowledge, hide, extend fallback hold |
| `/lemon quiet <player>` | Hush until that player next addresses Lemon |
| `/lemon mode <player> guide|llm` | Hand back or refresh the agent lease |
| `/lemon summon <player>` | Return 1 when her body is present, otherwise 0 |
| `/lemon tour <player> <text>` | Return 1 shown, 2 held for combat, 3 held for quiet |
| `/lemon context <player>` | One JSON line with companion state only |

Context includes present, waiting, mode, question, pending-question count, quiet,
quiet-until-spoken and thinking. Game context belongs to each game's own command.

## Configuration and interactions

`config/lemon/lemon.json` is created with defaults when absent. Timing ranges and
defaults match the previous engine:

| Key | Default seconds | Range |
|---|---:|---:|
| `lemonAskIdleSeconds` | 120 | 10 to 1800 |
| `lemonFallbackSeconds` | 45 | 5 to 600 |
| `lemonIdleSeconds` | 20 | 3 to 600 |
| `lemonLingerSeconds` | 4 | 1 to 60 |
| `lemonLlmLapseSeconds` | 300 | 30 to 3600 |
| `lemonThinkSeconds` | 90 | 10 to 600 |

`onInteract` defaults to an empty command. `onInteractHolding` defaults to an empty
object mapping item ids or `#namespace:item_tag` selectors to replacement commands.
The first matching holding hook replaces the general hook. Empty means no action.
Hooks run as the clicking player, without elevated permissions, on main-hand
clicks on their own Lemon. The journal never transfers to the player.

Pocket Dungeons server hooks:

```json
{
  "onInteract": "dungeon lemonmenu",
  "onInteractHolding": {
    "minecraft:written_book": "dungeon lemonarchive"
  }
}
```

Pocket Dungeons itself validates the diary marker; a plain written book opens the
menu instead. The playtest tool installs these hooks for its PD test runtime,
preserving other configuration values and existing custom hooks.

## Journal and transport

Ask, answer, heard and report events append as JSONL under
`<world>/lemon/journal/<UTC yyyy-mm-dd>/<uuid>.jsonl`. Every record includes time,
event, UUID and name. Ask records include text, answered_by, hint_tier and wait_s;
answers include question and text; heard includes from and text; reports include
text. Lemon also retains the `Lemon ask <name> text`, answer, says, tour and other
log-line forms used by the event filter. Game events stay in each game's journal.

`../../tools/playtest/` owns RCON/Kinetic transport, the shared cursor, wait filter,
game profiles and MCP. Its merged context object nests Lemon under `lemon` and
game snapshots under their mod ids. See `LEMON_AGENT.md` for the operating method.

## Isolation and verification

No game mod imports Lemon or declares it as a required dependency. A game may run
registered commands by name and inspect the public entity tag. A missing jar must
produce a plainer but working game. Pocket Dungeons falls back to action-bar and
chat tours and leaves keystone use unchanged when the command is absent.

From this directory's mod root, run `./gradlew.bat build` for the pure tests,
gametests and jar. `./gradlew.bat dist` copies the deployable jar to the workspace
`dist/`. Client appearance, nearby overhearing, and two-game remote timing still
need the live acceptance check from the approved extraction spec.
