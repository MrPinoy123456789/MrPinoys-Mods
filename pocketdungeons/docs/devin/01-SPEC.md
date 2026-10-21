# Spec: one campaign per party, the terminal, and glyphs

Approved by the owner on 2026-10-21. The decision record is the last section of `docs/DECISIONS.md`. This file is
the build spec: every word, colour, position and pixel a player sees is decided here. **Build it as written.** Where
this spec does not say, follow the nearest existing pattern in the code and note it in your report. Do not invent new
player-facing words; if a string you need is missing, use a placeholder `TODO(owner)` and list it in the report.

Contents:

1. Shared progression (crew model)
2. Leader reconnect grace
3. Glyphs (the generalized object)
4. The astrolabe as a glyph
5. Act data: story, crest, colours, window art, rewards
6. Act rewards: earning and delivery
7. The terminal (full UI spec)
8. Text style rules

---

## 1. Shared progression (crew model)

The party plays **the leader's campaign** (`record.owner`). A guest's own campaign never moves during a run they join.

### 1.1 What is campaign progress (leader only)

Every write of these, during or at the end of a run, goes to `record.owner` and nobody else:

- `DungeonLog.addDungeonFinished` (today called for every member in `RunLifecycle.finishDungeon`, around line 1605)
- `DungeonLog.unlockAct`, `markCampaignComplete` (inside `DungeonProgress.evaluate`)
- the prologue flag (`DungeonProgress.PROLOGUE_KEY` via `setTaskProgress`)
- Endless Mine depth (`campaign().mineDepth()`; find where it is written on a mine floor clear)

Search for every other write into `entry.campaign()` and `dungeonsFinished` reachable from a run and apply the same
rule. List each call site you changed in the report.

### 1.2 What stays personal (every member, as today)

Haul, scrap banking, emeralds, the vault, diary pages, storage, the compass item and its purchases. Keep these per
member exactly as now, with one fix:

**First finish bonuses.** `finishDungeon` uses `first` (the return of `addDungeonFinished`) to decide the full emerald
rate, the vault and the diary page. Guests no longer get `addDungeonFinished`, so without a fix they would earn
"first finish" forever. Add a per player set `finishBonusClaimed` (codec key `finish_bonus_claimed`, default empty) in
`DungeonLog`, with `boolean claimFinishBonus(UUID, String dungeonId)` returning true the first time. Use it for every
member, the leader included, in place of `first` for those three bonuses. Migration: when an entry is read and
`finish_bonus_claimed` is absent, seed it with a copy of `dungeonsFinished`.

### 1.3 Act evaluation and the compass line

- Call `DungeonProgress.onProgress` for the leader only. If the leader is offline (in grace), record the finish on
  their save anyway and run `onProgress` for them on their next join (add a join hook if none runs it today).
- The compass line already reads the leader's quest line for every member (2026-10-19). Keep repainting every online
  member's compass after progress, as `onProgress` does now.
- The act opened chat line and milestone title go to **every member present**, not just the leader, because it is
  the party's act. Keep the words exactly as today (`"<Act label> is open. Its dungeons now appear at the first door."`).

### 1.4 Door difficulty

Door offers already come from the leader (`HallOffers.build(server, owner, ...)`). Change the Endless Mine dive
compass in `EndlessMineRules` (the "lowest among the party" helper near line 158 to 169) to the leader's
`highestCharts`. Update its javadoc.

### 1.5 Tests

- Gametest: a two member run finishes a dungeon; the leader's `dungeonsFinished` gains it, the guest's does not; both
  get the finish bonus once; a second finish of the same dungeon pays both the repeat rate.
- Gametest: an act completes on the leader's save; the guest's `actsUnlocked` is unchanged.
- Pure test for the `finish_bonus_claimed` migration seed.

---

## 2. Leader reconnect grace

- `ownerReconnectGraceSeconds` default 120 becomes **600** (`PocketDungeonsConfig.java:75`). Keep the bounds.
- `RunLifecycle.watchOwnerGrace` today lifts the hold when `record.members.isEmpty()` and lets the run wait. New rule:
  **while the leader is away in grace, if every other member has left the dungeon, end the run now** with the same
  ending the deadline uses.
