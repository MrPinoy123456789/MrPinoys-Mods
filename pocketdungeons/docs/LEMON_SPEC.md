# Lemon: the player's guide

Status: design approved by the owner on 2026-09-27. Not built.

Lemon is a small companion that appears beside the player when it has something
to say and vanishes when it does not. It is one character with four jobs:

1. **Guide and tutorial:** teaches the game in context, the first time each thing
   happens.
2. **Hint system:** helps a stuck player without spoiling the puzzle.
3. **Playtest interviewer:** when an LLM agent is connected, Lemon is the body the
   interviewer speaks through.
4. **Confusion telemetry:** every question asked of Lemon, every hint it gives and
   every question it could not answer is logged, so tutorial and UI work is driven
   by what players actually struggled with.

It works without any LLM ("guide mode", the production default) and gains
open-ended answers and the interview when an agent is connected ("LLM mode"). If
the agent drops, Lemon falls back to guide mode instead of disappearing (the
2026-09-27 playtest showed a player quitting when the interviewer dropped).

The name is a working title ("LLM", read aloud).

## 1. The body

All vanilla, so it works for players without a client mod.

- A vanilla **Allay**, custom name "Lemon", invulnerable, no AI goals of its own,
  cannot pick up items, no collision with players or blocks, never targeted by
  mobs, silent except for the cues below. Tagged so it is never captured by room
  saves (`RoomStore`), never counted by teardown as a leftover, never scaled by mob
  scaling, and removed on logout, dimension change, and server stop.
- **One Lemon per player, private:** only its player can see it. Hide it from
  every other player's entity tracking (a small mixin on the server's entity
  tracking is allowed for this; the mixin rule is relaxed). In a party, each player
  sees only their own Lemon.
- **Appears when speaking:** summoned at a spot in view off to one side with a
  poof of particles and an allay chirp. While present it stays at that spot,
  only turning to face the player and bobbing gently; walking away or turning
  leaves it behind. It picks a new spot only when it next speaks or is spoken
  to and the old spot is far away, out of view or blocked, or when a fight
  starts or ends and it needs the other distance.
- **Highlighted while talking or waiting for an answer:** a glowing outline in
  yellow (vanilla glowing plus a team colour).
- **Vanishes when quiet:** after a short idle period (about 20 seconds after its
  last line, longer while waiting for an answer, configurable) it drifts away and
  disappears with a soft poof.
- **Speech in two places:** a floating speech bubble just above its head (a text display
  entity, also private to the player) for the current line, and a chat line with a
  yellow `Lemon:` tag so there is a history. Long text is split into short bubbles
  shown in sequence.
- Never appears mid-fight on its own initiative (recent damage taken or dealt, or a
  hostile mob targeting the player); it waits. It always answers when the player
  addresses it.

## 2. Talking to Lemon

- **Solo:** anything the player types in chat goes to Lemon.
- **In a party:** only chat that starts with "Lemon" (any case, optional comma or
  colon) goes to Lemon; other chat is ordinary party chat.
- `/lemon <text>` always works, and `/lemon quiet` / `/lemon on` silences or
  restores unprompted appearances (answers to direct questions still come).
- Lemon's replies and questions never go to other players.

## 3. Context

Lemon (in both modes) and any connected agent work from one **context snapshot**
per player, rebuilt on demand:

- phase, floor index, zone and theme, keystone level, party size;
- **the room the player is standing in** (room id, looked up from the floor's cell
  to room map, which each floor keeps from its plan) and **every room on the
  current floor**, each with its role, whether it has been entered, and its spawner
  and lock state;
- omen and band so far, the spawner gate (cleared, needed, total);
- inventory essentials: tool durability, block counts, food, whether the pack is
  near full;
- the last 20 journal events for the player (format:
  `docs/PLAYTEST_EVENTS.md`).

Exposed to agents as a console command (for example `dungeon admin context
<player>`) that prints the snapshot as one line of JSON, and wrapped by the server
tool (`server context <player>`).

## 4. Guide mode (no LLM)

Rule-driven, data-driven, and **never spoils a puzzle**.

