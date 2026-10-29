# Lemon in Pocket Dungeons

Lemon's companion engine now lives in the independent `lemon/` mod. Its runtime
spec is `../../lemon/docs/LEMON_SPEC.md`; the generic interviewer runbook is
`../../lemon/docs/LEMON_AGENT.md`. Pocket Dungeons retains her character and every
interaction that belongs to its game. Neither jar imports the other's classes.

## Character and teaching

Lemon is a small, warm, slightly playful allay, never corporate. She speaks in
short lines, teaches through the player's situation, and never gives a puzzle's
solution. Help is a nudge, then a clue. Ordinary mechanics can be explained freely.
Her name began as the working title "LLM", read aloud.

She looks for Alex's diaries, remembers entries handed to her, and shares a useful
mechanics tip when given a new one. Her archive survives a lost physical book.
A duplicate stays with the player. Completing the archive retains the existing
emerald reward behavior. These are Pocket Dungeons rules, not companion features.

## Game-owned interactions

`LemonBridge` checks for the registered root command before making an immediate
dispatcher call. It shares no classes or API with Lemon.

- A main-hand keystone right-click calls `lemon summon <player>` when available.
  An off-hand keystone does not summon her. Without Lemon, there is no extra action.
- First-visit and station tutorials call `lemon tour <player> <text>`. Result 1
  means shown, 2 means held for combat and counts as delivered for tutorial
  progression, and 3 means held for quiet and leaves the first-visit step due.
- With no Lemon command, tours show an action-bar line and a chat line instead.
- `/dungeon lemonmenu` opens the same menu as the room's wall lodestone.
- `/dungeon lemonarchive` accepts a diary from the player's main hand, records its
  band and says the tip through `lemon say`. A plain book opens the menu. These
  commands are ordinary player commands and act only on the invoking player.
- Room captures and entity sweeps use only the scoreboard tag `lemon.part`.
  Tagged parts are temporarily moved out of a structure capture and restored in
  a finally block, never saved or removed by cleanup.

The server config, not the mod, connects Lemon's clicks to these commands:

```json
{
  "onInteract": "dungeon lemonmenu",
  "onInteractHolding": {
    "minecraft:written_book": "dungeon lemonarchive"
  }
}
```

Place those fields in `config/lemon/lemon.json`. The playtest tool installs them
for the PD runtime. All six old Lemon timing keys in `pocketdungeons.json` are
ignored with a migration warning; set their values in Lemon's config instead.

## Context and interviews

`/dungeon admin context <player>` remains a one-line JSON snapshot of phase,
floor, zone, compass/run level, party, room and room list, spawners, omen/lives,
inventory, recent game events, last bank and safe room. It contains no Lemon
state. `node tools/playtest/playtest.mjs context <player>` from the workspace root
merges it under `pocketdungeons` with `/lemon context` under `lemon`.

Game events remain in the Pocket Dungeons playtest journal. Companion asks,
answers, overheard chat and `/lemon report` go to the separate Lemon journal.
`/dungeon report` remains available for a game-specific journal report.

The plain-words mechanics briefing is `../tools/lemon/GUIDE.md`. Use compass,
scrap, haul and lives in player-facing speech. The first-visit and station tours
are built; general stuck detection, room hint ladders and the keyed FAQ remain
future work. Any future guide data belongs to the game and must not create a
compile-time bridge.

## Verification

`LemonGameTest` covers optional commands, keystone use, the context without Lemon,
tagged parts staying out of a real room capture, and diary archive duplication.
To exercise the bridge with the independent jar, build Lemon, then add
`-PlemonTestJar=../lemon/build/libs/MrPinoys_Lemon-0.1.0.jar` to the PD gametest run.
The default suite runs without that jar and exercises the plainer fallback.
Appearance, live tour ordering, menu clicks and two-game interviewing still need
the approved test-server acceptance run.