- **A leader alone in their run** keeps today's behaviour exactly (U8 "disconnecting is free"): the run waits and
  they resume where they left off. No timer.
- **Nobody starts the next floor while the leader is away** (owner, 2026-10-21). In
  `RunLifecycle.checkCommitGates`, after the `PartyDecide` check: if the leader is not in `record.members`
  (in grace, or away for any reason), refuse the commit. Guests may still look inside doors (preview), walk around,
  bank and leave; only committing a door is held. Refusal: `Chime.refused` and an action bar line, yellow:
  `Waiting for <Name>. The next floor starts when they return.`
- Words. Replace the announcement in `holdForOwner`:
  - start (chat, every member): `<Name> lost their connection. The run waits 10 minutes for them.` (format the minutes
    from the knob: `N minutes`, or `N seconds` under 60)
  - at 5 minutes left and 1 minute left, action bar to every member: `<Name> has 5 minutes to return` (yellow)
  - expiry and the new early close (chat): `<Name> did not return. The run is over.`
  - the existing "is back" line stays.
- Gametest: leader drops with one guest inside, guest leaves, the run ends within one watch tick.
- Gametest: leader drops with one guest inside; the guest's door commit is refused and the phase does not change;
  the leader rejoins and the commit is accepted.

---

## 3. Glyphs (the generalized object)

A glyph is **something you can see and something that happens when you right-click it.**

### 3.1 Shape

```
Glyph
  anchor   : one minecraft:interaction entity (the click box); carries the GlyphData attachment
  visuals  : 0..n display entities (item_display, text_display, block_display) sharing the glyph's group tag
  GlyphData: handler id (Identifier) + data (CompoundTag), persisted on the anchor
```

- New classes: `Glyph` (spawn, find, restyle, remove), `GlyphHandler` (functional interface),
  `GlyphHandlers` (registry), `GlyphContext`, `GlyphResult`.
- **Persistence:** store `GlyphData` on the interaction entity with the Fabric Data Attachment API
  (`AttachmentRegistry` with a persistent codec). Do not add a mixin. If the attachment API is not available on this
  Minecraft version, stop and report rather than inventing storage.
- **Tags:** every glyph entity carries `pocketdungeons_glyph` plus a group tag `pocketdungeons_glyph_<uuid>` so the
  anchor finds its visuals. Owners of a glyph (the terminal, the astrolabe) add their own tag too, for bulk cleanup,
  the way `HallRoom.TAG` works today.
- **Interaction boxes are square** in plan (one `width` for x and z). A wide button is a row of 1.0 boxes that share
  one handler, not one wide box.

### 3.2 Dispatch

One `UseEntityCallback` registered in `PocketDungeonsMod` init: if the entity has `pocketdungeons_glyph` and a
`GlyphData`, run it; else `PASS`. Main hand only, server side only (return `SUCCESS` on the client side as
`HallRoom` does). A per player cooldown of 4 ticks stops double fires. An unknown handler id logs one warning per id
and plays `Chime.refused`; it never throws. Move the astrolabe's existing callback into this (section 4).

### 3.3 Handler API

```java
@FunctionalInterface
interface GlyphHandler { GlyphResult use(GlyphContext ctx); }

record GlyphContext(ServerPlayer player, ServerLevel level, ItemStack held, boolean sneaking,
                    @Nullable InstanceRecord record, Glyph glyph, CompoundTag data) {}
```

`GlyphResult` is a small builder; the dispatcher applies it in this order: `consume(n)` from the held stack, `refuse`,
`overlay(Component)` to the clicker, `sound(SoundEvent)` at the glyph, `restyle(...)`, `vanish()`, `then(id, data)`
(runs another handler). `GlyphResult.PASS` does nothing.

Register handlers by id in `GlyphHandlers` at mod init: `GlyphHandlers.register("pocketdungeons:terminal_tab", ...)`.

### 3.4 Ready-made handlers