- **Tutorial moments** replace the guided-task tutorial's chat lines: first
  entry, first staging room (doors, preview, commit lever), first omen rise, first
  floor clear, first time home is offered, first go-home, first kit top-up, first
  keystone level-up, first station use, and so on. Each fires once per player
  (tracked in `DungeonLog`), in the mod's terse voice. (The guided task line,
  the weekly bounties and the room's tracker screen were removed 2026-10-02;
  weekly floors and rooms will replace the bounties.)
- **Summon and journal (2026-10-02):** right-clicking the keystone with it in
  the main hand summons Lemon, or moves an already present Lemon to a fresh
  spot in view; it stays `lemonIdleSeconds`, and the log line `Lemon summoned
  <player>` wakes the agent. Lemon always holds a journal (a book and quill).
  Reaching for it (a right-click on Lemon) never hands it over: it opens the
  menu the room's wall lodestone opens.
- **Stuck detection:** time in an unsolved room past a threshold, repeated
  rescues in the same room, walking back and forth without progress, or a player
  question. Lemon then offers the room's **hint ladder**:
  - tier 1, a **nudge** (where to look, what to pay attention to);
  - tier 2, a **clue** (which mechanism matters, without the sequence);
  - there is no tier 3: Lemon never gives the solution.
  The next tier is only offered after more time or when the player asks again.
- **Hint ladders are authored per room** in the room's JSON (a new optional
  `hints` block: `nudge`, `clue`, and optional `about` lines for general questions),
  with situation-type defaults (locks, pressure plates, spawners, fluids, parkour)
  when a room has none. Pack authors can write them; the schema, `INTEGRATION.md`
  and `PackValidator` cover the field. An agent drafts ladders for all shipped
  rooms from their code and specs; the owner reviews them.
- **Questions in guide mode** are answered from the current room's hints, the
  situation defaults, and a small FAQ keyed on words (home, bank, omen, keystone,
  doors, kit, fuel, stations). A question Lemon cannot answer gets an honest "I do
  not know that one" and is logged as unanswered.

## 5. LLM mode (agent connected)

An agent drives Lemon through console commands (via RCON and the server tool):

- `dungeon lemon say <player> <text>`: appear, speak, then idle out.
- `dungeon lemon ask <player> <text>`: appear, highlighted, and wait for the reply
  (the next thing the player says to Lemon), with a longer idle timeout.
- `dungeon lemon quiet <player>`: vanish now.
- `dungeon lemon mode <player> <guide|llm>`: while `llm` is set (the agent sets it
  and refreshes it every few minutes; it lapses back to `guide` when not
  refreshed), player questions are logged for the agent to answer instead of being
  answered by the guide rules. If the agent does not answer within a short time,
  guide mode answers so the player is never left hanging.

In LLM mode the agent follows the same no-spoiler rule for puzzles (nudge, then
clue, never the solution) and the interview method in
`.claude/skills/playtest/SKILL.md`.

## 6. Journal events

Added to `docs/PLAYTEST_EVENTS.md`:

| `ev` | When | Extra fields |
|---|---|---|
| `lemon_ask` | The player says something to Lemon | `text`, `room`, `answered_by` (`guide`, `llm`, `none`), `hint_tier` (0 if not a hint) |
| `lemon_heard` | A party member said something in chat (written for each other online member) | `from` (the speaker's name), `text` |
| `lemon_hint` | Lemon offers a hint unprompted | `trigger` (`dwell`, `rescues`, `wandering`), `room`, `tier` |
| `lemon_tutorial` | A tutorial moment fires | `moment` |
| `lemon_answer` | The player answers a question Lemon (the interviewer) asked | `question`, `text` |

The `/playtest` digest summarises them as a **confusion log**: what players asked,
where, and what went unanswered.

## 7. Build order

1. **Journal and context:** the in-game journal (`docs/PLAYTEST_EVENTS.md`),
   the floor's cell to room map, and the context snapshot command.
2. **Lemon's body and the driver commands:** the allay companion, privacy, bubble,
   appear and vanish, chat routing, `/lemon`, and the `dungeon lemon` console
   commands, plus `server lemon ...` and `server context` in the server tool.
3. **Guide mode:** tutorial moments, stuck detection, hint ladders (schema,
   validator, situation defaults, drafted ladders for all shipped rooms), the FAQ,
   and the unanswered log.
4. **Hook up the interviewer:** the playtest skill and the server tool speak
   through Lemon; the digest gains the confusion log.
