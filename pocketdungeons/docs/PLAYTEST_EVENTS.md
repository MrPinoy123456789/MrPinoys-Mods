# Playtest journal: event format

The contract between the mod's in-game playtest journal (which records what
happens and asks the check-in questions) and the `/playtest` interview skill
(`.claude/skills/playtest/`), which reads it. Status: format agreed 2026-09-26;
the journal is built (`PlaytestJournal`, 2026-09-27). The check-in dialog is not
built: Lemon (`docs/LEMON_SPEC.md`) replaces it, so `checkin` lines are not
written yet and `playtestCheckins` is not read.

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

`floor` is the floor in play while the run is `ACTIVE` (1 for the first floor of an
interval), the floor just cleared between floors (`FLOOR_CLEARED`, `PREVIEW`), and 0
at home before the first door.

## Events

| `ev` | When | Extra fields |
|---|---|---|
| `session_join` | Player joins the server | |
| `session_leave` | Player leaves the server | `reason`: `quit`, `disconnect` |
| `enter_dungeon` | Crosses into the dungeon dimension | `via`: `command`, `reenter`, `rejoin`, `visit`, `invite` |
| `leave_dungeon` | Crosses out | `reason`: `exit`, `checkpoint_exit`, `disconnect`, `purge`, `rescue_eject`, `other` |
| `bag_chosen` | Class picked | `bag` |
| `door_preview` | A door preview is stamped | `step`, `level`, `theme`, `affixes` (array) |
| `door_commit` | A door is committed | `step`, `level`, `theme`, `affixes`, `fuel_spent` (the lives a side door cost; the name is from the fuel era, 0 for a free door), `rooms` (array of room ids on the floor) |
| `dungeon_chosen` | The first door of a trip is committed: the trip's dungeon is picked (dungeon structure W2) | `dungeon` (id), `act`, `kind` (`story`, `resource`, `capstone`), `entry` (node id), `step` |
| `edge_taken` | A later door is committed: the party takes an edge of the dungeon graph | `dungeon`, `from` (node id), `to` (node id), `step` (the +1 to +3 dealt to that door, 0 in a resource dungeon), `cost` (lives the side door took, 0 for a main path edge) |
| `node_entered` | A committed door opens a floor in a dungeon (written after `dungeon_chosen` or `edge_taken`) | `dungeon`, `node`, `name`, `layer`, `final`, `step`, `path_length` |
| `room_entered` | First time any member enters a cell on this floor | `room` (room id), `cell` (`"x,z"`) |
| `omen_rise` | Omen goes up | `source`: `death` or `door` (a side door took lives); `amount`, `total` (lives are `5 - total`), `room`. The other triggers write `hazard` |
| `rescue` | A killing blow is turned into a rescue | `cause` (damage type id), `room` |
| `floor_complete` | This player is credited with the floor | `seconds`, `omen`, `spawners_cleared`, `spawners_total`, `rescues`, `blocks_placed`, `nodes_mined` (resource nodes this player mined on the floor, dungeon structure W4), `nodes_total` (nodes the floor opened with), `durability_used`, `chests` |
| `dungeon_finished` | The final floor of a dungeon is cleared, one line per member present (dungeon structure W2) | `dungeon`, `node`, `floors` (the trip's path length), `emeralds` (the finish emeralds; half on a repeat finish), `vault_chests`, `first` (this player's first finish of it), `diary` (page id handed over, empty for none) |
| `act_unlocked` | A capstone clear opened an act for a member present (dungeon structure W3); one line per member who gained it | `dungeon` (the capstone), `act` (the act opened, 0 when none), `campaign_complete` (true for an act 5 capstone) |
| `bank` | Interval settled for this player (the reward chests; the scrap is `haul_banked`) | `trigger`: `home_lever`, `checkpoint_exit`, `grace_expiry`; `floors`, `levels_gained` and `scrap_left` (both always 0 now), `chests`, `depth_bonus`, `key_level` |
| `floor_pay` | A floor clear pays this member (2026-10-07) | `scrap` (into the haul), `emeralds` (0; unused vault keys settle at the bank now) |
| `haul_banked` | A member's haul is banked (home, finish, fail or a haul left from a trip that ended while away) | `context` (`home`, `finish`, `fail`, `orphan`), `banked`, `lost`, `compass` (level after), `progress` (scrap in the bar after) |
| `emeralds` | Emeralds granted as a dungeon reward | `amount`, `source` (`dungeon_finish`, `lemon_archive`, ...) |
| `hazard` | A pressure trigger answered (a wave or a cue; no lives are spent) | `source`: `dwell`, `sensor`, `shriek`, `bargain`, `silence`, `vault`; `room` |
| `shop_sale` | The player sold a drop to a vendor | `item`, `count`, `emeralds`, `vendor` |
| `fountain` | A dead end fountain was drunk | `boon` |
| `diary_handed` | A diary was handed to Lemon | `band`, `count` (diaries she now holds) |
| `room_scan` | The Home room as the player left it | `stations`, `containers`, `items` |
| `kit_refill` | Superseded 2026-10-05 (dungeon structure W5, D14): no longer emitted, the refill is gone. Was: the bag chest refilled with a fresh full kit on a trip home | `bag`, `kit` and `overwritten` (each `items` and `gear`, as in `inventory_snapshot`) |
| `kit_topup` | Kit top-up applied (superseded; no longer emitted, the top-up is gone) | `band`, `granted` (object item id to count), `tools_replaced` (array) |
| `inventory_snapshot` | A floor clear, a bank, an exit or a checkpoint exit (playtest 2026-10-03-2) | `trigger`; `pack`, `run_storage`, `ender_chest`, `kept` (each `items`: item id to count, and `gear`: `item`, `left`, `max`, optional `tier`, `enchants`, `kit`); `survival_stashed` (stack count only) |
| `shop_purchase` | A Store sale (2026-10-04, PD-142) | `item` (item id), `name` (the listing's name), `price`, `currency` (item id), `vendor` (the villager's name) |
| `quit_floor` | `/dungeon quit` (a failed dungeon for the haul since 2026-10-08) | `penalty` (always 0 now; the compass is untouched) |
| `ordeal` | An Ordeal room is resolved (its lever pulled, or its objective met), written for each player in the room | `ordeal` (`rising_lava`, `collapsing_bridge`, `thicket`, `ice_run`, `hold_the_plate`), `seconds` since the room was armed |
| `salvage` | The Salvage button at the salvage bench pays out | `gear`, `mob_gear` (counts taken); `xp` and `materials` (object item id to count; paid). Keys are refused at the bench now |
| `run_failed` | The fifth death (no lives left) fails the dungeon for the whole party | `cause` (damage type id), `floor_omen`, `interval_omen`, `floor`, `slot` |
| `owner_hold` | Owner reconnect grace starts, resumes or expires | `state`: `start`, `resume`, `expire` |
| `checkin` | Player answers the in-game check-in | `prompt` (`after_bank`, `after_quit`, `after_session`), `score` (1 to 5, or null if skipped), `comment` (string, may be empty) |
| `report` | `/dungeon report <text>` or the check-in's "something broke" | `text`, `pos` (`"x,y,z"`), `room`, `recent` (last 10 `ev` names for this player) |
| `error` | The mod logs an error while this player is in an instance | `message` (first line only) |
| `lemon_ask` | The player says something to Lemon (written once it is answered, or not) | `text`, `room`, `answered_by` (`guide`, `llm`, `none`), `hint_tier` (0 if not a hint), `wait_s` (seconds until answered) |
| `lemon_hint` | Lemon offers a hint unprompted | `trigger` (`dwell`, `rescues`, `wandering`), `room`, `tier` |
| `lemon_tutorial` | A tutorial moment fires | `moment` |
| `lemon_heard` | A party member said something in chat (written for each other online member, 2026-10-03) | `from` (the speaker's name), `text` |
| `lemon_answer` | The player answers a question Lemon (the interviewer) asked | `question`, `text` |

`room` is a room id from the floor's cell to room map (the placed room's manifest
name, for example `pocketdungeons:hold_the_plate`), one of the pseudo-rooms
`safe_room`, `staging_room` and `preview`, or `""` anywhere else. Two cells can
place the same room; `room_entered` carries the `cell` to tell them apart.

How the events are sourced, where it is not obvious: `leave_dungeon`'s `reason` is
`disconnect` for a logout inside and otherwise comes from the code path that moved
the player (`other` when none said); `session_leave`'s `quit` is a connection the
client closed, which a crash can also look like; `omen_rise` is written for every
member present, with the room the rise came from (or each member's own room for a
floor-wide source); `error` is written for every player in an instance at the time.
`lemon_hint` and `lemon_tutorial` come with guide mode and are not written yet.

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