| Id | Data | Does |
|---|---|---|
| `pocketdungeons:require_item` | `item` (id), `count` (default 1), `label` (the item's player name), `then`, `then_data` | Held item matches: consume `count`, sound `minecraft:block.vault.open_shutter`, 12 `minecraft:end_rod` particles, vanish, run `then`. Else: refuse with overlay `Needs a <label>` (yellow) and shake the icon (see 3.6) |
| `pocketdungeons:cycle` | `key`, `values` (list), `then` | Advances `record.glyphState[key]` to the next value (previous when sneaking), then runs `then` |
| `pocketdungeons:toggle` | `key`, `then` | Flips `record.glyphState[key]` between `"on"` and `"off"`, then runs `then` |
| `pocketdungeons:run_once` | `then`, `then_data` | Runs `then`, then vanishes |
| `pocketdungeons:inspect` | `text` (a serialized Component) | Overlay `text` to the clicker. Used by reward icons |

`record.glyphState` is a new `Map<String, String>` on `InstanceRecord`, in memory like the rest of the record.

### 3.5 Admin command for live checks

Under the existing `/dungeon admin` tree in `DungeonCommands.java`: `glyph require <item>` spawns a `require_item`
glyph 2 blocks in front of the caller (icon = the item, label = its name, `then` = none). This is how the owner tests
the vault key idea live. Op only.

### 3.6 The standard item glyph look

Used by `require_item` and the admin command:

- icon: `item_display`, the item, `billboard: center`, scale 0.6, brightness `{block:15, sky:15}`, at anchor + 1.3 y
- label: `text_display` below it at anchor + 0.95 y, scale 0.8, white, shadow, `billboard: center`, the label text
- click box: interaction width 0.8, height 1.6, at the anchor
- shake on refuse: three interpolated translations of the icon, x +0.06, -0.06, 0, two ticks each
  (`interpolation_duration` 2)

### 3.7 Tests

Gametests: `require_item` refuses the wrong item and leaves it in hand; accepts the right one, consumes one, and
removes every entity of the glyph; a glyph survives a save and reload of its chunk with its handler and data intact
(use the attachment round trip); an unknown handler id is refused without an exception.

---

## 4. The astrolabe as a glyph

- Rebuild `HallRoom.placeAstrolabe` from glyph parts: the clock icon and the act text become the glyph's visuals, the
  interaction box its anchor, handler `pocketdungeons:turn_astrolabe` (no data). Same positions, scales and text as
  today. Keep `HallRoom.TAG` on all of them so `clearAstrolabeEntities` still sweeps them.
- `turn_astrolabe` runs today's `HallRoom.turn(player, sneaking)` **without the owner check** (delete the
  `"The trip owner chooses."` branch). Everything else in `turn` stays (`RunSession.canChooseDoor`, "Only one act is
  open.").
- After a successful turn, set the terminal's act page to the act the astrolabe now shows and refresh the terminal
  (section 7.6).
- Remove the old `UseEntityCallback` from `HallRoom`.
- Update `HallRoomGameTest` (a guest can now turn it).

---

## 5. Act data: story, crest, colours, window art, rewards

New data type `data/pocketdungeons/act_reward/act_<n>.json` for n = 0 to 5, loaded with the other content
(follow how `dungeon_bag` or `dungeon_theme` JSON is loaded and validated; add validation to `PackValidator`).

```json
{
  "act": 1,
  "colour": "white",
  "crest": "minecraft:iron_pickaxe",
  "story": ["These rooms are Steve's first days, laid down wrong.",
            "His first mine. The night he lost the stone house."],
  "window": ["GLLLLLGG", "LGGBGGLG", "GGBGGGGO", "GBGGOGGG", "BGGGGGGG", "GGOGGGGG"],
  "rewards": {
    "title": "Delver",
    "disc": "minecraft:music_disc_13",
    "decor": [{"item": "minecraft:lantern", "count": 4}],
    "shell": "old_mine"
  }
}
```

`colour` is a `ChatFormatting` name. `window` is 6 rows of 8 letters, top row first, left to right as the viewer sees
it; letters map to stained glass in 5.3. `shell` is optional.

### 5.1 The six acts

Act names come from `ActProgress.name` and are not repeated here. Story lines are the owner approved draft; ship them
exactly. Two lines per act; each may wrap to two rows on the board.

| Act | Colour | Crest | Story line 1 | Story line 2 |
|---|---|---|---|---|
| 0 | `gray` | `minecraft:recovery_compass` | Lemon came out of the compass alone. | Alex followed it into the dark. She has not come back. |
| 1 | `white` | `minecraft:iron_pickaxe` | These rooms are Steve's first days, laid down wrong. | His first mine. The night he lost the stone house. |
| 2 | `aqua` | `minecraft:echo_shard` | Deeper down, the memories hold together. | He found his first diamond here. The echo never stopped. |
| 3 | `dark_aqua` | `minecraft:heart_of_the_sea` | He went to sea once. The sea remembers him. | Something swims in the tiled pools. You will not see it. |
| 4 | `red` | `minecraft:blaze_rod` | Fire, and the places he swore he would not go back to. | A keep stands where the map burned. |
| 5 | `light_purple` | `minecraft:ender_eye` | Every road ends at the same door. | He went in to fix the world. Something else walked out. |

### 5.2 Rewards per act

Act 0 has no rewards (`"rewards": {}`).

| Act | Title | Disc | Decor set (one bundle) | Shell |
|---|---|---|---|---|
| 1 | Delver | `music_disc_13` | lantern 4, chain 8, barrel 2, oak_log 8, cobweb 2 | `old_mine` (new, 5.4) |
| 2 | Deepwalker | `music_disc_5` | soul_lantern 4, sculk 8, deepslate_tiles 16, candle 4 | none |
| 3 | Tideworn | `music_disc_ward` | sea_lantern 4, prismarine_bricks 16, dark_prismarine 8, sea_pickle 4 | none |
| 4 | Ashbound | `music_disc_pigstep` | shroomlight 4, blackstone_bricks 16, gilded_blackstone 2, soul_campfire 1 | none |
| 5 | Voidsworn | `music_disc_11` | end_rod 6, purpur_block 16, end_stone_bricks 16, dragon_head 1 | none |

All ids are `minecraft:`. A bundle may hold at most 64 weight; split into two bundles if a set ever exceeds it.

### 5.3 Window art (8 by 6, viewer's view, top row first)

Letter to block: `K` black, `W` white, `L` light_gray, `G` gray, `B` brown, `O` orange, `Y` yellow, `R` red,
`N` pink, `M` magenta, `P` purple, `U` blue, `A` light_blue, `C` cyan, `E` green, `I` lime, each `<colour>_stained_glass`.

```
Act 0 (night, moon, tree)   Act 1 (pickaxe, ore)       Act 2 (diamond, sculk)
UUUUUWYU                    GLLLLLGG                   KKKKKKKK
UWUUUYYU                    LGGBGGLG                   KKKAAKKK
UUUEUUUU                    GGBGGGGO                   KKACCAKK
UUEEEUUW                    GBGGOGGG                   KCKAAKCK
UUUBUUUU                    BGGGGGGG                   KKCKKCKK
EEEBEEEE                    GGOGGGGG                   CKKKKKKC

Act 3 (monument eye)        Act 4 (keep in fire)       Act 5 (end portal)
AAUUUUAA                    KRKKKKRK                   KYKPKKWK
ACCCCCCA                    KKKBBKKK                   KKPMMPKK
UCWWWWCU                    RKBBBBKR                   PMKKKKMP
UCWKOWCU                    KKBYYBKK                   PMKYYKMP
ACCCCCCA                    ORBBBBRO                   KKPMMPKK
UUAAAAUU                    RORROORO                   KWKKPKYK
```

### 5.4 The `old_mine` shell (Act 1 reward)

A new `RoomBuilder.ShellPalette` built with the existing `palette(...)` helper: name `old_mine`, display name
`Old Mine`, unlock hint `Finish Act 1`, floor `spruce_planks`, wall `cobblestone`, ceiling `spruce_planks`, ceiling
slab `spruce_slab`, stair `spruce_stairs`. It must show in the room's shell menu as locked until earned, the way the
other locked shells do (`DialogScreens` around line 1177).

---

## 6. Act rewards: earning and delivery

### 6.1 When

An act completes when `DungeonProgress.evaluate` opens act n+1 on the leader's save (or marks the campaign complete,
for act 5), and when the prologue flag is set (act 0, no rewards). At that moment, act n's rewards go to **every member
in `record.members` who is online**, the leader included, **once per player per act**: a new per player set
`actRewardsClaimed` (codec key `act_rewards_claimed`).

