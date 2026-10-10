# Reply to design brief 2026-10-09-1

Answers `docs/design-2026-10-09-1.md`. Design only: no code. Every number below is a proposal and a knob.
Where the code was read to ground an answer, the file is named; where something was not checked, it says so.

## 1. Summary

1. **Q1 picker:** no menu. The first staging room becomes the **Astrolabe Room**: turn the astrolabe in the centre to cycle unlocked acts; the back wall is glass with up to six 1 by 2 selector doors (`OXOXOXOOXOXOXO`) for that act, copper bulbs on top (oxidized for finished, unlit with an iron door for locked); open a door to see its first room through the glass, pull DESCEND to go. Special doors (Endless Mine, later others) appear along the side walls as they unlock. The random first deal and `/dungeon reroll` retire.
2. **Q2 curve:** a level costs `4 + floor(compass / 3)` scrap (compass 1: 4, 10: 7, 25: 12, 50: 20). A floor pays `step + (act - 1)`, plus 1 on a final floor; below the compass it pays half, at least 1.
3. **Q3 sculk:** one rule, "sculk hears you": each sensor pulse fills the room's Heard meter; when it fills, a shrieker answers with darkness and a wave. A sculk room crossed unheard pays +1 scrap. Replace `sensor_gallery` with the **Hush Gallery** (quiet costs time).
4. **Q4 finale:** every non-capstone dungeon's final room ends in a **finale wave** (BroodWave generalised to JSON); Act 2 and up add a named **elite** with a health bar. Pays +1 scrap (Q2) and +1 reward chest.
5. **Q5 copper:** a per-dungeon `mobUniform` JSON field. Copper Works: melee mobs wear one copper armour piece plus a copper sword; skeletons wear two copper armour pieces. Nothing drops (J7 already zeroes equipment drops).
6. **Q6 toll:** the fault is fiction and readability. `barred_vault` swaps hopper and iron door for a real vanilla **vault block** opened with the trial key. `ominous_bargain` loses its gold toll: taking the chest is the bargain. Frame Lock stays.
7. **Q7 display:** a per-player **scoreboard sidebar** with blank number format: dungeon, floor, lives, spawners, haul, compass bar. Chat stays the log; an audit table moves each critical line to a title, action bar or the sidebar.
8. **Q8 wolves:** Feral retires for **Restless** ("the dead get up once; burn them and they stay down"). New Act 1 dungeon **The Kennels**; a wolf floor **The Hound Crypt** in the Ossuary; a rare generic room **The Lost Dog**. Pets stay, per trip only.
9. **Q9 ore:** `hiddenOre` extends to the Endless Mine only (plus depth scaling); `deep_shaft_landing` rises from 5 to 9 ore nodes; a validator rule stops notes promising ore the palette cannot deliver.
10. **Q11 order:** wave A (display and text), wave B (picker), play, wave C (scrap curve), play, wave D (finales and copper uniform), wave E (sculk and toll rooms), wave F (wolves and Restless), wave G (ore).

## 2. Per question

### Q1 (PD-181): choosing the act and dungeon

**(a) Problem.** "The 3 random doors doesn't really work with the new dungeon act system"; "players should be able to choose the act and dungeon"; his first change: "Redesigning the dungeon selection." Today the deal (`Keystone.offers`, `TripDoors.dealFirst`) can hide the dungeon he wants and push one he finished; the forced capstone door and the Endless Mine on door 3 are patches over that.

**Owner rulings, in order** (2026-10-08 and 2026-10-09):
1. No menu window; use tangible things in the world.
2. The player cycles through their unlocked acts; each act shows a door for every one of its dungeons, all along the wall.
3. Every door is a 1 by 2 door, like today's selector doors.
4. The selection wall is glass, so players see straight into the preview room. The 14 interior spaces read `OXOXOXOOXOXOXO` (O empty, X a door), with a 14 by 3 wall of glass behind them.
5. The room is decorated, with an astrolabe-like fixture in the centre. Special doors that are not part of an act (the Endless Mine, a future Woodland Mansion) are placed around the interior as they unlock. Copper bulbs keep marking doors as selectors.

What exists today and is kept (`RoomTemplateGenerator`): selector doors are 1 by 2 door blocks standing in the door row in front of the wall (Y 1 and 2); their **material is the affix language** (oak: none, crimson: ominous, exposed copper: greater); a copper bulb at Y 3 marks each selector; the door screen (black concrete backdrop and a `text_display`) is Y 4 and 5, blocks 3 to 12; a DESCEND lever with its sign commits, and the 2 wide slot at 7 and 8 is punched open as the way in. The preview floor stamps in the cell behind that slot.

**(b) Options.** The owner's direction is the design; the earlier Chart Floor (a floor map) and Act Hall with teleporting alcoves are superseded by it. Two choices were left open inside it, decided here:

| Choice | Option 1 | Option 2 | Pick |
|---|---|---|---|
| Where a special door previews | its own wall gets glass and a preview cell behind its own slot | every door, act or special, previews in the one cell behind the back glass | **2**: one preview cell, one punch, works whatever the instance layout leaves free beside the room; the back glass is where the eye already goes. |
| What marks a dungeon on its door | door material per dungeon | keep material for affixes (today's language), mark the dungeon with a doormat block and a small name label | **2**: changing what door wood means would break the language players have already learned. |

**(c) Recommendation: the Astrolabe Room.**

**Plan view** (back wall at the top; the 14 interior spaces numbered 1 to 14):

```
             1  2  3  4  5  6  7  8  9 10 11 12 13 14
   Y 4-5  [ ======== door screen, blocks 3 to 12 ======== ]        (the chosen dungeon's card)
   Y 3     .  b  .  b  .  b  .  .  b  .  b  .  b  .           b = copper bulb on top of each door
   Y 1-2   O  X  O  X  O  X  O  O  X  O  X  O  X  O           X = selector door, O = empty
   behind  [ glass, 14 wide, Y 1 to 3, the preview room seen through it ]
   floor   .  m  .  m  .  m  .  .  m  .  m  .  m  .           m = doormat (the dungeon's token block)

   side wall                    ( astrolabe )                    side wall
   special doors                DESCEND lever on its plinth      special doors
                                 ( entrance )
```

**1. The back wall (the act's dungeons).** Glass from wall to wall, 14 wide and 3 high (Y 1 to 3), in the wall ring. In front of it, the door row follows `OXOXOXOOXOXOXO`: doors at spaces 2, 4, 6, 9, 11 and 13, so six dungeons per act at most. Spaces 7 and 8 stay empty because they are the real 2 wide slot: on commit the glass there is punched out as the way in, exactly where today's doorway opens. Behind the glass is the preview cell, so the player sees the chosen dungeon's first room. Before anything is chosen the preview cell is empty and dark; a choice lights it.

Doors fill from the centre outward in the order the act lists them (spaces 6 and 9 first, then 4 and 11, then 2 and 13), so an act with 2 or 3 dungeons sits near the middle, not at one end. Today Act 1 has 5 (the Endless Mine moved to the special doors), Act 2 has 5, Acts 3 to 5 have 2 or 3. `PackValidator` fails an act with more than 6.

**2. Each door.** A door's look carries three things, each in one place:

| What | Where | How |
|---|---|---|
| Which dungeon | the **doormat** (the floor block in front of the door) and a **name label** (small `text_display` on the glass at Y 3, beside the bulb, scale about 0.4) | `token.mat` in the dungeon JSON: copper block for Copper Works, blue ice for Frostworks, bone block for the Ossuary |
| The entry floor's affixes | **door material**, today's language unchanged | oak none, crimson ominous, exposed copper greater |
| Its status | the **copper bulb** on top of the door, plus an iron door when locked | see below |

| Status | Bulb | Door | Label |
|---|---|---|---|
| Not entered | copper bulb, lit | affix material | name, white |
| Finished | **oxidized** copper bulb, lit (a finished dungeon wears patina) | affix material | name ✔, green |
| Capstone ready | copper bulb, lit; gold block doormat border | affix material | name, gold |
| Capstone not ready | bulb unlit | **iron door** (a hand cannot open it in vanilla) | "Finish the act first", grey |
| Locked by compass | bulb unlit | **iron door** | "Compass 12", grey |
| Start here | lit, with end rod particles drifting up the glass behind it | affix material | name, gold |
| Selected | the other bulbs dim; this one stays lit | open | name, and the screen above shows the card |

A lit bulb always means "this is a door you can choose", the same as today.

**3. The astrolabe (turning the act).** A centrepiece in the middle of the room: a lodestone core on a short copper plinth, ringed by two or three rings built from `block_display` entities (chains and cut copper slabs), with an `interaction` entity as its click box. Vanilla display and interaction entities, server side, no client mod.
- **Right click** it: the rings swing (display interpolation, about 20 ticks) to the next unlocked act's alignment and the room changes act. **Sneak and right click** turns back. Owner only; a member gets an action bar line.
- On a turn: the door row, doormats, labels and bulbs are re-stamped for the new act; the preview cell clears; a title shows `Act 2` / `The Deep`; the core's top block and the room's accent blocks (floor inlay ring around the plinth, ceiling trim) swap to the act's palette (Act 1 stone and oak, Act 2 deepslate and copper, Act 3 prismarine, Act 4 blackstone and basalt, Act 5 end stone and purpur). The authored decoration stays; only accents change, so the room keeps its character.
- The act's name hovers over the astrolabe (`Act 2: The Deep`), and around the plinth's base a ring of five small act markers shows the whole campaign: lit for open acts, the current one brighter, locked ones dark with their unlock line on approach (`Clear the Ancient City`).
- The room opens on the act holding the Start here dungeon, so most visits need no turn.

**4. Special doors (non-act).** Doors that belong to no act (the Endless Mine today; a Woodland Mansion or an event dungeon later) stand around the interior: along the two side walls, same 1 by 2 doors, same copper bulb on top, same doormat and label, in front of the decorated wall (no glass). A side wall takes them on the same `OXOXOXOOXOXOXO` rhythm, so each side holds six: twelve specials in all. They appear as they unlock and stay whatever act is on the astrolabe. Choosing one previews it **behind the back glass** like any other door, and DESCEND punches the same back slot. A special that has not unlocked yet is simply not there (no iron placeholders, which would spoil the surprise); the first time one appears, a title says so.

The operator's experimental offer (`ExperimentalDungeon`, today's door 3) becomes a special door too.

**5. Choosing and committing.** Exactly today's verbs:
- **Open a door** (right click): it is selected; the preview stamps in the cell behind the glass (`Instances.previewDoor`) and the player watches the first room appear; the screen above shows the card. Opening another door re-selects (the preview restamps, as switching doors did before).
- **Pull DESCEND**: the lever now sits on the astrolabe's plinth facing the back wall, with its sign, since the glass wall has nowhere to hold it. It commits (`RunLifecycle.commitDoor`), punches the glass at spaces 7 and 8, and the party walks through into the room they have been looking at.

**6. Party.** Any member may open a door; a member's choice shows `Bob wants this` on its label and as an action bar line to the owner. Only the owner's open door is the preview, and only the owner pulls DESCEND. Ominous still rolls at commit.

**The card** on the screen (Y 4 and 5): the dungeon name in its colour; its `notes` line; `4 floors. Compass 8.`; `First finish: vault and diary page` or `Finished. Repeat pays half emeralds.`; the entry floor's affixes. Same `Board` shape as today's door board.

**What stays random:** the entry floor's step and affixes, every later per-floor door deal (`TripDoors.dealNext`, `DoorAffixes`), deviation themes, room draws, the ominous roll. **What stops being random:** which dungeon. `/dungeon reroll` retires (leave `IntervalState.doorReroll` in the codec, unused). The echo shard engine is already gone (J1); "Same as door" is per floor and untouched. The forced Spawner Dungeon first door becomes the Start here marker; knob `hallFunnelNewPlayers` (default off) turns a brand new player's other Act 1 doors to iron until their first finish.

**Start here:** a ready capstone; else the lowest `unlockLevel` unfinished, unlocked dungeon in the highest open act; ties by id.

**Farming the easiest dungeon:** handled by pay, not locks. Q2's below-compass rule halves floor pay; finish emeralds drop to `repeatFinishEmeraldPercent` (50) after a dungeon's first finish; the vault and diary page are first finish only (owner believes the diary does not repeat; confirm the vault in `Payout`).

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| Room | the first staging room only: an authored, decorated template (`astrolabe_room`) with the back wall's glass, the side walls, the plinth and the decor baked; door rows, doormats, labels, bulbs, accents and the astrolabe's display entities placed at stamp from `DungeonDefs` | new `AstrolabeRoom` class; `StagingSpecs` (empty today) gains the template; first staging stamp path in `Instances`; later staging rooms unchanged |
| Door pattern | interior spaces 2, 4, 6, 9, 11, 13 (cell x or z 2, 4, 6, 9, 11, 13 counting the wall as 0); fill order 6, 9, 4, 11, 2, 13; spaces 7 and 8 the punch | constant `AstrolabeRoom.DOOR_SPACES`, `FILL_ORDER` |
| Glass | back wall ring, all 14 interior blocks, Y 1 to 3; punched at 7 and 8 on commit | `AstrolabeRoom`, reusing today's punch |
| Bulb | Y 3, directly on top of each door (in the door row, in front of the glass); copper bulb lit, oxidized lit for finished, unlit for locked | reuse `setBulb` and `clearBulbs` |
| Dungeon JSON | `"token": {"mat": "minecraft:copper_block"}`; plus `"hall": "act"` (default) or `"hall": "special"` with `"unlock": {...}` for non-act doors | `dungeon/*.json`, `DungeonDef`, `PackValidator` |
| Endless Mine | `"hall": "special"`, unlocked by today's `EndlessMineRules.opensFor` | `dungeon/endless_mine.json` |
| Astrolabe | lodestone core, 2 to 3 rings of `block_display` (chain, cut copper slab), an `interaction` entity 1.5 by 2.5; ring alignment per act; owner-only use | `AstrolabeRoom` |
| Act state | `interval.hallAct` on the record | `IntervalState` |
| DESCEND lever | moves to the astrolabe plinth, facing the back wall; sign unchanged | `AstrolabeRoom`, `RoomTemplateGenerator` lever code |
| Select | door open event: owner sets `interval.chosenDungeon` and previews; member records a wish | `Keystone.offers` returns one offer for `chosenDungeon`; `Instances.previewDoor` |
| Protection | glass, doors, bulbs, mats, labels, astrolabe and its entities protected like the shell; iron doors stay shut (no redstone sources placed near them) | `RoomProtection` |
| Repeat finish emeralds | 50 percent after the first finish | config `repeatFinishEmeraldPercent` |
| Knobs | `hallLabelRange` 4, `hallTurnTicks` 20, `hallFunnelNewPlayers` false | `pocketdungeons.json` |
| Journal | `act_turned`, `dungeon_selected` (with `wish` for members), `special_door_unlocked`; `dungeon_chosen` gains `via: "astrolabe"` | `PlaytestJournal` |

**Doormats (Acts 1 and 2; later acts the same way):** Mineshaft oak planks; Infestation mossy stone bricks; Ossuary bone block; Rootworks moss block; Spawner Dungeon mossy cobblestone; Copper Works copper block; Deepslate polished deepslate; Frostworks blue ice; Cow Pits hay bale; Ancient City sculk; Endless Mine (special) cobbled deepslate with a rail on top. Later acts: prismarine, dark prismarine, basalt, gilded blackstone, soul sand, bookshelf, end stone, purpur, obsidian.

**(e) Text.**

| Place | Short (preferred) | Clear |
|---|---|---|
| Astrolabe label | Act 2: The Deep | Act 2: The Deep. Right click to turn. |
| Act title on turn | Act 2 / The Deep | Act 2: The Deep / 5 dungeons |
| Screen before a pick | Open a door. | Open a door to look inside. Pull DESCEND to go. |
| Locked door (action bar) | Compass 12 | Needs compass 12. You have 10. |
| Capstone not ready (action bar) | Finish the act first. | Finish this act's other dungeons to open its capstone. |
| Member wish (label, and action bar to owner) | Bob wants this. | Bob opened Frostworks. |
| Member at the astrolabe or lever | Kris chooses. | Only the trip owner turns the act and descends. |
| New special door (title, once) | A new door / Endless Mine | A new door has opened / The Endless Mine |
| Locked act marker (on approach) | Clear the Ancient City. | Act 3 opens when you clear the Ancient City. |
| Repeat card line | Finished. Half emeralds. | You finished this. A repeat pays half emeralds and no vault. |

### Q2 (PD-182): a scrap curve

**(a) Problem.** "make it so each level requires more scrap than the previous and then make higher level floors reward more scrap appropriately." Today 5 scrap per level everywhere, floors pay 1 to 3 regardless of act.

**(b) Options.**

| | A. Linear step cost, act-added pay | B. Multiplicative (cost and pay both x1.1 per level) | C. Table per act |
|---|---|---|---|
| Player does | Same loop; deeper acts pay visibly more, each level asks a little more. | Same; numbers grow fast. | Same; cost jumps at act boundaries. |
| Why | Small integers, readable on `3/7`. Ratio of pay to cost stays near 2 levels per normal dungeon at the right act. | Smooth in theory, but numbers above 30 per level read badly on a bar. | Very legible, but a flat cost inside an act is the complaint again. |
| Build | Small | Small | Small |

**(c) Recommendation: A.** Integers a player can hold in their head; the formula is two knobs.

**Cost** to go from compass `c` to `c + 1`: `cost(c) = scrapCostBase + floor(c / scrapCostEvery)`, defaults 4 and 3.

| Compass | 1 | 5 | 10 | 15 | 20 | 25 | 50 |
|---|---|---|---|---|---|---|---|
| Cost of next level | 4 | 5 | 7 | 9 | 10 | 12 | 20 |
| Total scrap from 1 | 0 | 18 | 48 | 86 | 133 | 188 | 588 |

(Total from 1 to 25 is about 188 against 120 today; the ratio rises late, not early.)

**Pay** for a cleared floor: `pay = step + actBonus(act) + finalBonus`, where `actBonus = (act - 1) * scrapPerAct` (default 1) and `finalBonus` = `scrapFinalBonus` (default 1) on a final node. Endless Mine: `step + min(4, floorIndex / 4)` (depth replaces act).

**Overlevel rule** (amends `floorPay`): if `floorLevel >= compass`, full pay; else `max(1, pay * belowCompassPercent / 100)`, default 50, rounded down. In Act 1 this is exactly today's rule (a step 3 floor pays 1). It is the anti-farm lever for Q1.

**Worked check** (solo, average step 2):

| Trip | Compass | Floors | Haul | Levels gained |
|---|---|---|---|---|
| Infestation (act 1) | 5 | 4 | 2+2+2+3 = 9 | about 1.8 |
| Copper Works (act 2) | 10 | 4 | 3+3+3+4 = 13 | about 1.9 |
| Herobrine (act 5) | 25 | 5 | 6 x 5 + 1 = 31 | about 2.5 |
| Infestation farmed | 10 | 4 | 1+1+1+1 = 4 | about 0.6 |

Fail keep stays 50 percent of the haul (settled). Partial banking is unchanged: `ScrapMath.bank` loops level by level with the rising cost.

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| `SCRAP_PER_CHART` | replaced by `ScrapMath.levelCost(compass)` | `ScrapMath` |
| `bank(compass, progress, haul)` | loop: while `progress + haul >= levelCost(compass)`, subtract and raise | `ScrapMath` |
| `floorPay(step, floorLevel, compass)` | gains `act` and `finalNode` arguments | `ScrapMath`, callers in `RunLifecycle` |
| Bar | `progress / levelCost(compass)`, e.g. `4/7` | compass item lore (`Keystone`), omen bar, sidebar |
| Knobs | `scrapCostBase` 4, `scrapCostEvery` 3, `scrapPerAct` 1, `scrapFinalBonus` 1, `belowCompassPercent` 50, `endlessDepthEvery` 4, `endlessDepthMax` 4 | `pocketdungeons.json` |

**(e) Text.** Lore: `Compass 10: 4/7 scrap to 11` (unchanged shape). Clear title subtitle: short `+4 scrap` (preferred), clear `+4 scrap to your haul (3 + act bonus 1)`. Below compass, short `+1 scrap (below your compass)`; this line answers the decision doc's open question whether +1 "reads as an insult" by naming the reason.

### Q3 (PD-183): one sculk language, and replacing `sensor_gallery`

**(a) Problem.** "I don't like how these sculks act differently then the ones that spawn enemies"; "this room needs to be replaced"; "sculk-like things like the shrieker and sensor should read as stealth mechanics." Today sensors count pulses toward a wave (5 per wave, 1 in the Ancient City, `SculkOmen`), shriekers give darkness then a wave (`PressureSources`), and only rooms with `pressure: "omen"` arm.

**(b) Options for the rule.**

| | A. One Heard meter per room | B. Sensors only warn, shriekers punish | C. Floor-wide sculk alarm |
|---|---|---|---|
| Player does | Every sensor pulse fills the room's meter; when full, the shrieker shrieks: darkness and a wave. Sneak to stay quiet. | Sensors glow and play a sound but do nothing; any shrieker activation is a wave. | All sculk on a floor share one meter. |
| Why | One cause, one effect, a number on screen, a reward for quiet. | Simple but sensors become decor again. | Punishes a room you already left. |
| Build | Medium | Small | Medium |

**(c) Recommendation: A**, with a reward for silence so quiet is a choice, not only a tax.

The rule, in the player's words: **Sculk hears you. Fill a room's sculk and it shrieks: darkness and a wave. Cross it unheard for +1 scrap.**

- Every armed sculk room (any room containing a sensor or shrieker, `pressure: "omen"` no longer required for sculk) has `heard` and `heardMax`.
- A sensor rising edge adds 1. Vanilla does the stealth for free: sneaking, and walking on wool or carpet, emit no vibration; arrows, block breaks, eating and fighting do.
- At `heardMax` the room **answers**: the nearest shrieker shrieks (forced, vanilla sound and particles), darkness on members within 40 blocks (today's `darken`), one lootless wave from today's wave code. The meter resets to 0 and the room is marked heard.
- A room with sensors and no shrieker answers from the sensor nearest the player, with the shrieker sound.
- A catalyst adds 1 to `heard` when a mob dies within 8 blocks (fighting near one is loud) and blooms as vanilla does.
- **Quiet earns:** leaving a sculk room whose spawners are all cleared while it was never heard pays `sculkUnheardScrap` (1) into the haul of members present, once per room.
- Spawner-driven mob spawns in the Spawner Dungeon brood chamber are classic spawners, not sculk; no change there. If any room uses a shrieker as a decorative mob source, it now obeys the meter.

**Ancient City:** every room armed (as today), `heardMax` 2 instead of 4. On the final floor, the second answer on the floor wakes the Warden (replaces `WARDEN_PULSES` 4 pulses with `ancientWardenAnswers` 2). Crossing the final floor unheard is the capstone's stealth win: the Warden never wakes; the floor still needs its spawners cleared. Its Q4 finale is the Warden itself (it is the capstone's fight already).

**Room concepts to replace `sensor_gallery`:**

1. **Hush Gallery (recommended).** A long straight gallery. A wool carpet path winds the long way round along the walls past sensors in alcoves; a short gravel and amethyst floor crosses the middle under a shrieker hung from the ceiling. The spawner sits at the far end. One idea: quiet costs time (the long carpet route while the spawner's mobs come at you) or speed costs noise. No items needed: both routes always work.
2. **The Echo Well.** A vertical shaft: descend ledge by ledge past sensors in the walls to a spawner at the bottom; a ladder route is quiet but slow and exposed, dropping is fast and loud. Verticality he likes, but fall damage and landing vibrations make it harder to read.
3. **The Sleeping Choir.** A chapel of shriekers on pews; the reward chest sits on the altar between them. Very readable but it is a loot room, not an encounter, and it duplicates the Ancient City's look.

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| `heardMax` | 4 (Ancient City 2) | config `sculkHeardMax`, `sculkHeardMaxAncient`; room JSON override `heardMax` |
| Catalyst death radius | 8 blocks | config `sculkCatalystRadius` |
| Unheard pay | 1 scrap | config `sculkUnheardScrap` |
| Warden | second answer on the final floor | `SculkOmen.ancientWardenAnswers` (config) |
| Retire | `NORMAL_PULSES_PER_WAVE`, `ANCIENT_PULSES_PER_WAVE`, `WARDEN_PULSES` | `SculkOmen` |
| Arming | any cell containing sculk sensor, calibrated sensor or shrieker | `PressureSources` scan |
| New room | `hush_gallery`, encounter, tier 2, weight 1, acts 1 and 2, `maxPerDungeon` 1 | new `RoomSpec`, `dungeon_room/hush_gallery.json`, `RoomSelector.ENCOUNTER_ROOMS` |
| Retire room | `sensor_gallery` weight 0 (keep the files a release) | `dungeon_room/sensor_gallery.json` |

**(e) Text.**

| Place | Short (preferred) | Clear |
|---|---|---|
| Omen bar while in a sculk room | `Heard 2/4` appended | `Sculk heard you 2 of 4` |
| First sculk room of a player (title, once) | Sculk hears you / Sneak. Stay unheard. | Sculk hears every sound / Sneak or walk on wool. If it fills, it shrieks. |
| Answer (title) | Heard | The sculk heard you |
| Unheard (action bar) | Unheard. +1 scrap | You crossed unheard: +1 scrap |
| Ancient City board note | Every sound counts. Wake nothing. | Two sounds wake a room. Two rooms wake the Warden. |

### Q4 (PD-184): a finale on the last floor

**(a) Problem.** "There wasn't a boss for copper works"; "There should be some extra challenge in the last floor." Target pressure: the brood wave at 1 life, "just barely enough resources to succeed."

**(b) Options.**

| | A. Horde wave on the final room | B. Named elite per dungeon | C. Mix by act: wave in Act 1, wave plus elite from Act 2 |
|---|---|---|---|
| Player does | Clears the final room's spawners; the room seals and a themed wave comes. | Fights one tough named mob with a health bar. | Act 1 finales are waves; Act 2 and up a wave with an elite inside it. |
| Why | Proven (BroodWave landed). | Reads as a boss, which is what he asked for. | Escalates with the campaign; capstones stay the big fights. |
| Party / ominous | BroodWave scaling per member, `MAX_WAVE` 40. Ominous adds the ominous mob variant chance as on any floor. | Health scales per member. | Both. |
| Build | Medium (generalise BroodWave) | Medium | Medium (both share one class) |

**(c) Recommendation: C.** He asked "boss or horde" without an answer; a wave with a named leader is both, and Act 1 stays gentle. Capstones keep their own fights (`CapstoneFights`) and get no finale on top.

**Rules:**

1. Trigger: on the final node's floor, when the floor's last required spawner is cleared, in whatever room that spawner stands. `roomBias` is a soft weight (only Lemon's playtest bias treats it as a hard filter), so the finale must never depend on a biased final room such as `master_furnace` being drawn. Title, 3 seconds, then the wave spawns at that room's spawner positions (or the room centre if broken).
2. The doors of that cell close (iron door latch as `IronDoorLatch`) until the wave is dead, so it cannot be kited through the floor. The floor clear waits for the wave, as in the brood chamber.
3. The elite: a vanilla mob with a custom name, health `eliteHealth x (1 + 0.5 x extraMembers)`, its dungeon's uniform (Q5) or listed gear, glowing for the first 5 seconds, its own boss bar (red, notched 10) under the omen bar. Its gear never drops (J7).
4. Pays: `scrapFinalBonus` (Q2) is the finale's scrap; plus `finaleRewardChests` (1) into the reward barrel.
5. A death during the finale is a normal death (a life). No extra scaling by lives: pressure comes from lives already spent.

**Per-dungeon finale (Acts 1 and 2; later acts follow the same JSON):**

| Dungeon | Final node | Wave (solo) | Elite |
|---|---|---|---|
| Mineshaft | (its final) | 4 zombies, 3 cave spiders | none (Act 1) |
| Infestation | The Queen's Nest | 10 silverfish, 4 cave spiders | none |
| Ossuary | The Last Rest | 5 skeletons, 2 zombies | none |
| Rootworks | The Great Drip Cavern | 4 drowned, 2 slimes (size 2) | none |
| Copper Works | The Master Furnace | 4 husks, 2 skeletons, copper uniform | **The Foreman**: vindicator, copper axe, 60 health |
| Deepslate | The Silent Deep | 4 skeletons, 3 zombies, darkness pulse at start | **The Deep Sentry**: skeleton, Power I bow, 50 health |
| Frostworks | The Big Freeze | 5 strays, 2 zombies in ice blue leather | **The Frost Warden**: stray, Slowness tipped arrows, 50 health |
| Cow Pits | (2 nodes) | none: the short dungeon stays short | none |
| The Kennels (Q8) | The Alpha's Den | 5 angry wolves | **The Alpha**: wolf with wolf armour, 40 health |

Per extra member: +50 percent wave (rounded up), capped at `MAX_WAVE`.

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| Dungeon JSON | `"finale": {"mobs": [{"type": "minecraft:husk", "count": 4}, ...], "perMemberPercent": 50, "elite": {"type": "minecraft:vindicator", "name": "The Foreman", "health": 60, "gear": {"mainhand": "minecraft:copper_axe"}}}` on the dungeon (applies to its final node) | `dungeon/*.json`, `DungeonDef` |
| Runtime | `FinaleWave` generalising `BroodWave` (states, kill tracking, sizing), driven from `CapstoneFights` tick | new class; `BroodWave` becomes one configuration of it |
| Seal | close the final cell's iron doors while the wave lives | `IronDoorLatch` |
| Knobs | `finaleEnabled` true, `finaleRewardChests` 1, `finaleCountdownSeconds` 3, `finaleEliteHealthPerMember` 0.5 | `pocketdungeons.json` |
| Validator | `finale` forbidden on capstones; mob ids must resolve | `PackValidator` |

**(e) Text.**

| Place | Short (preferred) | Clear |
|---|---|---|
| Title on trigger | The Master Furnace stirs | Last stand: the Master Furnace stirs |
| Subtitle | The Foreman comes. | Kill the Foreman and his crew to finish. |
| Act 1 subtitle | Hold the room. | Kill the wave to finish. |
| Elite bar | The Foreman | The Foreman (finale) |
| Board note (append to final node notes) | Ends in a fight. | The last floor ends in a fight. |

### Q5 (PD-185): Copper Works mobs wear copper

**(a) Problem.** "could we thematically make it so that all mobs wear two pieces of copper, including weapons?" "just in this copper dungeon."

**(b) Options.** A: hard-code Copper Works in a mob spawn hook (small, a dead end). B: a `mobUniform` field per dungeon (small to medium, reusable). C: per-theme uniforms (a deviated floor would lose or gain the uniform with its theme; confusing in a Copper Works trip).

**(c) Recommendation: B**, keyed on the trip's dungeon (not the floor theme), so every Copper Works floor matches his ask "just in this copper dungeon".

- "Two pieces": melee mobs (zombie, husk, drowned without trident, vindicator, piglin) get **one random copper armour piece plus a copper sword** (or the listed weapon). Ranged mobs (skeleton, stray, bogged) keep their bow and get **two random copper armour pieces**. Creepers, spiders, silverfish, slimes: none. Copper armour and copper tools exist in 26.2 (the Copper Age items); confirm the item ids at build time.
- Applies to every hostile spawned inside the dungeon: spawner mobs, waves, finale. Overwrites whatever the difficulty rolled in those slots.
- **Drops: zero.** `DungeonDrops` already sets every gear slot's drop chance to 0 (J7). No farm.
- Difficulty: copper armour is weak; it is a look with a small toughness bump. Watch the clear time.

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| Dungeon JSON | `"mobUniform": {"armourPieces": 1, "rangedArmourPieces": 2, "armour": ["minecraft:copper_helmet", "minecraft:copper_chestplate", "minecraft:copper_leggings", "minecraft:copper_boots"], "weapon": "minecraft:copper_sword", "chance": 1.0}` | `dungeon/copper_works.json`, `DungeonDef` |
| Hook | at mob add in the dungeon level, beside the J7 drop zeroing | `DungeonDrops` (same entity add callback) |
| Opt-ins later | Frostworks: leather dyed `#A8D8F0`, iron sword; Basalt Foundry: golden; Ossuary: none | dungeon JSON |
| Knob | `mobUniformEnabled` true | `pocketdungeons.json` |

**(e) Text.** No new title; the look is the message. Copper Works dungeon `notes`, short (preferred): "A copper forge. Its crew wears copper." Clear: "A copper forge full of coal and iron. Every mob wears copper." The current note mentions coal and iron and "Smelt as you go"; keep the smelt line if the owner prefers it and add only "Its crew wears copper."

### Q6 (PD-186): the hopper-key toll room

**(a) Problem.** Worst room: "The room with the key in the hopper to open the iron doors." That is `barred_vault` (trial key into a hopper, iron door; `SpurToll.tollFor`). Frame Lock uses a chest (`Locks.Kind.ITEM_KEY`), not a hopper, so it is not the room he meant.

**Diagnosis:** fiction and readability. A hopper is plumbing, not a lock; Minecraft already teaches one verb for a trial key, and it is the vault block. The room around it (an iron door in a partition, repaired at stamp time by `SpurToll.apply`) is also the heaviest repair code in the mod.

**(b) Options.**

| | A. Real vault block | B. Keyhole lectern | C. Retire both rooms |
|---|---|---|---|
| Player does | Uses the trial key on a vanilla vault (right click), which spits the reward, as in a trial chamber. | Places the key on a lectern-like stand; the door opens. | Nothing; rooms gone. |
| Why | Zero teaching: vanilla verb. Keeps "a vault that needs a key from this floor". | Custom, still needs teaching. | Loses the key economy's sink. |
| Party | Vanilla vaults reward each player once: in a party every member with a key can open it. | One key opens for all. | n/a |
| Build | Small to medium (stamp a vault, set its loot table and key item; delete the door repair) | Medium | Small |

**(c) Recommendation: A for `barred_vault`.** Remove the iron door, partition and hopper; the vault block sits on a plinth behind bars as set dressing, open to walk up to. Normal trial key opens a normal vault; on an ominous floor the room stamps an ominous vault that takes the ominous trial key (the PD-170 "floor's key kind" rule now falls out of vanilla). The room's relief one-shot (a life back on taking the reward, `PressureSources`) fires on the vault ejecting. The floor never needs the key: the room is `loot` role only, never on the clear path (soft-lock rule kept).

**`ominous_bargain`:** drop the gold toll (gold is scarce and a toll in a scarce resource is exactly what the scarcity rule forbids). The chest is open; **taking from it is the bargain** (+2 level for the rest of the floor, already the PressureSources one-shot). The room's sign or title says so before you open it.

**Frame Lock:** keep, provided its key item is supplied inside the same room (check `MechanismSpecs`; if the key can be absent, set the room to `loot` role only).

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| `barred_vault` | vault block, `config.loot_table` = the room's current reward table, key item trial key or ominous trial key by floor | `SpurSpecs` template, a stamp step replacing `SpurToll.apply` for this room |
| `SpurToll` | toll for `barred_vault` and `ominous_bargain` returns null; class kept only if anything else uses `HOPPER_KEY`, else retired with `Locks.Kind.HOPPER_KEY` | `SpurToll`, `Locks` |
| `barred_vault.json` | keep `requires: ["trial_key"]` (so it only rolls on floors that can drop one) | `dungeon_room/barred_vault.json` |
| Knob | `bargainLevels` (today `PressureSources.BARGAIN_LEVELS` 2) into config | `pocketdungeons.json` |

**(e) Text.**

| Place | Short (preferred) | Clear |
|---|---|---|
| Barred Vault arrival (action bar) | A vault. Bring this floor's key. | A vault: use a trial key from this floor on it. |
| Bargain arrival (title) | Ominous Bargain / Take it, and the floor fights back. | Ominous Bargain / Take the chest: the rest of this floor gets 2 levels harder. |

### Q7 (PD-187, PD-190): a persistent display, and moving info out of chat

**(a) Problem.** "floor information and Chart/scrap information in the top left or right corner"; "persistent text should be more than enough"; "I never read any of the run messages in chat."

**(b) Options.**

| | A. Scoreboard sidebar per player | B. Text display riding the player | C. Extra boss bars |
|---|---|---|---|
| Where | right edge, mid screen | in the world in front of the camera | top centre, stacked |
| Pros | Exactly "the right corner"; up to 15 lines; colour per line; since 1.20.3 the number column can be blank. Sent per player by packet, so each sees their own. | Anywhere. | Already used (omen bar). |
| Cons | Hidden by the tab list only while held; one sidebar per player (no other mod here uses it). | Jitters with movement, blocks view, other players see it. | Each bar is a line plus a bar; three would cover the top of the screen. |
| Cost | A few packets on change only. | An entity per player teleported every tick. | Cheap. |
| Build | Medium | Medium, poor result | Small, poor result |

**(c) Recommendation: A.** Per-player objective sent with packets (not the shared server scoreboard), blank number format, lines repainted only when a value changes, at most once per second.

Lines, top to bottom (title in the dungeon's theme colour):

| Line | Example | Colour | Shown |
|---|---|---|---|
| Title | `Copper Works` | gold (Endless Mine: grey `Endless Mine`) | in a trip |
| Floor | `Floor 3: Gear Loft` | white | in a trip |
| Lives | `Lives 3` | green 4 to 5, yellow 2 to 3, red 1 (same as `OmenBarText.omenColourIndex`) | in a trip |
| Spawners | `Spawners 2/3` | white; green when done | on a floor |
| Heard (Q3) | `Heard 1/4` | dark aqua | in an armed sculk room |
| blank | | | |
| Haul | `Haul 7 scrap` | aqua; after a finish `Banked 7 scrap` (PD-179 wording) | in a trip |
| Compass | `Compass 10: 4/7` | yellow | always |

Lobby: title `Pocket Dungeons`, then `Compass 10: 4/7` and `Next: Copper Works` (the Astrolabe Room's Start here dungeon). Party: each member sees their own haul and compass; add `Party 3` under Lives. Ominous floor: Floor line in dark purple with `(ominous)`. Toggle: `/dungeon display off|on`, stored per player.

**Chat audit.** Chat stays the log for all of these; this is where each should *also* show. The coder should grep `sendSystemMessage` in `RunLifecycle`, `IntervalBanking`, `StoreShop`, `SalvageStation`, `Omen`, `DoorLives`, `Diaries`, `PartyRewards` to confirm which are chat-only today.

| Information | Move to |
|---|---|
| Floor pay (`+3 scrap`) | clear title subtitle (exists) and the sidebar Haul ticking up |
| Bank result, compass rise | HOME title (PD-180 done); compass rise gets its own title `Compass 11` with a level-up sound; sidebar |
| Fail settlement (half kept) | title `The dungeon claims you` / `Kept 4 of 8 scrap` |
| Key redemption, vault reward | action bar |
| Omen and life loss | omen bar (exists), death title `Lives 2` |
| Blood door cost | door commit title subtitle `Cost 1 life. Lives 3.` |
| Milestone fanfare (dungeon finished, act opened) | Q10b title rules; an opened act also shows in the world: the next Astrolabe Room opens on it and its marker on the astrolabe plinth lights |
| Shop and salvage results | action bar `Sold 4 string: +1 emerald` |
| Refusals (`Only the owner...`) | action bar, yellow |
| Affixes on the floor | floor start title subtitle (`FloorStartTitle`) and sidebar floor line colour |
| Diary page delivered | title `Diary page` and the book in hand |
| Party joins, leaves, deaths of others | action bar for others |

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| New class | `SidebarDisplay`: builds lines from `InstanceRecord`, `DungeonLog.Entry`; per-player objective `pd_side`; diff and send | new file |
| Rate | repaint on change, at most every 20 ticks per player | config `sidebarRepaintTicks` 20 |
| Default | on | config `sidebarEnabled` true |
| Per player toggle | `/dungeon display` | `DungeonCommands`, `DungeonLog.Entry` flag |

**(e) Text.** As in the line table. Short labels are preferred everywhere; a clear version would be `Haul (at risk) 7 scrap`, which is too wide for the sidebar. Teach "at risk" on the GO HOME board instead.

### Q8 (PD-188): replace Feral, and wolf content

**(a) Problem.** "Feral seems out of place, we should instead make a wolf themed dungeon, make a wolf/pet themed floor for another dungeon, and create a rare generic wolf themed room. And then create a new affix to replace feral." Wolves mattered in the capstone: "enough pet dogs to help with the initial wave."

**Pets: keep them.** They gave him the session's best moment. Rule: a pet is per trip. Wolves tamed in a trip already follow their owner from floor to floor (owner confirmed); add a cap of `petCap` (3) per player, and leave them behind when the trip ends (check that they are). Taming uses bones as vanilla; bones come from skeletons at `BONE_SHARE` 0.25 and from Mineshaft and Ossuary, so a pack costs a real resource. No pet leaves the dungeon (scarcity: no farm).

**The replacement affix (options).**

| | A. Restless | B. Starving | C. Marked |
|---|---|---|---|
| Effect | Undead killed have 30 percent chance to rise once, 2 seconds later, where they fell (soul particles and a groan first). | Hunger drains twice as fast. | One player per floor is marked; mobs prefer them. |
| Counterplay | Kill with fire (fire aspect, flame, lava, a lit block) and they stay down; or stand on the body spot. | Bring food. | Party positioning; solo it is just "harder". |
| Risk and reward | +1 level like other affixes; more kills means more drops (bones for pets). | Eats a finite resource: against the scarcity rule. | Unreadable solo. |
| Ominous | 50 percent rise chance. | | |
| Build | Medium | Small | Medium |

**Recommendation: A, Restless.** One readable effect, SCP tone (the dead do not stay down), real counterplay, works solo and in a party, costs time and risk, not items.

**Wolf dungeon: The Kennels** (Act 1, `baseLevel` 3, 5 nodes, main theme new `kennels`: spruce, stripped logs, hay, iron bars, a cold taiga cave). What it offers that others do not: the one place you can build a pack before the Spawner Dungeon. Ore promise: none (notes must say so by omission). Nodes:

| Node | Layer | Notes line (the reason to choose it) |
|---|---|---|
| Lodge Gate | 1 | An old hunting lodge. Something still barks. |
| Kennel Rows | 2 | Cages full of strays. Bring bones. |
| Hunting Grounds | 2 | Rabbits and foxes. Food, if you can catch it. |
| Bone Yard | 3 | Skeletons. Bones for the hounds. (side door, 1 life) |
| The Alpha's Den | 4, final | The pack's leader. Ends in a fight. (finale, Q4) |

**Wolf floor elsewhere: The Hound Crypt**, a new layer 2 node in the **Ossuary** (bones are the Ossuary's resource; a crypt of hounds buried with their masters fits). Notes: "Buried hounds, not all asleep. Tame what wakes." Room bias: two kennel rooms. Because `roomBias` is only a soft weight, the node's wolf identity must not rest on it: the floor's wolves spawn neutral from a node flag (today's Feral spawn code moves here), and the kennel rooms are tagged to the Ossuary and The Kennels in their `dungeons` list so they can be drawn at all. The Kennels likewise needs its own theme rooms, not a bias over generic halls.

**Rare generic room: The Lost Dog.** One cell, any Act 1 or 2 theme, weight 0.1, `maxPerDungeon` 1. A collapsed trapper's camp: zombies around a fenced pen with one wolf inside. Clear the room and open the pen's gate; the wolf is tamed to the first player who opens it (no bones needed; the cost is the fight). One idea: rescue.

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| Retire | `dungeon_affix/feral.json` weight 0 (kept a release for old saves), `AffixIds.FERAL` kept as legacy | `dungeon_affix`, `AffixIds.LEGACY` |
| New affix | `dungeon_affix/restless.json`: `label` Restless, `order` 1, `min_level` 3, `weight` 1, `effects: {"undead_rise_chance": 0.3, "undead_rise_chance_ominous": 0.5}` | data, `AffixEffects` |
| Node flag | `"neutralWolves": true` (what Feral did) | dungeon JSON, `FeralContent` |
| Pets | `petCap` 3 per player, dropped at trip end (following already works) | config, `RunLifecycle` |
| New dungeon | `dungeon/kennels.json`, theme `dungeon_theme/kennels.json`, 4 to 6 rooms, finale | data, `RoomSpec` |
| New room | `lost_dog`, weight 0.1, roles encounter | data, `RoomSpec` |
| `cube_recipe/feral.json` | check what it unlocks; repoint to Restless or retire | data |

**(e) Text.** Restless board line, short (preferred): "Restless: the dead get up once. Burn them." Clear: "Restless: slain undead may rise again once. Fire keeps them down." Lost Dog action bar on taming: short "A friend." clear "The dog is yours for this trip."

### Q9 (PD-189): ore expectations on mine floors

**(a) Problem.** "Still yet to see any hidden ores in this room" (Endless Mine); "This room is very good, but it didn't have enough ore in it" (`deep_shaft_landing`).

**(b) Options.** A: `hiddenOre` on the Endless Mine only. B: on every dungeon whose palette has ore. C: no hidden ore; raise visible nodes everywhere.

**(c) Recommendation: A**, plus a fix to `deep_shaft_landing` and a guardrail. The Endless Mine is called a mine and is about descending, so it should pay the Mineshaft's promise and more with depth. Other dungeons keep their visible nodes; their notes already name what they hold.

- Endless Mine `hiddenOre`: pockets 1 to 2, size 1 to 3, blocks coal and iron; every 4 floors deep add a deepslate variant and a gold entry; cap total hidden ore blocks per floor at 12.
- `deep_shaft_landing`: nodes from 5 (3 iron, 2 lapis) to 9 (5 iron, 2 lapis, 2 coal), at least two visible from the landing. It is a Deepslate room (Act 2); it is also the room he judged on an Endless Mine trip, so check whether Endless Mine draws it (theme and `dungeons` say Deepslate only; a deviation may have pulled it).
- Guardrail: `PackValidator` fails a dungeon or node whose `notes` contain "ore" or a palette ore name when its `nodePalette` has no ore block and it has no `hiddenOre`; warns when a dungeon's `notes` say "dig" without `hiddenOre`.

**(d) Spec.**

| Item | Value | Where |
|---|---|---|
| `endless_mine.json` | `"hiddenOre": {"pocketsMin": 1, "pocketsMax": 2, "sizeMin": 1, "sizeMax": 3, "blocks": ["coal_ore", "coal_ore", "iron_ore"], "deepBlocks": ["deepslate_iron_ore", "gold_ore"], "deepEvery": 4, "capPerFloor": 12}` | data, `HiddenOrePlanner` (new `deepBlocks`, `deepEvery`, `capPerFloor`) |
| `deep_shaft_landing.json` | nodes as above | data |
| Validator | notes vs palette rule | `PackValidator` |

**(e) Text.** Endless Mine notes, short (preferred): "Dig the walls. Deeper pays better." Clear: "Ore hides in the walls. Every few floors down, richer ore."

### Q10: small items

a. **"GO HOME" after a finish.** Use **"Leave"** on the board heading and the lever, since the payout is already banked. Short: `Leave. Banked 7 scrap.` Clear: `Dungeon finished. Banked 7 scrap. Pull to leave.` Before a finish, GO HOME stays (it does bank).

b. **Infestation title missed, Spawner Dungeon title seen.** The capstone's title also unlocked an act and carried "Go Home", and came after a hard fight he was watching for; the Infestation one landed during a normal clear among other titles. Make every finish its own beat: a 4 second title (`StaggeredTitle` with a 1 second gap after the floor clear title, never on the same tick), a toast-like sound (challenge complete), and the sidebar title turning green with a check mark. Recommended text, short: `Infestation finished` / `Banked 9 scrap`.

c. **Auto-bank and the lever.** Keep the instant payout (he asked for it). The lever becomes a pure leave button after a finish; its board says the Leave text in (a). PD-179's "Banked N scrap" is already the right content.

d. **"What's this room called?"** Mostly habit plus timing: the floor title shows at floor start, while he asked mid-floor. The Q7 sidebar's Floor line answers it permanently. Rooms themselves have no visible names; if room names matter, add the room's display name to the action bar for 2 seconds on first entry to a cell (small; recommend only for authored set-piece rooms).

e. **Other "hide for 30 seconds" effects.** Cap the same way with one generalised clamp: **Wither** at 8 seconds (wither skeletons, Act 4), **Slowness** from strays at 6 seconds, **Darkness** at 8 seconds outside the Ancient City, **Mining Fatigue** (elder guardian, Act 3) at 30 seconds (it is a theme beat, not a hide). Leave Hunger and Weakness alone (short already). Knobs: `effectCaps: {"minecraft:wither": 8, "minecraft:slowness": 6, "minecraft:darkness": 8, "minecraft:mining_fatigue": 30}` replacing `poisonMaxSeconds` with `minecraft:poison: 10` in the same map.

## 3. Migration

| Save state | Rule |
|---|---|
| Compass | Kept exactly. Never lowered. |
| Bar progress (0 to 4 of 5) | Kept as raw scrap. If it already meets the new `levelCost(compass)` (only possible at compass 1 or 2, where cost is 4), bank once at migration: the compass rises, never drops. Otherwise it reads e.g. `4/7`. |
| Haul in an open trip | Kept; banks under the new curve at the next bank. Floor pay already paid is not recalculated. |
| Offered doors in an open first staging room | On load, a record with `dungeonId` empty and no door taken restamps its first staging room as the Astrolabe Room with nothing selected; the old dealt offers and any preview are discarded. A trip already inside a dungeon is untouched. |
| Already finished dungeons | Stay finished; their door bulb turns oxidized in the Astrolabe Room and they pay repeat emeralds. Their vault and diary are not given again. |
| `doorReroll` | Stays in the codec, unused. |
| Feral | Existing floors already dealt Feral finish as Feral; new deals never roll it. Legacy id kept so old journals and saves parse. |
| Dungeon JSON | Copper Works gains `mobUniform` and `finale`; Act 1 and 2 non-capstone dungeons gain `finale`; Endless Mine gains `hiddenOre`; Ossuary gains the Hound Crypt node and edges; new `kennels.json`. `PackValidator` must accept the new keys and reject `finale` on capstones. |
| One-time message | Title on first join: `Compass 10: 4/7` / `Levels now cost more. Deeper floors pay more.` Plus one chat line for the log. |

## 4. Knobs

| Knob | Default | Range | Where |
|---|---|---|---|
| `scrapCostBase` | 4 | 2 to 10 | pocketdungeons.json |
| `scrapCostEvery` | 3 | 1 to 10 (compass levels per +1 cost) | pocketdungeons.json |
| `scrapPerAct` | 1 | 0 to 3 | pocketdungeons.json |
| `scrapFinalBonus` | 1 | 0 to 5 | pocketdungeons.json |
| `belowCompassPercent` | 50 | 0 to 100 | pocketdungeons.json |
| `endlessDepthEvery`, `endlessDepthMax` | 4, 4 | 1 to 10, 0 to 10 | pocketdungeons.json |
| `failHaulKeepPercent` | 50 (unchanged) | 0 to 100 | pocketdungeons.json |
| `repeatFinishEmeraldPercent` | 50 | 0 to 100 | pocketdungeons.json |
| `hallLabelRange`, `hallTurnTicks`, `hallFunnelNewPlayers` | 4, 20, false | 2 to 8, 0 to 60, bool | pocketdungeons.json |
| `token.mat`, `hall`, `unlock` | per dungeon | any block id; `act` or `special` | dungeon JSON |
| `sculkHeardMax`, `sculkHeardMaxAncient` | 4, 2 | 1 to 10 | pocketdungeons.json; room JSON `heardMax` |
| `sculkCatalystRadius` | 8 | 0 to 16 | pocketdungeons.json |
| `sculkUnheardScrap` | 1 | 0 to 3 | pocketdungeons.json |
| `ancientWardenAnswers` | 2 | 1 to 5 | pocketdungeons.json |
| `finaleEnabled` | true | bool | pocketdungeons.json |
| `finaleRewardChests` | 1 | 0 to 3 | pocketdungeons.json |
| `finaleCountdownSeconds` | 3 | 0 to 10 | pocketdungeons.json |
| `finaleEliteHealthPerMember` | 0.5 | 0 to 1 | pocketdungeons.json |
| `finale.mobs[].count`, `finale.perMemberPercent`, `finale.elite.health` | per dungeon | count 0 to 20, percent 0 to 100, health 20 to 200 | dungeon JSON |
| `mobUniformEnabled` | true | bool | pocketdungeons.json |
| `mobUniform.armourPieces`, `rangedArmourPieces`, `chance` | 1, 2, 1.0 | 0 to 4, 0 to 4, 0 to 1 | dungeon JSON |
| `bargainLevels` | 2 | 0 to 5 | pocketdungeons.json |
| `sidebarEnabled`, `sidebarRepaintTicks` | true, 20 | bool, 5 to 100 | pocketdungeons.json |
| `petCap` | 3 | 0 to 8 | pocketdungeons.json |
| Restless `undead_rise_chance`, `_ominous` | 0.3, 0.5 | 0 to 1 | dungeon_affix/restless.json |
| `lost_dog` weight | 0.1 | 0 to 1 | dungeon_room JSON |
| `effectCaps` | poison 10, wither 8, slowness 6, darkness 8, mining_fatigue 30 | 0 (off) to 60 seconds | pocketdungeons.json |
| Endless Mine `hiddenOre.capPerFloor`, `deepEvery` | 12, 4 | 0 to 40, 1 to 10 | dungeon JSON |

## 5. Waves (Q11)

Ranking by player impact over build cost: Q7 display (high impact, medium) and Q10 (high, small) first; Q1 Astrolabe Room (his top pick, large); Q2 curve (medium, small, but tuned against the picker); Q4 finale (high, medium); Q5 (low, small, rides with Q4); Q6 (medium, small); Q3 (medium, medium); Q8 (medium, large); Q9 (low, small).

| Wave | Scope | Depends on | Tests | Live check owed |
|---|---|---|---|---|
| **A. Legibility** | Q7 sidebar and chat audit moves; Q10 a to e (Leave board, finish title beat, effect caps map) | none | pure: sidebar line builder for trip, finished, lobby, party, ominous; effect cap map clamps each listed effect. Gametest: sidebar packet sent to the right player only. | He plays one trip and answers without prompting what floor he is on and how much haul he carries; notices the Infestation-style finish title. |
| **B. Astrolabe Room** | Q1 authored first staging room (glass back wall, decor, plinth), stamped door rows on the `OXOXOXOOXOXOXO` pattern, doormats, labels, bulb states, astrolabe display and interaction entities, act turning, special doors on the side walls (Endless Mine, experimental offer), DESCEND moved to the plinth, `token` and `hall` in dungeon JSON, retire reroll and forced first door, repeat emerald rule | A (the sidebar's "Next:" line) | pure: door state and bulb per dungeon, fill order for 1 to 6 doors, validator rejects 7 per act and 13 specials, Start here, act cycle skips locked acts; gametest: astrolabe turn re-stamps the door row; owner opening a door previews the entry node behind the glass; member opening only wishes; DESCEND punches spaces 7 and 8 and commits the same `dungeon_chosen` and node as the old door; a special door previews behind the back glass; iron doors cannot be opened; protection holds. | **Must be played before C.** Does he find the astrolabe; do bulbs and iron doors read as status; does seeing the room through the glass change his pick; which dungeons does he pick and repeat; in a party, do wishes get used. |
| **C. Scrap curve** | Q2 cost, pay, overlevel rule, migration | B played | `ScrapMathTest`: cost table, multi-level bank across rising cost, migration never lowers compass, below-compass half. | Levels per trip at compass 10 to 15 (target about 2 per right-act dungeon); does `+1 (below your compass)` read fair. |
| **D. Finales and uniforms** | Q4 `FinaleWave` (BroodWave refactor), finale JSON for Act 1 and 2; Q5 `mobUniform` for Copper Works | C (finale pays through the curve) | pure: wave size per party and cap; elite health per member; uniform piece rule per mob kind. Gametest: brood chamber still behaves (regression), finale seals and releases the cell, uniform items present and drop chance 0. | Copper Works finish: does the Foreman land like the brood wave ("barely enough")? Does an Act 1 wave feel like an ending? |
| **E. Sculk and toll rooms** | Q3 Heard meter, unheard pay, Ancient City retune, `hush_gallery`, retire `sensor_gallery`; Q6 vault block in `barred_vault`, bargain without toll | A (the Heard line) | pure: meter fill, answer, reset, unheard pay once; Warden on second answer. Gametest: vault stamped with the floor's key kind; bargain level rise on take; `RubbleRulesTest` with the new encounter room. | Hush Gallery solved quietly at least once; Ancient City crossed (L31 still owed); vault used without explanation. |
| **F. Wolves** | Q8 Restless affix, retire Feral, pets follow per trip, Hound Crypt node, Lost Dog room, then The Kennels dungeon (may split into F1 affix and room, F2 dungeon) | D (Kennels finale) | pure: rise chance and fire rule; pet cap. Gametest: risen mob spawns once only; pet follows a floor change; PackValidator accepts the new dungeon. | Does Restless read from the board; does he take the Kennels before the Spawner Dungeon. |
| **G. Ore** | Q9 Endless Mine hidden ore with depth, `deep_shaft_landing` nodes, validator rule | none (can ride with any wave) | `HiddenOrePlanner` cap and depth; validator rejects a notes ore promise without ore. | "Still yet to see any hidden ores" does not recur; `nodes_mined` on an Endless Mine floor. |

## 6. Open until played

- Does the picker make him play more different dungeons, or the same favourite? (decides whether repeat pay needs more than half emeralds)
- Is about 2 levels per right-act dungeon the right pace, and does the curve flatten Act 1 replays enough?
- Is the finale at solo, 1 life, as tight as the brood wave, and is an Act 1 wave enough of an ending?
- Does "Heard 2/4" read as stealth, and is +1 scrap for unheard worth sneaking for?
- Is a pet pack too strong in the Spawner Dungeon capstone once players can build one on purpose in The Kennels?
- Does Restless read, and is fire counterplay available enough without fire aspect?
- Does the sidebar crowd the screen next to the omen bar; would he hide it?
- Did he ever notice the Frame Lock room (it is not the hopper room; no data on it).
- Copper armour on mobs: a look only, or does it lengthen Copper Works clears noticeably?
- Answered by the owner: wolves already follow a floor change; the diary page is believed not to repeat (confirm the vault in `Payout`); `roomBias` is a soft weight that Lemon's bias uses as a hard filter, so nothing in this design depends on a biased room being drawn.
- Does a first-time player try the astrolabe without being told, and does an act turn read as "a different act"?
- With one act on show at a time, does he still feel the campaign ahead (the plinth markers), or does he want every act visible at once?
- Does the oxidized bulb read as "finished" without a word, or does he rely on the label?

## 7. Supersedes

- **decision-2026-10-07-haul-and-blood-doors:** amends "Earning" (floorPay gains act and final bonuses and the half below compass), the Knobs entry `ScrapMath.SCRAP_PER_CHART` (replaced by `levelCost`), Migration (bar now out of a varying cost). The model itself (haul, banking, fail keep, lives) is kept.
- **DUNGEON_STRUCTURE_DESIGN:** D23 kept (compass gates, now shown as iron doors with unlit bulbs in the Astrolabe Room); D13 and D29 Endless Mine door 3 rule replaced by its special door on a side wall; "design section 11" capstone on door 1 or 2 replaced by the capstone's gold doormat border and the Start here marker; section 11 Ancient City pulse rules replaced by the Heard meter.
- **plan-2026-10-06-2:** J3's sensor pulse trigger amended (Heard meter, any sculk room arms); J7 kept and relied on (no equipment drops).
- **PD bugs:** PD-181, PD-182, PD-183, PD-184, PD-185, PD-186, PD-187, PD-188, PD-189, PD-190 answered by this design; PD-170 (key kind) superseded by the vanilla vault; PD-164 (`SpurToll` repair) retired for both toll rooms; PD-178 generalised into `effectCaps`; PD-165 (milestone title) amended by Q10b; PD-179 and PD-180 kept, extended by Q10a and c.
- **Carried live checks:** L24, L28, L29 (door screen and map questions) superseded by the Astrolabe Room; ExperimentalDungeon door 3 becomes a special door; L20 (watched gated-room solves) gains the Hush Gallery; L53 (ominous key redemption) moves to the vault block.
- **Rooms and data:** `sensor_gallery` retired (weight 0); `dungeon_affix/feral.json` retired (weight 0); Feral's neutral wolf spawn becomes the node flag `neutralWolves`.
