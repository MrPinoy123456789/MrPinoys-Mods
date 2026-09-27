# Playtest journal: event format

The contract between the mod's in-game playtest journal (which records what
happens and asks the check-in questions) and the `/playtest` interview skill
(`.claude/skills/playtest/`), which reads it. Status: format agreed 2026-09-26;
the in-game side is not built yet.

## Why one system

The check-in questionnaire and the event log are the same thing. Every answer a
player gives in game lands in the same stream as the events around it, so the
interview can ask about what actually happened ("you went home after floor 3 at
band 1; what made you stop?") instead of asking for opinions from memory. The
same stream is the per-floor balance telemetry `ZONES_SPEC.md` section 5.3 asks
for; there is no second telemetry system.

## Files

- Location: `<world save>/pocketdungeons/playtest/<yyyy-mm-dd>/<player-uuid>.jsonl`
  (the date is the server's local date when the event is written).
- One JSON object per line, UTF-8, appended and flushed per event. A torn last
  line after a crash must be skipped by readers, never fatal.
- Retention: keep 30 days of date folders; delete older ones on server start.
- Config (`pocketdungeons.json`): `playtestJournal` (record events, default
  true) and `playtestCheckins` (show the check-in dialog, default true). A
  player can turn their own check-ins off with `/dungeon checkin off` (and back
  on); events are still recorded while the server has the journal on.
- Local files only. Nothing is ever sent off the server.

## Common fields (every line)

| Field | Type | Meaning |
|---|---|---|
| `t` | string | ISO-8601 UTC timestamp, seconds precision |
| `ev` | string | Event name (below) |
| `player` | string | UUID |
| `name` | string | Player name at the time |
| `slot` | int | Instance slot, or -1 outside an instance |
| `phase` | string | `RunSession.Phase`, or `NONE` outside an instance |
| `floor` | int | Floor index within the current interval, or -1 |
| `zone` | string | Zone/theme id of the current floor, or `""` |
| `party` | int | Party size at the time, 1 when solo |

## Events

| `ev` | When | Extra fields |
|---|---|---|
| `session_join` | Player joins the server | |
| `session_leave` | Player leaves the server | `reason`: `quit`, `disconnect` |
| `enter_dungeon` | Crosses into the dungeon dimension | `via`: `command`, `reenter`, `rejoin`, `visit`, `invite` |
| `leave_dungeon` | Crosses out | `reason`: `exit`, `checkpoint_exit`, `disconnect`, `purge`, `rescue_eject`, `other` |
| `bag_chosen` | Class picked | `bag` |
| `door_preview` | A door preview is stamped | `step`, `level`, `theme`, `affixes` (array) |
| `door_commit` | A door is committed | `step`, `level`, `theme`, `affixes`, `fuel_spent`, `rooms` (array of room ids on the floor) |
| `room_entered` | First time any member enters a cell on this floor | `room` (room id), `cell` (`"x,z"`) |
| `omen_rise` | Omen goes up | `source`: `dwell`, `sensor`, `shriek`, `bargain`, `headstart`; `amount`, `total`, `room` |
| `rescue` | A killing blow is turned into a rescue | `cause` (damage type id), `room` |
| `floor_complete` | This player is credited with the floor | `seconds`, `omen`, `spawners_cleared`, `spawners_total`, `rescues`, `blocks_placed`, `durability_used`, `chests` |
| `bank` | Interval settled for this player | `trigger`: `home_lever`, `checkpoint_exit`, `grace_expiry`; `floors`, `band`, `levels_gained`, `carry`, `chests`, `depth_bonus`, `key_level` |
| `kit_topup` | Kit top-up applied | `band`, `granted` (object item id to count), `tools_replaced` (array) |
| `quit_floor` | `/dungeon quit` | `penalty` (levels) |
| `owner_hold` | Owner reconnect grace starts, resumes or expires | `state`: `start`, `resume`, `expire` |
| `checkin` | Player answers the in-game check-in | `prompt` (`after_bank`, `after_quit`, `after_session`), `score` (1 to 5, or null if skipped), `comment` (string, may be empty) |
| `report` | `/dungeon report <text>` or the check-in's "something broke" | `text`, `pos` (`"x,y,z"`), `room`, `recent` (last 10 `ev` names for this player) |
| `error` | The mod logs an error while this player is in an instance | `message` (first line only) |

`blocks_placed` and `durability_used` count this player's own actions on the
floor: blocks placed in dungeon cells, and durability points lost by tools and
armour. Counting may be approximate; say so in the implementation's javadoc.

## The in-game check-in

- After each bank (and after a `/dungeon quit`), a vanilla dialog: "How was that
  stretch?" with five buttons (1 to 5), an optional one-line comment, a
  "Something broke" button that opens a short report field, and "Skip".
- At most one check-in per bank; never during a floor; never for visitors.
- The answer is written as a `checkin` (and, from the broke button, a `report`)
  event. Voice: short and in the mod's terse tone; no survey-speak.

## Stability

Readers ignore unknown fields and unknown events. New events and fields may be
added; existing ones are not renamed or repurposed.