**Back pay:** on join, for every act a player's own save shows complete (act n+1 in `actsUnlocked`, or campaign
complete for act 5) and not in `actRewardsClaimed`, pay it now.

### 6.2 What is delivered

All items through `Payout.deliver` (which applies the caps). Read `Payout.deliver` first: if it can drop items at the
player's feet when the inventory is full, keep that.

| Reward | Item | Name (italic off) | Lore lines (gray) |
|---|---|---|---|
| Disc | the act's disc | `<Act name>` in the act colour | `Act <n>` |
| Window | a `minecraft:bundle` holding the window's glass, one stack per colour, counts from the 48 pixels | `<Act name> Window` act colour | the 6 pattern rows (5.5), then `Build it on a wall of your room.` |
| Decor | a `minecraft:bundle` with the set | `<Act name> Decor` act colour | `Act <n>` |
| Title | no item; added to a per player set `titlesEarned` | | |
| Shell | no item; added with the existing shell unlock path (`withUnlockedShells`) | | |

### 6.3 Titles

- Shown as a scoreboard team prefix, so it shows over the head and in the tab list: `◆ <Title> ` with `◆` in the act
  colour and the title in the act colour, then a reset so the name keeps its colour.
- One team per act: `pd_title_<n>`. A player is on the team of the **highest act** whose title they hold.
- Applied on join and when a title is earned.
- If the player is already on a team whose name does not start with `pd_title_`, leave them alone (another feature
  owns their team) and log it once.

### 6.4 What the party sees and hears

- Title (via `StaggeredTitle` like the act opened title): `<Act name> complete` in the act colour, subtitle
  `4 rewards in your inventory` (count what was delivered; `Rewards in your inventory` if the count is not known).
- Chat log line per reward, gray: `+ Old Mine shell`, `+ Title: Delver`, `+ First Iron (music disc)`,
  `+ First Iron Window`, `+ First Iron Decor`.
- Sound: `minecraft:ui.toast.challenge_complete` to each receiver.

### 6.5 Tests

Gametest: two members present at an act completion both get the items and the title once; a second completion pays
nothing; a player with an act already complete before the change is back paid once on join. Pure test: the act JSON
loads for acts 0 to 5 and every window has 6 rows of 8 valid letters.

---

## 7. The terminal (full UI spec)

The terminal replaces the floor history board on the same wall: `RoomGeometry.leftOf(selectorWall)`, in every staging
room that has the board today. New class `Terminal`. `FloorHistory.board` stays and becomes the History tab.

### 7.1 Coordinates

Everything below uses **viewer coordinates** for a player standing in the room facing the terminal wall:

- `u`: blocks from the terminal's left edge, 0 to 12, left to right as the viewer sees it.
- `v`: blocks above the room floor (`o.y`), 1 to 6. `v = 6` is the ceiling.
- Column `c` (1 to 12) is the wall block spanning `u = c-1 .. c`. Column `c` is wall `along = c + 1`, converted for
  the viewer with `RoomGeometry.viewerAlong` (the history panel never needed this because it was symmetric; the
  terminal is not).
- Row `y` (1 to 5) is the wall block spanning `v = y .. y+1`.
- Entities sit in front of the wall face: text displays 0.10 in front (as `DungeonScreen.wallAnchor` does), item and
  block displays 0.06 in front, interaction anchors 0.5 in front. All face into the room (`yawFor(wall)`).

Text anchoring: a text display's position is the **bottom centre** of its text block. Where the spec says "left edge at
u", use `alignment: left`, set `line_width` in pixels, and centre the entity at `u + width/2`. One text pixel is
`0.025 * scale` blocks.

### 7.2 Wall blocks (always, every tab)

```
          c1  c2  c3  c4  c5  c6  c7  c8  c9  c10 c11 c12
 y5 (v5)  K   K   K   K   K   K   K   S   S   S   S   K
 y4 (v4)  K   K   K   K   K   K   K   S   S   S   S   K
 y3 (v3)  K   K   K   K   K   K   K   S   S   S   S   K
 y2 (v2)  K   K   K   K   K   K   K   K   K   K   K   K
 y1 (v1)  T   T   T   T   T   T   T   T   T   T   T   T
```

- `K` black concrete (the existing `SCREEN_BLOCK`).
- `S` window backlight: `minecraft:sea_lantern` when the Objectives tab shows an **open** act; black concrete
  otherwise (a locked act, or any other tab).
- `T` tab bar: `minecraft:polished_blackstone`.
- Replace `StagingFurniture.placeHistoryPanel` and `isHistoryPanel` (along 4 to 11, y 2 to 5) with this 12 by 5
  area (along 2 to 13, y 1 to 5). `clearFurniture` must restore every one of these blocks to the room's shell wall,
  and `RoomProtection` must treat them as unbreakable as it does the panel. **Check first** that nothing else uses
  along 2, 3, 12 or 13 on this wall (bulbs, shell windows); report what you find before moving anything.

### 7.3 Tab bar (row y1, every tab)

Three tabs, each 4 columns wide:

| Tab | Columns | Label | Label centre (u, v) |
|---|---|---|---|
| Objectives | c1 to c4 | `OBJECTIVES` | (2.0, 1.32) |
| Progress | c5 to c8 | `PROGRESS` | (6.0, 1.32) |
| History | c9 to c12 | `HISTORY` | (10.0, 1.32) |

- Labels: text display, scale 1.2, shadow on, bold. Selected tab: gold. Others: gray.
- The selected tab also gets an underline above it: text `▬▬▬▬▬▬▬▬▬▬` gold, scale 0.7, bottom centre at (tab centre,
  1.80).
- Each tab is a glyph with 4 interaction boxes (width 1.0, height 1.0, one per column, bottom at v 1.0), handler
  `pocketdungeons:terminal_tab`, data `{tab: "objectives" | "progress" | "history"}`.
- Clicking the selected tab does nothing. Clicking another: set the shared tab, play
  `minecraft:ui.button.click` (volume 0.5) at the terminal, redraw.

### 7.4 Objectives tab

Shows one act: the **page act** (7.6). Mock up at 1 character per 0.25 blocks (not to scale):

```
 ┌─────────────────────────── c1 .. c7 ───────────────────────────┬──── c8 .. c11 ────┬─c12─┐
 │ [crest]  ACT 1                                                  │                   │     │
 │          First Iron                                         ◀   │   stained glass   │  ▶  │
 │      ━━━━━━━━━━━━━  ◆  ━━━━━━━━━━━━━                             │   window 8 x 6    │     │
 │ ◆ These rooms are Steve's first days, laid down wrong.          │                   │     │
 │ ◆ His first mine. The night he lost the stone house.            │                   │     │
 │ ◆ Next: Lush Caves                                              ├───────────────────┤     │
 │                 REWARDS                                         │ (1) (2) (3) (4) (5)│     │
 │   [shell] [title] [disc] [window] [decor]                       │      act pips      │     │
 └─────────────────────────────────────────────────────────────────┴───────────────────┴─────┘
   OBJECTIVES              PROGRESS                HISTORY
```

Exact elements (u, v are entity positions as defined in 7.1):

| Element | Kind | Position | Scale | Style | Content |
|---|---|---|---|---|---|
| Crest | item_display | centre (0.75, 5.40) | 0.8 | brightness 15 | act `crest` |
| Act number | text | left edge u 1.35, bottom v 5.50 | 2.0 | bold, act colour | `ACT 1` (`PROLOGUE` for act 0) |
| Act name | text | left edge u 1.35, bottom v 5.08 | 1.3 | gold | `ActProgress.name(act)` |
| Divider | text | centre u 3.15, bottom v 4.80 | 0.8 | `━` dark gray, `◆` gold | `━━━━━━━━━━━━  ◆  ━━━━━━━━━━━━` |
| Lines | text | left edge u 0.3, top v 4.62 | 1.1 | see below | 3 lines, no extra gap |
| Rewards heading | text | centre u 3.15, bottom v 2.82 | 0.9 | white, bold | `REWARDS` |
| Reward icons | item_display | centres along v 2.45, u = 3.15 + (i - (n-1)/2) * 0.95 | 0.5 | see below | in the order of 7.4.2 |
| Reward ticks | text | bottom right of each earned icon: (icon u + 0.22, v 2.12) | 0.6 | green | `✔` |
| Left arrow | text | centre (6.5, 4.25) | 2.2 | white if a page exists to the left, dark gray if not | `◀` |
| Right arrow | text | centre (11.5, 4.25) | 2.2 | same rule | `▶` |
| Window | block_display grid | covers c8 to c11, y3 to y5 (u 7 to 11, v 3 to 6) | each pixel 0.5 by 0.5, depth 0.04 | see 7.4.3 | act `window` |
| Act pips | item_display | centres along v 2.5, u = 7.4, 8.2, 9.0, 9.8, 10.6 for acts 1 to 5 | 0.4, the page act 0.55 | open: brightness 15; locked: brightness 3 | each act's `crest` |

**7.4.1 The three lines** (each starts with `◆ ` in white):

- open act: line 1 and line 2 are the act's `story`, gray. Line 3 is the next goal, from the leader's quest line
  (`QuestLine.lines`, first line): `Next: ` in white and the rest in gold, e.g. `◆ Next: Lush Caves`. If the act is
  complete, line 3 is `◆ Act complete` in green.
- locked act (no spoilers): one line only, gray: `◆ Finish Act <n-1> to open.` (for act 1: `◆ Finish the First Night
  to open.`)
- act 0: the story lines, then `◆ Next: The First Night` (from the quest line).

Wrap at `line_width` 207 px (5.7 blocks at scale 1.1; the pane runs u 0.3 to 6.0, centre 3.15). Story lines
wrap to two rows each; five rows fit between v 4.62 and the rewards heading. The history rows (scale 1.03) were
enlarged three times after playtests, so nothing a player must read on the terminal is smaller than 1.0. If any line wraps, the lines below move down by one line height.

**7.4.2 Rewards row** (hidden for act 0): icons in this order, skipping what the act does not give:

| Reward | Icon item | Right-click (handler `inspect`) |
|---|---|---|
| Shell | the shell's wall block (`cobblestone` for Old Mine) | `Room shell: Old Mine` |
| Title | `minecraft:name_tag` | `Title: ◆ Delver` (◆ and title in the act colour) |
| Disc | the disc | `Music disc: First Iron` |
| Window | `<main colour>_stained_glass`, where main colour is the window's most used letter that is not `K` | `First Iron Window` |
| Decor | the first item of the decor set | `First Iron Decor` |

Earned means: the **viewer** cannot be told apart on a shared board, so the tick shows when the **leader** holds that
reward (`actRewardsClaimed` contains the act). Earned icons: brightness 15. Not earned: brightness 5. Inspect text is
the same for everyone. Each icon is a glyph (interaction width 0.6, height 0.6).

**7.4.3 The window**

- 48 block_displays (8 columns by 6 rows), each `<colour>_stained_glass`, scaled (0.5, 0.5, 0.04). Pixel (px, py),
  px 0 to 7 left to right, py 0 to 5 top to bottom, covers u `7 + 0.5*px .. 7.5 + 0.5*px`, v `6 - 0.5*(py+1) ..
  6 - 0.5*py`.
- Open act: the backlight blocks are sea lanterns and every pixel has brightness `{block:15, sky:15}`.
- Locked act: backlight black concrete; every non `K` pixel becomes `gray_stained_glass` and every `K` pixel
  `black_stained_glass`, brightness `{block:4, sky:4}`. The shape reads as a dark silhouette.

**7.4.4 Arrows**

Each arrow is a glyph (interaction width 1.0, height 1.0, centred on the arrow), handler `pocketdungeons:terminal_page`,
data `{dir: -1}` or `{dir: 1}`. The pages run act 0 (only while the leader has not finished the prologue), then acts 1
to `Campaign.releaseMaxAct()`. Locked acts are pages too (that is how the dark windows are seen). At an end the arrow is
dark gray and a click plays `Chime.refused`. A page turn plays `minecraft:item.book.page_turn` and redraws.

### 7.5 Progress tab

The window area is black (no glass, no pips, no arrows). Two columns across the whole screen (rows y2 to y5):

```
 MINESHAFT                                 ACT 1   First Iron
 Floor 3 of 5                              ✔ Mineshaft
 ◆━◆━◆━◇━◇                                 ◆ Lush Caves
                                           ◆ Infestation          Compass 4
 ❤ 3 lives                                  ◆ Kennels
 Haul 12 scrap                             ★ Spawner Dungeon
                                           ⛏ Endless Mine         floor 2 of 3
                                           3 of 7 done
```

Left column, "this dungeon", left edge u 0.4:

| Element | Bottom v | Scale | Style | Content |
|---|---|---|---|---|
| Dungeon name | 5.40 | 1.4 | bold, act colour | the current dungeon's name, upper case |
| Floor line | 5.02 | 1.0 | white | `Floor <n> of <total>` (reuse `FloorHistory.progress` logic for the numbers) |
| Track | 4.35 | 1.6 | done `◆` green, current `◆` gold, ahead `◇` dark gray, joints `━` dark gray | one glyph per floor |
| Lives | 3.30 | 1.0 | `❤` red, text white | `❤ <n> lives` (find where the sidebar reads lives and use the same source; `❤ 1 life` for one; hide in act 0) |
| Haul | 2.90 | 1.0 | aqua | `Haul <n> scrap` (the leader's current haul, the same number the sidebar shows) |

Right column, "this act", left edge u 6.4, right edge u 11.8:

| Element | Bottom v | Scale | Style | Content |
|---|---|---|---|---|
| Heading | 5.40 | 1.2 | `ACT 1` bold act colour, name gold | `ACT 1   First Iron` |
| Rows | from top v 5.0 down, 0.30 apart | 1.1 | see below | one row per dungeon of the act, in the hall's row order (`HallLayout`), then the Endless Mine |
| Footer | 2.25 | 1.0 | gray | `<done> of <total> done` |

Row style: finished `✔ <name>` green. Open and not finished `◆ <name>` white. The capstone uses `★` (gold) in place
of `◆` or `✔`. Locked by compass: `◆ <name>` dark gray with the right column text `Compass <n>` yellow, right aligned at
u 11.8 (use `QuestLine.lockedSign` for the number). Endless Mine row: `⛏ Endless Mine` white, right text
`floor <depth> of <target>` gray (`ActProgress.mineTarget`), green with `✔` when the act's mine leg is done. If the
rows do not fit (more than 8), drop the scale to 0.9 and the spacing to 0.25.

The act shown is the dungeon's act during a run; in the hall (first staging room) it is the page act.

### 7.6 History tab

The window area is black. Show `FloorHistory.board(server, record.owner)` exactly as today (heading `FLOOR HISTORY`,
the same scales and columns), centred at u 6.0. The old heading and body Y constants in `DungeonScreen` stay valid.

### 7.7 Shared state and refresh

- On `InstanceRecord`: `terminalTab` (default `objectives`) and `terminalAct` (default: in the hall, the act the
  astrolabe shows; in a run, the dungeon's act).
- Every member sees the same terminal; any member may click.
- Redraw when: the tab or page changes, the astrolabe turns (set `terminalAct` to the shown act first), a floor is
  recorded (where `FloorHistory.refresh` runs today), progress or rewards change, and whenever the room is stamped
  (the terminal is transient like today's screens; it is never captured).
- Redraw clears only the terminal's entities (tag `pocketdungeons_terminal`) and resets the `S` blocks; it never
  touches other screens. `DungeonScreen.updateHistory` and `summonHistory` become calls into `Terminal`.

### 7.8 Tests

Gametests: the terminal places all 60 wall blocks and clears them back to the shell wall; a guest clicking
`PROGRESS` changes the tab for the leader too; the right arrow at the last act is refused; an open act lights the sea
lanterns and a locked one does not; the History tab still shows the leader's floors (`FloorHistoryGameTest` keeps
passing). Source scan test: no terminal string contains an em dash or ` -- `.

---

## 8. Text style rules (for anything not spelled out above)

- Say what to do, not what happened. Few words. No system words: the player words are compass, scrap, haul, lives,
  charts, party leader.
- Colour carries meaning: gold for "this is where you are", green done, yellow compass, aqua what you carry, red
  danger, gray everything else, the act colour for the act's own name.
- No em dashes and no ` -- ` anywhere, including comments and commit messages.
