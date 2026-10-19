# Pocket Dungeons: Decisions

Single record of owner decisions, model changes and workshop ledgers. Newer sections outrank older ones where they disagree.

---

## Standing foundational rules (September to October 2026)

| Date | Decision |
|---|---|
| 2026-09-26 | **Owner disconnect grace:** Owner disconnect gets a reconnect grace (about two minutes) before the party's run ends. |
| 2026-09-26 | **The kit is granted once:** Chosen at the bag chest; entering and leaving never grant items and nothing tops up a kit (`DungeonLog` flag `kit_granted`). |
| 2026-09-27 | **Loot weights:** Fewer emeralds low (tier 1 chest and vault emerald weight x0.2, tier 2 x0.6; trial spawner key/emerald rewards 8/2 at tier 1, 6/4 at tier 2, 5/5 at tier 3); armour trim templates at tier 3 only; wood early (tier 1 log weight x4, tier 2 oak logs, supply tier 1 logs and planks x2); bones cut to a quarter (`DungeonDrops`); weapons and armour twice as common in chests and vaults. |
| 2026-09-27 | **Weapon and armour durability caps:** Weapons 24 (wood, gold), 40 (stone, copper), 64 (iron), 96 (diamond), 128 (netherite); armour 32, 48, 64, 96, 128 by material; bow, crossbow and shield 64; trident and mace 96. Applied on craft and to every dungeon item, keeping wear proportional (PD-69). |
| 2026-10-05 | **Dungeon structure (`docs/DUNGEON_STRUCTURE_DESIGN.md`):** One trip is one dungeon, chosen in the Astrolabe Room. Five acts follow Steve's playthrough; the act sets the loot band, compass + step sets difficulty. Every dungeon has one `mainTheme`, and deviating floors or rooms only borrow from the same or an earlier act while keeping the dungeon's loot band, node tiers and merchant currency; entry and final floors always use `mainTheme`. |
---

## 2026-10-07: Haul and Blood Doors

Replaces the spendable scrap pool (plan 2026-10-06-2 J1, D28, D32, D33) after the
2026-10-07 playtest (PD-162): the pool and the compass read as two competing levels, an
overleveled player could not earn what a side branch charged, and nothing but emeralds was ever
at stake. The owner asked for Darkest Dungeon style risk: failing loses progress, not everything.

### The model

The player's sentence: *Clearing floors fills your haul, and bringing it home fills your compass;
if the dungeon claims you, you lose half the haul, and side doors cost a life.*

| Number | Where it lives | At risk? |
|---|---|---|
| **Compass** (`DungeonLog.Entry.highestCharts`) | the keystone item, `Compass 12: 3/5 scrap to 13` | never; only rises |
| **Chart progress** (`chartProgress`, 0 to 4) | the compass bar | never |
| **Haul** (`haul`) | the clear line, the GO HOME board, the compass lore during a trip | yes: half lost to a failed dungeon |

- **Earning.** A floor clear pays `ScrapMath.floorPay(step, floorLevel, compass)` into each
  member's haul: the dealt step at or above their compass, 1 below it. No emeralds (D28 is gone).
- **Banking.** Going home, a checkpoint exit, a grace expiry and a finish bank 100 percent of the
  haul into the bar (`RunLifecycle.bankHaul`, from the log alone, so an absent member banks too).
  A haul carried out of a trip that ended while the member was away banks in full when they next join.
- **Failing.** The fifth death banks `failHaulKeepPercent` (default 50) of every member's haul,
  present or detached, and loses the rest, besides the finish rewards.
- **Side doors.** An edge now says `lives` (1, or 2 for Frostworks) instead of `cost`. The door
  takes that many lives from the party (the trip's omen, the same lives a death spends) and is
  refused unless at least one life would remain. The old `cost` key is rejected by the loader.
- **Scrap is never spent.** There is no spendable pool.

### Migration

The compass keeps its level. The bar starts at what the old pool held past the compass's own
whole charts (`ScrapMath.migratedProgress`, clamped to 0 to 4): a migrated or spent-down player
lands on 0/5, a never spent pool of 16 at compass 3 keeps 1/5. The haul starts empty. The first
join shows one message. The old `scrap` codec key is read once and never written again.

### Supersedes

- plan-2026-10-06-2: D28 (overlevel emeralds), D32 (scrap is paid and kept on the spot; amended:
  scrap fills the haul and the bar fills at a bank, going home still loses nothing), D33 (side
  branches cost scrap; replaced: they cost lives), D34 and J2 (amended: failure also halves the haul),
  J1 (replaced in full), J3 (amended: only a death and a side door raise omen), section B "Paying a
  floor", and "Changes to plan 2026-10-06-1" item 4 (the GO HOME board showed no scrap; it now shows the haul).
- DUNGEON_STRUCTURE_DESIGN: D11 (going home banks the haul plus chests), W5 (party scrap balance),
  the Banking row of the summary table.
- Kept: D23 (the compass gates dungeons), D24 (every exit settles the same), D27 (floor level is the
  node level plus the step), D29 (act completion).
- Bugs: PD-162 is resolved by the model change; PD-166's command became `/dungeon admin compass` and `haul`.

### Knobs

- `failHaulKeepPercent` (pocketdungeons.json, default 50): the share a failed dungeon keeps.
- An edge's `lives` in `dungeon/*.json`: 1 or 2.
- `ScrapMath.SCRAP_PER_CHART` (5).

### Open until played

The price of a life (if players rarely die, a side door is nearly free), whether half is soft or
harsh on failure, whether `+1 scrap` on a floor far below the compass reads as an insult, and
whether a two life Frostworks door is ever taken.

---

## 2026-10-09: Playtest 2026-10-08-1 design pass

The full spec, with option tables, numbers, text and knobs, is `docs/DECISIONS.md`
(Opus's reply to `docs/DECISIONS.md`). This record is the owner-approved summary and the build
order. If the two disagree, the reply wins until this file says otherwise.

### The decisions, in the player's words

1. **Choose your dungeon in the world, not from three random doors.** The first staging room is the
   Astrolabe Room: turn the astrolabe to cycle unlocked acts, open a 1 by 2 door to look at the dungeon's
   first room through the back glass, pull DESCEND to go. Special doors (Endless Mine) stand on the side walls.
2. **Levels cost a little more each time, and deeper floors pay more.**
   Level cost `4 + floor(compass / 3)`; a floor pays `step + (act - 1)`, plus 1 on a final floor; below your
   compass it pays half (at least 1).
3. **Sculk hears you.** One Heard meter per sculk room; full means darkness and a wave; crossing unheard pays
   +1 scrap. `sensor_gallery` is replaced by the Hush Gallery.
4. **The last floor ends in a fight.** Act 1: a finale wave. Act 2 and up: a wave with a named elite.
5. **Copper Works mobs wear copper** (a per-dungeon `mobUniform`; nothing drops).
6. **The hopper-key toll goes.** `barred_vault` becomes a real vanilla vault opened with the trial key;
   `ominous_bargain` loses its gold toll.
7. **A sidebar shows floor, lives, spawners, haul and compass.** Chat stays the log; critical lines also
   show on titles and the action bar.
8. **Feral retires for Restless.** New Act 1 dungeon The Kennels, a Hound Crypt floor in the Ossuary, a rare
   Lost Dog room. Pets stay, per trip.
9. **The Endless Mine hides ore** and gets richer with depth; `deep_shaft_landing` gets more ore.
10. **Small items:** finished dungeons say Leave (the payout is already banked); every finish gets its own
    title beat; effect caps generalise the poison cap.

### Build order (each wave is one reviewable commit, tests green before the next)

| Wave | Scope | Owed live before the next |
|---|---|---|
| A | sidebar, chat audit moves, Leave wording, finish title beat, effect caps | one trip: can he say his floor and haul unprompted |
| B | Astrolabe Room (Q1): BUILT 2026-10-09, see PD-181 in `BUGS.md` for the list of deviations from the reply | played before C |
| C | scrap curve (Q2): BUILT 2026-10-09, see PD-182 in `BUGS.md` | levels per trip |
| D | finales and uniforms (Q4, Q5): BUILT 2026-10-09, see PD-184 and PD-185 in `BUGS.md` | Copper Works finish |
| E | sculk and toll rooms (Q3, Q6): BUILT 2026-10-09, see PD-183 and PD-186 in `BUGS.md` | Hush Gallery, vault |
| F | wolves and Restless (Q8): BUILT 2026-10-09, see PD-188 in `BUGS.md` (the wolf floor is Rootworks' Wolf Hollow, not the Ossuary's Hound Crypt) | |
| G | ore (Q9): BUILT 2026-10-09, see PD-189 in `BUGS.md` | |

### Supersedes

- `docs/DECISIONS.md`: Earning (act and final bonus, half below compass), Knobs
  (`SCRAP_PER_CHART` becomes `levelCost`), Migration (bar out of a varying cost). The haul model is kept.
- `DUNGEON_STRUCTURE_DESIGN`: the three-door front offer, the forced capstone door, the Endless Mine on door 3,
  the Ancient City pulse rules.
- PD-181 to PD-190 answered; PD-178 generalised into `effectCaps`; PD-170 and PD-164 retired for the toll rooms.

### To verify at build time (the reply asserts, the code decides)

- Copper armour and copper tool item ids in 26.2.
- Whether `Locks.Kind.HOPPER_KEY` has other users before `SpurToll` is retired.
- Whether the finish vault repeats on a second finish (`Payout`), and whether the diary page repeats.
- Which chat lines are chat-only today (`sendSystemMessage` audit) before moving them.
- Whether `roomBias` is a hard filter anywhere besides Lemon's playtest bias.

---

## 2026-10-11: Affix workshop

Running ledger. Nothing here is applied until the owner says so. Status: agreed, applied, deferred.

Findings from the read (code, not decisions):
- `weight` is loaded but unused; `AffixMath.pick` is a plain shuffle.
- `incompatible` is empty on all ten affixes, and `pick` does not skip incompatible pairs despite the `AffixDefinition` javadoc.
- Effect numbers mostly live in `PocketDungeonsConfig` (for example `swarmingMobFactor` 1.5, `overclockedCooldownFactor`), not in the affix JSON. The JSON values may be inert.
- Feral is retired (`min_level` 999).

| # | Affix | Decision | Exact change | Status |
|---|---|---|---|---|
| 1 | Cooked (`ominous`) | Keep. Show the ominous bonus on the floor clear big text (title). | Java change, not data: add a line to the floor clear title naming the Cooked bonus. Needs a build task and a LIVE_CHECKS entry. | agreed |
| 2 | Swarming | Leave alone. | None. (Open nit: blurb says "more drops"; not verified.) | agreed |
| 3 | Overclocked | Pay for speed: a floor cleared under a target time pays extra scrap. Target shown on the floor title. Cooldown factor 0.4 stays. | Java change (no par timer exists today). Starting numbers, to tune: target 360 s, bonus +1 scrap. Basis: BALANCE.md solo clears run about 190 to 640 s (median near 400), one outlier at 1190 s. Fix blurb to name the target. | agreed (numbers open) |
| 4 | Loaded | Redesign as trapped chests: the guaranteed tool cache chest keeps its loot but opening it springs an ambush. Drop the dead `extra_trial_bodies` flag and the "more trial bodies" blurb. | Java change, build cost S to M (a use-block callback on the cache chest, no mixin; spawn a themed wave around the chest on first open, once). Data: keep `bonus_tool_pool`, remove `extra_trial_bodies`, rewrite blurb. Ambush size and mob list open. Must not soft-lock: ambush spawns outside the chest cell's doors and does not block the exit. | agreed (details open) |
| 5 | Restless | Widen to every mob, not just undead. Risen mob is theme-matched (for example wolves rise as bone hounds). Fire still keeps them down. Keep the 2 s groan and soul telegraph. | Java change, build cost M: `RestlessRules.shouldRise` currently gates on undead type; add a per-theme rise table. Data: keep `undead_rise_chance` 0.3 and 0.5 (rename later). `min_level` 3 is dead (first affix at compass 5); set to 5 when convenient. Check each rise type against vanilla limits. | agreed (details open) |
| 6 | Molten | Do nothing. (Held idea for Workshop 3: lava pockets with water making mineable obsidian.) | None. | agreed |
| 7 | Explosive | Extend the mine fuse by 25 percent. No other change. | `RoomContent.java:452` `MINE_FUSE_TICKS` 20 to 25 (hard-coded constant, not a knob). Add a LIVE_CHECKS line when applied. Blurb nit left alone for now ("the only TNT you will ever find"). | agreed |
| 8 | Silenced | Show the count: an action bar line "Silenced: n/3" after each consumable used. Rest unchanged. | Java change, build cost S: in `PressureSources.silencedUse` send an action bar message with the running count (the counter already exists). Player text per the player-text skill. Add a LIVE_CHECKS line. | agreed |
| 9 | Voided | Exclude puzzle and ordeal cells from Voided, so a voided cell is only ever a plain traversal cell. Keep the three shapes. | Java change, build cost S: in `LayoutStamper.computeVoidedCells` skip cells whose room role is a puzzle or ordeal operation (read the role the way `isHazardEligible` does). Add a LIVE_CHECKS entry: first compass 45 trip. | agreed |
| 10 | Incompatible pairs | None. Molten, Explosive and Voided can co-occur. (Note: `AffixMath.pick` ignores `incompatible` anyway, and `weight`; fix the javadoc or the pick when convenient.) | None. | agreed |
| 11 | New affix: Blackout | Add. Sight pressure: lights go out in scattered cells, reusing the `DungeonLight` dim and dark logic. Ravenous and Tethered not kept. | New file `dungeon_affix/blackout.json` plus Java for the effect (build cost S to M; a closed-set operation like `dark_cells` in `AffixEffects` and `AffixMeta`). Label, blurb, min compass, which cells and whether a light source counters it are open. Must never darken a cell that `requiresLight`. | agreed, details open |
| 12 | New: Wager and Blood | Every affix wagers 0 to 10 (`wager` in its JSON); a floor shows its total as "Loot n%" and a wagered floor pays one extra piece into the copper chest. Blood is a new affix that costs 1 life when the door is committed. | Built by T50: `Wager`, `WagerGear`, `dungeon_affix/blood.json`, `life_cost` in `AffixEffects`. Design: `docs/DECISIONS.md`. | built (T50), never played |

### Workshop 1 complete

Nothing applied. Every row except 2 and 6 needs Java. Live checks to add when each is built: 1, 3, 4, 5, 7, 8, 9, 11.

---

## 2026-10-11: Boss workshop

Running ledger. Nothing here is applied until the owner says so. Status: agreed, applied, deferred.
Build cost: S, M, L. No design here needs a new mixin unless flagged.

| # | Dungeon (act) | Decision | Build cost | Exact change | Status |
|---|---|---|---|---|---|
| 1 | Mineshaft (1) | Dark phase: at half the wave dead the lights fail and the rest of the wave is cave spiders. | S | Finale code (`FinaleWave`) plus the `DungeonLight` dark logic; wave mix split in two phases in the dungeon's `finale` block (now 4 zombies, 3 cave spiders). Links to the Blackout affix. Add a LIVE_CHECKS entry. | agreed |
| 2 | Lush Caves (1) | Owner concept, replaces the flood-phase proposal. Boss: a scaled-up slime; on its first split it becomes double the usual number of slimes. A dungeon wide effect: a random splash potion is dropped on the player at intervals (like the Cooked floor, but dungeon-owned), and the drop rate rises during the boss fight. | S to M | Finale code (`FinaleWave`): swap the drowned and slime wave for one giant slime (`Slime.setSize`, a plain entity API, no mixin). First split doubling needs a spawn listener on the children (vanilla splits 2 to 4, this tops it up), tagged so later splits are vanilla; check. Potion drop: a timed splash potion spawned above each member (vanilla `ThrownSplashPotion`), S. Pool, interval, telegraph and the boss rate multiplier open. Vanilla limits: all server-side, no client mod. | agreed (details open) |

Carried to Workshop 3 (dungeon concept, not boss): treat one floor of Lush Caves as a swamp with witch haven rooms; fungal skeleton archers (vanilla bogged); clay shelves with vines to navigate; mushrooms; dead end pond rooms. Overlap to check: the potion drop is a dungeon wide effect, so decide whether it is an affix-like rule owned by the dungeon JSON or a new closed operation in `AffixEffects` (it would sit beside Cooked and Blackout).

Lush Caves story frame (owner, 2026-10-11): witches brew potions and poisons on the upper floors; the lower floors have the brews dripping in from above. Working reading, not yet confirmed: witch haven rooms and bogged on the early floors (nodes 0 to 3 of 7: Moss Gate, Glow Berry Hollow, Clay Burrow, Wolf Hollow); the drip starts on the later floors, gets heavier with depth, and is heaviest on the Great Drip Cavern boss fight. Fits the floor name and the "drip" in `great_taproot`.

Decision 2b (Lush Caves potion pool): option 1. About 70% harmful (poison, slowness, weakness, nausea), about 30% good (regeneration, speed, jump boost). Telegraph: the potion falls visibly about 2 seconds with a sound and particles. Early floors / later floors split taken as accepted (no objection). Numbers (interval, boss multiplier, per-floor ramp, harming from compass 5 or never) open. Status: agreed (details open).
| 3 | Infestation (1) | Owner concept, replaces the egg-hatch proposal. Dungeon mechanic: a mossy stone brick appears, matures to stone brick, then to infested stone brick, then bursts and releases a silverfish. Boss: a giant silverfish that spawns in like the other bosses and attacks; after a while it burrows, and an adds phase starts: mossy bricks spawn and mature and burst into silverfish through the same mechanic. | M (mechanic) plus S to M (boss) | Mechanic: a staged block timer (`scheduleTick` or a mod tick list) over vanilla `MOSSY_STONE_BRICKS`, `STONE_BRICKS`, `INFESTED_STONE_BRICKS`; the burst spawns a silverfish and breaks the block with no drops (avoid the PD-75 free item leak). Boss: `Attributes.SCALE` on a Silverfish (vanilla, no mixin); burrow is invisible plus invulnerable plus teleported underground with block-dust particles, S. Vanilla limits: all server-side. Stage times, active brick cap, and rollout scope open. Counterplay to keep legible: each stage has a sound and crack particles, and the player can smash a brick before it matures. | agreed (details open) |

Decision 3b (Infestation rollout): option 2. The creeping brick mechanic lives in the Silverfish Nests and Chewed Corridors rooms and in the boss fight, not floor wide. Status: agreed (details open).
| 4 | The Kennels (1) | Kill the handler. The pillager handler makes a gesture, then the wolves scale up and turn red. Killing the handler is the counterplay. | S to M | `FinaleWave` + the finale `elite` block. Handler gesture: the vanilla celebrating arm pose (`Raider.setCelebrating`) plus a horn sound. Scale up: `Attributes.SCALE` on the wolves (vanilla). Red: true tint needs a client mod, so use the vanilla angry wolf state (red eyes) and a red glow outline from a scoreboard team colour. Prerequisites, not design: wolves must spawn (PD kennel floors are stone, wolves need grass) and fire spread must be off in the Kennels (2026-10-09-1). Trigger timing, scale amount, and whether killing the handler shrinks them back are open. | agreed (details open) |

Decision 4b (Kennels trigger): option 1. At half the Alpha's health the handler starts a 5 second windup (celebrating pose, horn). Killing it during the windup cancels the empower; killing it afterwards shrinks the pack back. Scale amount open. Status: agreed (details open).
| 5 | Ossuary (1) | Coffins. The burial hall has sealed sarcophagi; the wave rises from them one at a time as the player crosses the room. A skipped coffin opens when the player turns their back. | S to M | `FinaleWave` + the `last_rest` room (`FinalFloorSpecs`/room NBT); coffin blocks via vanilla state changes, rise telegraphed. Links to the Restless affix. No mixin. Coffin count, trigger radius and rise delay open. | agreed (details open) |
| 6 | Spawner Dungeon (1 capstone) | Owner direction: as the Act 1 capstone it is the biggest fight, built from mechanics of the earlier Act 1 dungeons. Structure open. | M to L, last to build | `CapstoneFights`/`BroodWave` (four spawners at `CapstoneSpecs.BROOD_SPAWNERS`). Depends on the earlier mechanics existing first: Mineshaft dark phase, Lush Caves potion drip, Infestation creeping bricks, Kennels handler empower, Ossuary coffins. Build order: capstone after those. | agreed (structure open) |

Decision 6b (Spawner Dungeon structure): option 1. Spawner as phase. Each of the four spawners, once broken, releases one earlier mechanic that then stays on (Ossuary coffins, Infestation bricks, Mineshaft dark phase, Lush Caves drip). After the fourth, the brood mother arrives with the Kennels handler-empower beat and a sped-up drip. Spawner to mechanic order, brood mother design and the numbers are open. Status: agreed (details open).
| 7 | Endless Mine (1) | Deferred to Workshop 3 (decide a depth guardian with the Endless Mine concept). | n/a | None. | deferred |
| 8 | Cow Pits (2) | Specimen 0. Feeding the pedestal cow ("Specimen 0 (do not feed)") wakes it: a huge cow (scaled) that leads the herd. Obeying the sign skips the fight and the reward chest. | S to M | `CowPits.WARD_PEDESTAL_NAME` and the Big Barn rooms; `Attributes.SCALE` on a cow, a use-item hook on the pedestal cow (no mixin). The Big Barn has no `finale` block today. Pedestal in the Barn (it is only in `cow_ward` now), the reward and the scale are open. | agreed (details open) |
| 9 | Deepslate (2) | Hush rule. The Silent Deep uses the Heard meter: sensors listen, a loud fight fills it, a full meter answers with darkness and a Warden whelp (never the Warden). Plants the Ancient City rule early. | S to M | The Heard meter already exists (decision 2026-10-09); add sensors to the `silent_deep` room and a threshold of 4 for the whelp. The Deep Sentry elite stays. Meter rise per bow shot and per fight noise, and the whelp count, open. | agreed (details open) |
| 10 | Copper Works (2) | Furnace fires. The Master Furnace vents pulse on a readable rhythm and burn anyone in a lit zone; the Foreman (vindicator, 60 hp, copper axe) weakens when he steps into the water vats. | S to M | `master_furnace` room (`FinalFloorSpecs`) plus the finale code; vent pulse by timed fire or magma blocks, vats by water blocks, no mixin. Pulse interval, damage, vat count and how much the Foreman weakens are open. Must keep an exit path clear of the vents. | agreed (details open) |
| 11 | Frostworks (2) | Freezing meter. The Big Freeze uses vanilla freeze ticks: staying out of the warm spots (braziers) freezes players, so they move between fire and cover while the Frost Warden fights. | S | `big_freeze` room: powder snow or snow zones plus warm spots; `Entity.setTicksFrozen` (vanilla, no mixin). Braziers must be the readable counter (campfire or lava-free heat). Zone layout and freeze rate open. Overlap noted: Deepslate and Frostworks both have a bow elite. | agreed (details open) |
| 12 | Ancient City (2 capstone) | Leave it; playtest first (L31). Option 2 (remix of the Act 2 mechanics) is held for Workshop 3. | n/a | None. Add nothing until a player has been through it. | deferred |
| 13 | Prismarine (3) | Drain the Heart. The Heart of the Monument floods with the wave; levers or valves (the Rusted Pumps) drain it in stages, which beaches the guardians (slow and flopping on land). The water level is the visible clock. Avoids repeating `elders_chamber`. | M | New `monument_heart` room logic (`FinalFloorSpecs`) plus a finale block in `prismarine.json` (none today); vanilla water blocks and levers, no mixin. Land behaviour of guardians and drowned to verify live. Number of stages, guardian count and the reward are open. | agreed (details open) |
| 14 | Drowned Vault (3 capstone) | A drowned boss. Replace the ravager with a scaled drowned holding a trident in a flooded arena; the water level provides the phases. Rename it so it no longer clashes with the one-Warden rule ("The Drowned Warden" is a ravager today). Option 1 (full Act 3 remix, L) held as a stretch once the Prismarine drain exists. | M | `BossContent.spawn` (ravager, tagged, `BOSS_SCALE_FACTOR` 3.0) and the `wardens_deep` room (`FinalFloorSpecs`). Drowned thrown tridents are vanilla; no mixin. New boss name, arena depth and phase water levels open. The pad gate `bossAlive` stays. | agreed (details open) |
| 15 | Basalt Foundry (4) | The pour. The Great Crucible ladle tips on a timer (bell and glow telegraph), flooding a lane with lava while the wave attacks; players cross on cooled basalt (the lava hardens after a pour). The elite is a magma-armoured piglin brute. The pour sweeps across the room, unlike the Copper Works vents, which are fixed. | M | `great_crucible` (`FinalFloorLateSpecs`) plus a new finale block in `basalt_foundry.json` (none today); lava and basalt block swaps on a timer, no mixin. Lane count, pour interval and hardening time open. Must never trap a player with no basalt path. | agreed (details open) |
| 16 | Blackstone (4) | The Hoard Lord. A scaled piglin brute with a gold phase: it takes less damage while gold lies on the floor, and players grab the gold (a visible pickup) to open it up. Avoids repeating the Foundry elite. | M | `bastion_keep` room (`FinalFloorLateSpecs`) plus a new finale block in `blackstone.json` (none today); damage reduction via an attribute modifier or effect while gold items are present, vanilla piglin behaviour, no mixin. Gold pile count, damage reduction and what the gold is worth (it must not become a free scrap source) open. | agreed (details open) |
| 17 | The Wither's Keep (4 capstone) | Not decided. Proposed: leave it for a playtest first (the vanilla Wither with the two mixins is built). Owner paused here. | n/a | None. | open |

### Workshop 2 paused

Not yet walked: Ender Archive, The End (Act 5), Herobrine (Act 5 capstone). Wither's Keep awaits an answer. Nothing applied. Every decided row is Java (some with room NBT); a live check is owed when each is built.

---

## 2026-10-11: Dungeon and act concepts (Workshop 3)

Running ledger for Workshop 3. Nothing is built until the owner approves the roadmap at the end.

### Act shape

| Item | Decision | Status |
|---|---|---|
| Act shape | Keep five acts, one capstone each, unlock chain as is (D8, D29). | agreed |
| Act 3 | Propose a second Act 3 dungeon in the concepts step. | agreed |

### Act 1

| Dungeon | Decision | Status |
|---|---|---|
| Mineshaft | Keep the current concept (Steve's first mine, cave-ins and seams, dark phase finale). The Borrowed Mine idea not taken. | agreed |
| Lush Caves | Keep, as the concept (swamp with witch havens and bogged on early floors, potion drip below, giant slime). Name stays "Lush Caves". Wolf Hollow and Sapping Den overlaps noted. | agreed |
| Endless Mine | Layer guardian every 3 floors (was a layer every 5 to 6). Loot in three tiers A, B, C: the tier rises every guardian (floor 3 A, floor 6 B, floor 9 C), capped at C, so a player past floor 9 gets tier C every 3 floors. Each act has its own Endless Mine (five mines). Open: what each guardian is, what the tiers contain, what replaces the old act gate (D13, D29: a mine layer reached at 5, 11, 17, 23), and each act mine concept. Supersedes the single-mine, four-layer table in `ZONES_SPEC` section 3.5 and the Endless Mine rows of the unlock chain. | agreed (details open) |
| Endless Mine act gate | Reaching floor 3 (the first guardian) in the previous act's mine counts toward opening the next act. Replaces D13 and D29 floors 5, 11, 17, 23. Which guardian counts when a player skips a mine, and the exact wording, open. | agreed |
| Infestation | Keep, as the concept (the night Steve lost the stone house, creeping bricks in nest rooms, giant silverfish boss). The bricks belong to the Infestation; Lush Caves `sapping_den` should drop `infested_wall`. | agreed |
| Kennels | Keep, as the concept (pillager lodge, hostile pack, handler and Alpha boss). Overlap noted: three dog rooms in Act 1 (`lost_dog`, Kennels, planned Hound Crypt). | agreed |
| Ossuary | Keep, as the concept (a counted catacomb, coffins finale). The torch ending not taken. Overlap noted: Restless on an Ossuary floor doubles the rise. | agreed |
| Spawner Dungeon | Keep, as the concept (the cage waiting since Steve's first night; four spawners each release an earlier mechanic; brood mother last). Build it last, after the five mechanics it echoes. | agreed |
| Act 1 Endless Mine | Guardian approach 1: a mini of each Act 1 boss, escalating (floor 3 small silverfish queen, floor 6 a pack with a handler, floor 9 a coffin wave). Every guardian is harder than the last with no cap: past floor 9 the three repeat, each lap harder. Tiers A, B, C inside the act's loot band (A coal, iron, copper; B adds gold, lapis; C adds redstone and a diamond chance), placeholders for tuning. Hardness curve and the exact mini designs open. | agreed (details open) |
| Cow Pits | Keep Specimen 0 and add a third floor, the Milking Parlour (a clinical dairy floor with more numbered specimens). Where it sits (between Pen Gate and The Big Barn, probably), its rooms and what the milk is open. Retire the borrowed mineshaft rooms from the roomBias when it exists. | agreed (details open) |
| Deepslate | Keep the concept (where Steve found his first diamond, the echo that never stopped, the Hush rule finale). Shorten the run from 5 floors to 4; the 9 floor pool stays for variety. Which layer drops is open. Correction logged: node counts are the pool, a run walks one path (see the floors per run table in the chat). | agreed (details open) |
| Deepslate layer | Drop layer 4. Layer 3 leads straight to The Silent Deep. The blood door (1 life) moves to Deep Quarry straight to the Silent Deep. `deep_dark_landing` (Pale Gallery) moves into Tuff Cellars or the Silent Deep approach. Collapsed Landing and Pale Gallery leave the graph. Exact JSON to be shown before any edit. | agreed |
| Copper Works | Take the ticking clock: a visible countdown on the Master Furnace that the whole dungeon feeds. Clearing spawners and pipe floors slows it; at zero the furnace blows and the finale wave arrives early (a soft failure, not a run failure). Where it shows (action bar, boss bar or a sign), the numbers and how it relates to the Overclocked par time are open. Also owed: declare `hiddenOre` (copper, coal, iron) so the floors match the name, and give Gear Loft a room bias. | agreed (details open) |
| Frostworks | Keep as is, with the cold and heat mechanic (freeze ticks, braziers as warm spots) at the finale. Run stays 5 floors. Footprints idea not taken. Open: whether cold and heat also runs on earlier floors, and the Frost Warden name next to the Warden rule. | agreed |
| Ancient City | Keep as is (a city nobody built, every sensor armed, the Warden wakes itself). The Act 2 remix is not taken; boss stays a playtest question (L31). | agreed |
| Act 2 Endless Mine | Keep: the Deep Mine, a working mine pushed deep (rails, lifts, carts), omen as echo. Guardians at floors 3, 6, 9 are minis of the Deep Sentry, the Foreman and the Frost Warden, each lap harder. Tiers A, B, C inside the Act 2 band, diamond in C. | agreed (details open) |

### Act 3

| Dungeon | Decision | Status |
|---|---|---|
| Prismarine | Keep as the concept (the monument, the drain finale). Tone: SCP pool rooms (liminal tiled pools, endless water, a hum). Add a mechanic to test: an invisible entity whose particle effects give away its position. The lighthouse sweep not taken. Open: what the entity does, where it lives, how it is hit. Pump room and water breathing to confirm live. | agreed (details open) |
| Prismarine: The Wake (new ordeal) | The hunter becomes an Ordeal, room id `the_wake`, on the Guardian Galleries floor. Objective: cross a tiled pool hall to the far lever. Danger: an invisible hunter (drowned, invisibility effect) that only hunts players standing in water; its position shows as bubble and ripple particles plus a hum that rises as it nears, and a ping about 3 seconds before a bite. Counterplay: dry tile islands to hop between, or placed blocks as stepping stones (the skill route and the resource route, per ORDEALS.md vertical convention); hit the particles to kill it. Resolution: the lever and lamp pair, one way, protected. Party size adds hunters (1 per 2 members, max 3). Reward for killing it still open. Build cost M (new `HunterOrdeal` class, room NBT via the room-building skill, a target filter tick). Player lines draft: arm "Something is in the water.", resolve "The water is still." | draft |
| Haunts | New mechanic class replaces the one room Wake: floor wide roaming creatures with a learnable rule, engaging the player now and then. Draft in `docs/DECISIONS.md`. The Wake becomes the first haunt, in Prismarine. Open: how the rule is learned, pressure source, kill reward. | draft |
| Haunts: learning | Tell plus field note: the title says only "Something walks here"; the first real tell writes one rule line in chat; a diary entry tells the story. Slow introduction: on the first floor the haunt stalks without attacking and vanishes several times (horror pacing). Ladder counting open. | agreed (details open) |
| Haunts: ladder | Counted by floor: floor 1 sighting (stalks, vanishes, no attack), floor 2 near miss, floor 3 onwards strikes. A 2 floor dungeon (Cow Pits) carries no haunt or only teases. | agreed |

### Act 3 onwards: paused

Owner, 2026-10-11: enough for Workshop 3. Test Acts 1 and 2 first and see what works before the remaining acts. Not walked: the Drowned Vault concept (the Shape; presented, unanswered), the second Act 3 dungeon (agreed to propose, not yet proposed), the Act 3 Endless Mine, Act 4 (Basalt Foundry, Blackstone, Wither's Keep, Act 4 mine), Act 5 (Ender Archive, The End, Herobrine, Act 5 mine). Boss decisions for those dungeons in `docs/DECISIONS.md` stand but are not to be built yet, except where they feed Acts 1 and 2.

> Status at 2026-10-12: Acts 1 and 2 of this roadmap are built and merged (see `docs/DECISIONS.md`). The
> Endless Mine rows above are built as one mine per act with the floor-3 gate (`docs/DECISIONS.md`).

> Status at 2026-10-13: partially reopened for the three objective-floor
> dungeons (Sunken Treasury, Lighthouse Below, Gilded Seam), built as the
> heist, haunt and claim floor types (`docs/DECISIONS.md`).
> The Drowned Vault concept and the remaining Act 3 to 5 items stay paused.
---

## 2026-10-11: Haunts (floor-wide stalkers)

Status: draft for the owner. Nothing is built. Born in the concepts workshop, replacing the one room hunter ("The Wake" as a single Ordeal).

### One sentence

A haunt is a creature that roams a whole floor, follows one simple rule the player can learn, and engages the player now and then, the way a Lethal Company monster does.

### How it differs from what exists

| Mechanic | Scope | Lives in |
|---|---|---|
| Ordeal | one room, ends with a lever | `Ordeal.java`, `Ordeals.java` |
| Affix | the whole run, a rule bent | `dungeon_affix/*.json` |
| Heard meter | one sculk room, sound fills a bar | `SculkOmen` |
| **Haunt** | **the whole floor, a creature with a rule** | new |

### The parts

1. **A definition per haunt** (data, a closed set of behaviour kinds, like affixes): id, label, which themes and compass levels, its behaviour kind, its tell (particles, a sound), its strike (damage or an effect), its counter, how long it retreats after a strike.
2. **A director** that runs per floor. It decides when a haunt wakes, from a pressure number that rises with time on the floor, omen and noise, and falls when the party clears rooms. One haunt per floor at a start, capped by party size.
3. **A closed set of behaviour kinds**, each a few lines of server tick code:
   - `HUNT_IN_WATER`: only targets players standing in water (the Wake).
   - `STALK_WHEN_UNSEEN`: moves only while no player looks at it (dot product of the look vector); strikes if reached.
   - `DRAWN_BY_SOUND`: walks toward the loudest recent noise (ties to the Heard meter).
   - `FOLLOWS_LIGHT`: drawn to lit torches and lanterns, avoids the dark.
4. **Fairness rules, fixed for every haunt:**
   - always a tell before a strike (a sound or particles, at least about 2 seconds);
   - always a counter the player can learn without dying;
   - after a strike it retreats, so it never chain kills;
   - never active in the entrance room, the lodestone pad, an armed Ordeal's lever room, or during a finale;
   - a death is an ordinary death: a life, as everywhere.
5. **Legibility (owner decision 2026-10-11).** The floor title says "Something walks here." The first time a haunt gives a real tell, one chat line states the rule in few words (for example "It hunts in water."). A diary entry per haunt gives the story. Per the player-text skill. The rule is learned on first contact, not by dying.
5a. **Slow introduction (owner decision 2026-10-11).** Horror game pacing. On the first floor of a dungeon the haunt stalks the player but does not attack, and vanishes, several times: seen at the edge of a room, gone when approached, its tell (particles, a sound) left behind. Only later does it strike. How the ladder is counted (by floor or by encounter) is open, see Open questions.
6. **Scaling.** Compass sets the strike and the number of haunts; ominous adds one; party size raises the cap.

### Vanilla limits (all server side, no client mod)

- Bodies are ordinary mobs (drowned, skeleton, zombie, enderman) with the invisibility effect or a held pose; the tell is particles and sounds sent from the server.
- Looking detection is the server comparing the look vector with the direction to the mob.
- No custom model or animation. Anything that needs one is out.
- Needs a tick hook per active haunt; the cap keeps cost low. No mixin is expected.

### First haunts (one per theme, to test one at a time)

| Haunt | Theme | Rule | Counter |
|---|---|---|---|
| The Wake | Prismarine | hunts players in water, shown by bubbles and a hum | stay on dry tile, or hit the ripples |
| The Counted | Ossuary | moves only when unseen | keep it in view, back away |
| The Listener | Deepslate and Ancient City | walks toward noise | go quiet, sneak, throw bait |
| The Tenant | Lush Caves | follows light | douse torches, use the dark |

### Build cost

- Director and definition loader: M.
- Each behaviour kind plus its tell: S to M.
- First test: The Wake only, in Prismarine: M in total.

### Risks

- Feels like a second Heard meter or omen; keep the pressure number separate and visible, or fold it into omen. Open.
- A haunt that cannot be seen or heard is unfair; every haunt needs a playtest of its tell.
- Griefing a party by pulling a haunt onto a teammate; the retreat rule covers it.

### Open questions

1. DECIDED 2026-10-11: counted by floor (floor 1 sighting, floor 2 near miss, floor 3 onwards strikes). Consequence: a 2 floor dungeon (Cow Pits) never reaches a strike, so it carries no haunt, or only teases. Options considered: by floor, by encounter, by pressure. How the slow introduction is counted: by floor (floor 1 sighting, floor 2 near miss, floor 3 on strikes), by encounter (the first few meetings are teases whatever the floor), or by pressure. Run length varies from 2 to 5 floors, which favours counting by encounter.
2. Whether pressure is its own number or the existing omen.
3. Whether a killed haunt returns, and what killing one pays.

---

## 2026-10-11: Endless Mine, one per act

Task T40 of `docs/DECISIONS.md`. Owner decisions from `docs/DECISIONS.md` (the Endless Mine
rows and the act gate row). This is the engine and the data model; the real guardians are tasks T41 and T42.

### The model, in the player's words

Every act has its own Endless Mine. A guardian waits on every third floor (3, 6, 9 and on), and that floor is the
checkpoint that pays: the first guardian pays tier A, the second tier B, the third and every one after pays tier C.
Every guardian is harder than the last, with no cap. Clearing the first guardian floor of an act's mine counts
toward opening the next act. There are no depth layers and no sealed shaft any more.

### The numbers

| What | Value | Lives in | At risk |
|---|---|---|---|
| Guardian cadence | every 3rd floor | knob `endlessGuardianEvery` (3) | nothing; the party can go HOME at any checkpoint |
| Guardian number n | floor / 3 | `EndlessMineRules.guardianNumber` | |
| Lap slot | `(n - 1) mod 3 + 1` (the act's three guardians repeat) | `EndlessMineRules.slot`, `lap` | |
| Hardness | `1 + 0.25 (n - 1) + 0.5 lap`, no cap | knobs `endlessGuardianHardnessStep` (0.25), `endlessGuardianLapStep` (0.5) | guardian stats, count, damage (each guardian defines its own use) |
| Loot tier | guardian 1 A, guardian 2 B, guardian 3 and later C | `EndlessMineRules.tierIndex`, tables `loot_table/endless_mine/act<N>_<a/b/c>.json` | |
| Act 1 tier A | coal, iron, copper (plus cobblestone, torch) | `act1_a.json` | |
| Act 1 tier B | A plus gold and lapis | `act1_b.json` | |
| Act 1 tier C | B plus redstone, plus a 15 percent diamond | `act1_c.json` | |
| Act 2 tier A | iron, gold, lapis (plus cobblestone, torch) | `act2_a.json` | |
| Act 2 tier B | iron, gold, lapis, redstone, fatter piles | `act2_b.json` | |
| Act 2 tier C | A and B stock, fattest piles, plus a 30 percent diamond (1 or 2) | `act2_c.json` | |
| Floors between guardians | the ordinary floor haul, at the mine's loot band minimum | `LootBands.floorTier` | |
| Act gate | the act's mine cleared to floor 3 (its first guardian) | `ActProgress.mineTarget`, `MineDepth.reaches` | |
| Act 1 mine | `endless_mine`, band 1 to 2, base level 1, door at compass 3 | `dungeon/endless_mine.json` | |
| Act 2 mine | `endless_mine_deep` (the Deep Mine): Deepslate, tuff, dripstone; band 2 to 3, base level 6, door at compass 6 | `dungeon/endless_mine_deep.json`, `dungeon_theme/endless_mine_deep.json`, `worldgen/processor_list/theme_deep_mine.json` | |

Quantities in the tier tables are placeholders for tuning. The placeholder guardian is a ring of vanilla mobs
(zombies in the Act 1 mine, skeletons in the Deep Mine), `round(1 + 2 hardness)` of them (3 at hardness 1, capped at
24), their stats multiplied by `ordinary mob scale x (1 + hardness)`.

### The rules

1. A mine is a dungeon json of kind `endless` with an `act`, hall `special`. The astrolabe shows the mine of the act it
   is turned to, once that act is open and the compass reaches the mine's level (`baseLevel`, so the Deep Mine opens
   where the Act 2 dungeons do). Acts 3 to 5 get a mine by adding a dungeon json, a theme, an adventure, and tier
   tables `act<N>_a/b/c`; no Java changes. Until a mine exists for an act, that act asks nothing of a mine.
2. Every floor of a mine is stamped in that mine's own theme, palette, hidden ore and loot band. The run remembers which
   mine it is in (`IntervalState.mineDungeonId`, in memory like the rest of the interval).
3. On floor `k * 3` the terminal cell holds the guardian. The registered guardian for the act and the lap slot spawns;
   with none registered the placeholder wave does. Its pad stays shut ("The guardian still stands. Finish it first.")
   until every tagged mob is dead.
4. Clearing a guardian floor fills the reward barrel from the guardian's tier table for the mine's act (the table
   replaces the tier table for that floor; the chest count is unchanged). A floor between guardians fills it from the
   ordinary tier table as before.
5. The party can go HOME at any checkpoint as before. Banking is the same average-of-doors rule; the Mine never raises
   the power ceiling.
6. The deepest cleared floor is kept per mine. The Act 1 mine keeps the old `deepest_mine_floor` field; later mines
   live in `mine_floors`. "Reaching floor 3" means clearing floor 3, the guardian floor, since the record is written
   when a floor is cleared.
7. A mine floor's level is the mine's `baseLevel` plus the floors down plus the dealt step, clamped to the compass
   maximum.

### Migration

- Saves: `mine_floors` is a new optional map (act number to floor), left out of the written save while empty, so every
  existing fixture still reads and writes byte for byte as before. `deepest_mine_floor` is unchanged and is the Act 1
  mine's record. A malformed act key in `mine_floors` is dropped; a key for act 1 is ignored. Golden fixture
  `src/test/resources/dungeonlog/mine_floors.json` pins the new shape; the existing fixtures pin the old ones.
- Acts: a player who already passed an old layer threshold keeps the act. `MineDepth.reaches` treats the old record as
  satisfying the new gate: floor 5 of the old mine for act 1 (and so any record of 3 or more), floor 11 for act 2,
  17 for act 3, 23 for act 4. Opened acts are stored sets and are never taken away.
- A shaft that was sealed at a layer boundary is not sealed any more; the in-memory sealing state is gone.

### Supersedes

- `docs/ZONES_SPEC.md` section 3.5 (the four depth layers, floors 1 to 5, 6 to 11, 12 to 17, 18 and on) and the
  mine payout rows of its section 5 in so far as they name layers.
- `docs/DUNGEON_STRUCTURE_DESIGN.md` D13 (each depth layer needs its act; the loot tier climbing every 3 floors within a
  layer's band) and the Endless Mine part of D29 (the mine reached the bottom of the act's layer, floors 5, 11, 17, 23).
  The mine still opens in Act 1 on `endlessMineUnlockLevel`.
- The Mine theme's `loot_tier_every: 3` zone rule (removed from `dungeon_theme/endless_mine.json`).
- `docs/DECISIONS.md`: the Endless Mine row, the act gate row and the Act 1 and Act 2 Endless Mine rows are now
  built as an engine; the guardians themselves are T41 and T42.

### Knobs

- `endlessGuardianEvery` (3), `endlessGuardianHardnessStep` (0.25), `endlessGuardianLapStep` (0.5) in
  `pocketdungeons.json`. The act gate follows `endlessGuardianEvery`.
- `endlessMineUnlockLevel` (3): the compass for the Act 1 mine's door. Later mines use their `baseLevel`.
- Loot: the six tier tables under `loot_table/endless_mine/` (quantities, weights, the diamond chances).
- Per mine data: `lootBand`, `baseLevel`, `nodePalette`, `hiddenOre` in the dungeon json; the palette in the theme and
  its processor list.
- `tools/tune_loot.py` tunes spawner key weights only; it does not touch these tables. They are exempt from the K and K2
  loot policy on purpose (`endless_mine/` in `tools/loot_rules.py` and `LootRulesTest`), because copper, gold and
  redstone are the owner's asked-for mine payout.

### Open until played

- Does a guardian every 3 floors feel like a rhythm or a chore? Is the first guardian (floor 3, about three floors of
  work) a fair gate for the next act?
- Should the gate count arriving on floor 3 rather than clearing it? Today it is clearing, because the record is written
  at a floor clear and the guardian floor is the paying checkpoint.
- Is a ring of plain mobs a fair stand-in, and does the hardness curve (1, 1.25, 1.5, then 2.25, 2.5, 2.75, then 3.5)
  climb too slowly or too fast? Do ordinary floors between guardians still feel worth the descent without the old
  per-floor tier climb?
- Tier C pays on every guardian from the third on, so deep mining is a steady faucet. Is that too much raw material?
- The Deep Mine palette (tuff bricks 30 percent in place of stone bricks, dripstone block 8 percent in the floor) is
  a first pass and unseen.

---

## 2026-10-12: Wager and the loot skew

Owner direction, 2026-10-12: "Blood" becomes an affix that simply costs 1 life. Every affix has its own value based on its difficulty or wager; the sum of the values heavily skews the loot odds.

Status: approved 2026-10-12 with the choices below. Built by task T50.

### The rule in the player's words

*The harder the floor's affixes, the better the loot. Every affix says how much it wagers; the door shows the total.*

### Wager values (proposed, to tune)

| Affix | Wager | Why |
|---|---|---|
| Swarming | 1 | more bodies, nothing new to learn |
| Overclocked | 1 | faster spawners, but it pays a par bonus |
| Silenced | 1 | a cost on healing, shown as n/3 |
| Blackout | 2 | sight pressure |
| Explosive | 2 | floor mines |
| Molten | 2 | floor lava |
| Loaded | 2 | an ambush on a free cache |
| Restless | 2 | every mob rises once |
| Voided | 3 | falls, compass 45+ |
| Cooked | 3 | the whole run ominous |
| Blood | 3 | costs 1 life |

A floor's wager is the sum over its affixes. Compass 5 to 24 carries one seeded affix, 25 to 44 two, and so on, so a typical sum runs 1 to 3 early and 6 to 9 late.

### Blood

An affix that costs 1 life, nothing else. It follows the old blood door rule: refused unless at least one life would remain after the cost (`docs/DECISIONS.md`). It replaces the `lives` edges in the dungeon files (the Deepslate blood door could not be built under the loader; this removes that problem).

### The skew: the copper chest piece (owner clarification, 2026-10-12)

The summed wager decides what the copper chest at the end of the floor pays. It does not touch the chest and vault
tier tables.

**Rule.** A floor whose affixes wager something pays one extra piece of gear into its copper chest, seeded like
promised gear (owner, trip salt, node; `PromisedGear.extraSeed`), so the door board and the chest agree. The piece is a
weapon, a tool, a piece of armour or a material, picked evenly. The tier starts at the floor's base tier (the node's own
`gear:<slot>:<tier>` reward if it has one, else the dungeon's loot band floor) and rises by one with a chance of
`min(wagerTierChanceCap, wagerTierChancePerPoint x wager)` percent (20 per point, capped at 90), never above the dungeon's
loot band (D10). Wager 0 pays nothing extra. `wagerGearEnabled` false turns the whole thing off.

- `material` is a new gear slot: `loot_table/gear/material_1..4` (iron and lapis, then diamonds and emeralds, netherite
  scrap at tier 4). Tier 4 tool and armour tables were added so a band 4 dungeon can draw them.
- Not built: a better enchant level at wager 6 and up. The gear tables set `enchant_with_levels` in data, so a bonus
  would need a second code path; left out.
- Code: `Wager`, `WagerGear` (pure), `PromisedGear.extraPick` / `rollExtra`, `RunLifecycle.promisedItems`,
  `DungeonScreen.previewGets`. Tests: `WagerTest`, `WagerGearTest`, `WagerGearGameTest`.

#### Superseded options

1. Upgrade chance on every chest draw: not chosen.
2. **Luck weights on the tier tables (was recommended): superseded by the owner's clarification of 2026-10-12.**
   No `quality` was added to any table.
3. Bonus rolls on every chest: not chosen (fights finite resources).

### Where the player sees it

The player never sees the word "wager" or its raw number. The door shows a loot percentage,
`100 + lootPercentPerWager x wager` (the knob is 50): "Loot 100%" for a floor with no affixes, "Loot 200%" at wager 2,
"Loot 400%" at wager 6.

- The door board and the deal sheet: "Loot 200%" after the affix names, white at 100 and gold above. The piece the
  floor adds is named in the line of what the floor gives, beside the other promises.
- The floor title: the affix lines, then "Loot 200%" as the last line (always shown, "Loot 100%" with no affixes).
- The percent is real (docs/DECISIONS.md): the floor pays round((base + depth) x pct/100)
  rolls. The copper chest rule above is a second, separate payout.
- The chest or vault itself needs nothing.

### Decisions (owner, 2026-10-12)

1. Blood pays its 1 life once, when the door is committed.
2. The skew is the extra copper chest piece (was: luck weights, option 2, superseded the same day).
3. The player reads "Loot n%", never "wager".

---

## 2026-10-13: One loot system, the wager percent pays

PD-207 (playtest 2026-10-13-1). The door board printed two numbers that both said "loot":
`Loot 250%` (the affix wager, display only) and `loot x3` (the floor's barrel roll count). The
player asked which was which on sight.

Owner call: "lets just have the one loot system using the affix wager and %", then picked
"Percent scales chest rolls" from the design options.

### The rule

`Loot n%` is the floor's loot multiplier and the only loot number. A cleared floor pays

    rolls = round((baseRewardChests + depthBonus) x n / 100)

with the won-finale chest still added flat on top (`FinaleWave.rewardChests`, not scaled; it is
a completion prize, not odds). At `lootPercentPerWager` 50 that reads: Loot 100% pays the base,
Loot 250% pays 8 rolls on a base of 3, Loot 400% pays 12. Rounding is half up
(`Wager.scaledChests`).

The wagered floor's extra copper chest piece (`WagerGear`) stays: it is gear, not loot rolls,
and the deal sheet names it with the other promises.

### What the player sees now

- Door board name line: `Hollow Walls . Overclocked . Loot 250%` (white at 100, gold above).
- Door board green line: `8 rolls . costs 2 scrap`, the count the percent implies for this
  floor's depth. The word "loot" appears once.
- Deal sheet and floor title: the same Loot percent, unchanged.
- Cleared verdict and the bar between floors: `8 loot rolls, chart scrap` reads the real stored
  count (`FloorState.rewardChests`).
- HOME: `N chests in your reward barrel` is now the deepest floor's actual roll count
  (`IntervalState.floorChests`, recorded at each clear), not a formula recomputed at settle.

### Not done

- The vault's finish rolls are not scaled; the percent wagers on the floor, not the finish.
- No knob for the scaling itself: the shape is fixed by `lootPercentPerWager` plus
  `baseRewardChests` and the zone's `depthBonus`. A separate scaling knob would split the one
  system again.
- `Payout.payoutCommand` still takes the chest count; it now receives the deepest floor's count.

### Tests

- `WagerTest.scaledChests`: 100% pays base, 200% doubles, 250% rounds 7.5 up to 8, a zero knob
  and a negative wager never pay below base.
- `IntervalBankingTest`: the settle count is the deepest floor's rolls; empty trip still banks
  the base.
- `BankAnywhereGameTest` builds its records with per-floor `floorChests`, mirroring production.

Supersedes: nothing. Amends `docs/DECISIONS.md`'s "display only" line.

Open question: whether the deal sheet should also state the scaled count (`8 rolls`) on floors
the player has not committed yet, or only the percent. Both are on the board already.

---

## 2026-10-13: Act 3 and 4 floor types (heist, claim, haunt)

The player's sentence: *a floor is no longer always a spawner hunt; three new
kinds ask a different question.* A heist floor asks whether you can crack the
vault and get out while it wakes. A claim floor asks how many veins you will
stake while the seam fights back. A haunt floor asks whether you can light the
beacons while the Keeper walks.

Built as three dungeons: `sunken_treasury` (heist, act 3, baseLevel 13),
`lighthouse_below` (haunt, act 3, baseLevel 14, carrying the haunt
`the_keeper`) and `gilded_seam` (claim, act 4, baseLevel 17). A node's
`objective` in `dungeon/*.json` picks the kind (`spawners` is the default, and
a `final` node must keep it; the finale gates on spawners). `FloorObjectives`
dispatches on the kind and arms an `ObjectiveState` on `FloorState.objective`
at commit, which answers the pad's refusal, the bar's progress line, fixture
protection, block use, the floor title and the reward roll bonus.
`FloorObjectiveRules` holds the arithmetic with no Minecraft imports.

### The numbers

| Number | Default | Where it lives |
|---|---|---|
| Vault channel | 5 s | `heistCoreChannelSeconds` |
| Crew at the crack | `heistCrewBase + members` drowned | `heistCrewBase`, `HeistObjective.raiseCrew` |
| Strongboxes | `heistStrongboxBase + (members - 1) / 2` | `heistStrongboxBase`, `FloorObjectiveRules.strongboxes` |
| Claim quota | 3 veins | `claimQuota` |
| Veins placed | quota + extras, +1 more for a party of 3 or more | `claimExtraVeins`, `FloorObjectiveRules.veins` |
| Tremor | +1 per claim; an ominous floor starts at 1 | `ClaimObjective` (fixed) |
| Hoglin pack | at Tremor `claimWaveTremor` (4), `1 + members / 2` per claim | `claimWaveTremor`, `ClaimObjective.raisePack` |
| The Assayer | at Tremor `claimHunterTremor` (6), once, x2 the scaled health | `claimHunterTremor`, `ClaimObjective.ASSAYER_HEALTH_MULT` |
| Beacons | 2 | `HauntObjective.BEACONS` (constant) |
| Beacon charge | 3 s standing on the plate | `beaconChargeSeconds` |
| Beacon banish | 15 s off the floor | `beaconBanishSeconds` |
| Hunt grace | 20 s after the floor opens | `hauntHuntGraceSeconds` |
| Stagger | 3 s held, then 5 s immune | `hauntStunSeconds`, `Haunts.stagger` |
| Ominous keeper | 1.15x walk speed | `Haunts` (fixed) |

### The rules

1. **Heist (dormant swap).** At commit every trial spawner on the floor is
   captured, block state and block entity tag together, and swapped for
   unbreakable reinforced deepslate, so the floor walks in silent.
2. **Heist (core channel).** The Vault Core, a heavy core on a gold plinth,
   stands in the terminal cell off the pad. A member who uses it channels for
   `heistCoreChannelSeconds`; taking a hit or straying more than 4 blocks
   resets the channel, and anybody may pick it up.
3. **Heist (wake).** The crack puts every stand-in back exactly as stamped
   (the spawners wake with their configs intact), opens each strongbox as a
   barrel holding a deferred roll of the floor's tier chest table, unseals the
   pad and raises the crew in a ring around the core. An ominous floor raises a
   second identical wave about a second later.
4. **Heist (strongboxes, pad).** Strongboxes are greed, never the gate: branch
   cells first, never the entrance or terminal, at most two a cell. The pad
   refuses "The vault is sealed." until the crack.
5. **Claim (claim).** Gilded blackstone veins stand in every cell but the
   entrance, branch cells first, at most two a cell. A right-click claims one:
   it spends to plain blackstone, the count and the Tremor rise, and the floor
   growls. The pad refuses ("The seam holds. Claim N more veins.") until the
   quota.
6. **Claim (Tremor, waves, Assayer).** From Tremor `claimWaveTremor` every
   further claim raises a hoglin pack around the vein; at `claimHunterTremor`
   the floor sends The Assayer once, a named piglin brute at x2 scaled health
   standing near the terminal. Both are pressure only: a failed spawn never
   un-credits the claim that triggered it. An ominous floor starts at Tremor 1.
7. **Claim (bonus rolls).** Each vein claimed past the quota pays one extra
   roll of the floor's reward table (`FloorObjectiveRules.bonusRolls`).
8. **Haunt (HUNT).** The Keeper (`the_keeper`, behaviour HUNT) ignores the
   pressure clock: it wakes once, `hauntHuntGraceSeconds` after the floor
   opened, and walks whether seen or not, so the stalker's freeze-on-look
   never applies. The stage ladder is preserved: early floors are sightings,
   then near misses, then strikes from the strike floor on.
9. **Haunt (invulnerable, stagger).** A hit on the Keeper never lands; unless
   it is inside the immunity window it staggers, held without AI for
   `hauntStunSeconds`, then immune for five more so it cannot be stunlocked.
10. **Haunt (beacon charge and banish, pad).** Two beacons stand in branch
    cells first (never the entrance or terminal), each a heavy weighted
    pressure plate beside an unlit copper bulb. Standing on a plate for
    `beaconChargeSeconds` lights the bulb and banishes the Keeper for
    `beaconBanishSeconds`. The pad refuses ("The lamp is dark. Light N more
    beacons.") until the quota is lit. The Keeper never enters the entrance,
    the terminal or an armed Ordeal room, and it is never spent: a lost body
    returns after the retreat.
11. **Fallback (shared).** Every kind places under the generic recovery rule:
    the quota drops to the placed count, at least one
    (`FloorObjectiveRules.effectiveQuota`), and an objective that placed
    nothing arms as `fallback`, which runs the floor exactly like a spawner
    floor end to end (pad, bar, title and rewards all read ordinary).

Objective blocks are fixtures: the code places them at arm time (`heist`,
`claim`, `haunt` claim their blocks through `FloorObjectives.isFixture`, which
`RoomProtection` protects and `RitualListener` routes right-clicks to). Rooms
only supply the space.

### Migration

None needed. `FloorState.objective` is per-floor state only; nothing new
persists, so a save in progress reads exactly as before.

### Supersedes

- `docs/DECISIONS.md`, "Act 3 onwards: paused": partially reopened
  for these three dungeons only (`sunken_treasury`, `lighthouse_below`,
  `gilded_seam`). The Drowned Vault concept and the remaining Act 3 to 5 items
  stay paused.
- `docs/DECISIONS.md`, the generic objective framework
  note of section 3: built as `FloorObjectives` plus `ObjectiveState`, with
  the generic recovery path as the fallback rule. The brainstorm doc stays as
  provenance.

### Knobs

In `config/pocketdungeons.json`: `heistCoreChannelSeconds` (5),
`heistCrewBase` (2), `heistStrongboxBase` (2), `claimQuota` (3),
`claimExtraVeins` (3), `claimWaveTremor` (4), `claimHunterTremor` (6),
`hauntHuntGraceSeconds` (20), `hauntStunSeconds` (3), `beaconChargeSeconds`
(3), `beaconBanishSeconds` (15).

Fixed in code: `HauntObjective.BEACONS` (2), `HeistObjective.CHANNEL_RANGE`
(4), `HeistObjective.OMINOUS_ECHO_TICKS` (20, the ominous crew echo),
`ClaimObjective.ASSAYER_HEALTH_MULT` (2.0), hoglin pack `1 + members / 2`,
ominous Tremor start 1, ominous keeper speed 1.15x, stagger immunity 5 s.

### Tests

`FloorObjectiveRulesTest` covers the arithmetic pure (fallback, quotas, wave
and hunter tremors, bonus rolls). `HeistFloorGameTest`, `ClaimFloorGameTest`
and `HauntFloorGameTest` cover the floors end to end; `HauntRulesTest` covers
`decideHunt`.

### Open until played

Whether 5 seconds of channel on the core reads as tension or dead time (and
whether a hit breaking it is fair), whether the crew and The Assayer are the
right weight at their acts (they scale as omen waves, untested live), whether
leaving a claim floor while Tremor stays low is too safe (stake the 3 and walk
before the seam answers), whether the Keeper's advance speed and stagger
window read as horror or nuisance, and whether the extra claim rolls and
strongbox payouts inflate the reward economy.

## 2026-10-15: The Proving, a wave-arena dungeon

Owner request 2026-10-16-1: "a gamemode where the player is given a big 4x4
chunk room and have to survive waves". Warframe's endless modes (Survival,
Defense, Excavation) all share one beat: clear a rotation, then choose stay or
extract. The Proving is that beat as a dungeon: an arena room, a wave ladder,
and a repeated stay-or-bank choice mid-run.

| Number | What | Where it lives |
|---|---|---|
| 1 arena room | the floor | room spec, `proving_arena` family |
| `provingWavesPerFloor` 3 | waves gating each floor | config |
| `provingGuardianEvery` 3 | every third floor is a guardian wave | config |
| `provingWaveDelayTicks` 60 | the beat between waves | config |
| wave size = base + members | party scaling | `FloorObjectiveRules` |
| lives 5 shared | what is wagered | the existing trip rule |
| half haul | what a fail keeps | the existing fail rule |

1. **Where it lives.** Each act offers The Proving beside its Endless Mine on
   the astrolabe's special offers: a `hall: "special"` dungeon per act whose
   waves are themed from that act's mob pool. Entering spends nothing; a
   finished Proving does not gate acts (the capstone and mine floor 3 do).
2. **The floor.** One arena room per floor, built for a fight: open middle,
   spawn points at the edges, the descent pad inside. No spawner gate and no
   chests to sweep; the objective is the wave ladder (`Waves n/m` on the bar,
   like `Spawners n/m`).
3. **The ladder.** A floor runs `provingWavesPerFloor` waves. A cleared wave
   gives a short beat (`provingWaveDelayTicks`) and the next rings in. Waves
   are mob groups themed to the act, sized by party, and they never include a
   mob a floor cannot satisfy (the PD-194 spawn-rule rule applies).
4. **The choice.** The last wave down opens the pad: DESCEND rolls the next
   floor (bigger waves, deeper pay), and the leaving-between-floors rule banks
   the haul in full as usual. There is no door board in the Proving; the
   choice is descend or bank, each wave.
5. **Guardian floors.** Every `provingGuardianEvery` floor runs a guardian
   wave instead (the existing `MineGuardians` per-act sets and their tier
   A/B/C pay), so the Proving reuses the mine's guardian content.
6. **Scaling.** Wave size = base + one per extra member; an ominous Proving
   (if offered) echoes waves as the heist crew echoes. Mobs drop normal
   mob loot only; the pay is the floor pay and guardian tiers.
7. **Fallback.** A Proving floor that cannot arm its waves falls back to an
   ordinary spawner floor under the shared objective fallback rule.

### Migration

None. New dungeon content only.

### Supersedes

- Nothing retired. The per-act-mine decision (2026-10-11) is amended in one
  place: the astrolabe's special offers grow from one (the mine) to two (the
  mine and the Proving) per act.

### Knobs

In `config/pocketdungeons.json`: `provingWavesPerFloor` (3),
`provingGuardianEvery` (3), `provingWaveDelayTicks` (60). Fixed in code:
wave size base and per-member step, guardian tier mapping.

### Open until played

Whether three waves per floor is the right chew versus one long wave;
whether the arena reads as a different place floor to floor (one room spec
may go stale fast); whether descend-or-bank is legible on the pad or needs a
two-lever choice; whether wave pacing at compass 20+ pressure-fits a
well-geared player; and whether the Proving cannibalises the mines or sits
beside them cleanly.

## 2026-10-15: Arena floors and phased bosses

Owner direction 2026-10-16: "boss floors should really just be an arena with
an engaging boss fight (current boss fights are not detailed/ long enough to
hold as a good bossfight)". Live evidence PD-208: both deep-mine guardians
died before their mechanics registered. The fix is not more health: at
compass 20+ any pool either pops or sponges. A boss lasts by gating its
phases on what the player does, not only on damage dealt.

| Number | What | Where it lives |
|---|---|---|
| 3 phases | every boss | `FinaleScript` phases per dungeon |
| bounds at 2/3 and 1/3 health | phase edges | `BossFight` |
| soft cap at the edge | no skipped phase | `BossFight` |
| `bossPhaseStaggerSeconds` 3 | transition tell | config |
| health x members | party scaling | `BossFight` |

1. **The room.** Every finale floor fights in a purpose-built arena room:
   an open fight space with the room's hazard anchors (vents, vats, beacons,
   coffins) placed by the spec. The existing final-floor room specs become
   the arenas; new finale rooms are built to this rule from the start.
2. **The framework.** `BossFight` owns the boss entity and an ordered phase
   list. Phase boundaries sit at health fractions (two bounds for a
   three-phase fight). Damage that would carry the boss past a bound clamps
   at the bound instead and the next phase begins, so burst play
   fast-forwards within a phase but can never skip one.
3. **The transition.** At each bound the boss staggers for
   `bossPhaseStaggerSeconds` (invulnerable, a tell the player can read:
   title plus particles plus sound), then the phase's mechanic arms.
4. **The phases.** A dungeon's `FinaleScript` declares its phases: phase one
   is the baseline fight, each later phase adds one mechanic from the boss
   workshop row for that dungeon. The workshop's per-dungeon mechanics are
   unchanged; the framework wraps them in order.
5. **Scaling.** Boss health scales per member; ominous floors echo the phase
   adds as they echo other waves.
6. **The gate.** The pad gate stays: the arena opens when the boss dies.
   Leaving mid-fight keeps the ordinary leaving rules.
7. **Pilot.** Copper Works first. The Foreman: phase 1 baseline vindicator;
   phase 2 the furnace vents pulse on a readable rhythm (workshop row 10);
   phase 3 the water vats bait a weakened Foreman while the vents quicken.
   The machinery exists; the phases make it land before he dies.

### Migration

None. `FinaleWave` stays for the wave lead-in; `BossFight` takes over at the
boss spawn. A save mid-finale just fights under the old code until that
finale is re-run.

### Supersedes

- Boss workshop (2026-10-11) structure: every "agreed (details open)" row
  keeps its mechanic but now lives inside the three-phase spine instead of a
  flat wave-plus-elite.
- PD-208 (mine guardians die before mechanics register) is the live
  verification driver for the pilot.

### Knobs

In `config/pocketdungeons.json`: `bossPhaseStaggerSeconds` (3). Fixed in
code: phase bounds (2/3, 1/3), the boundary clamp, health per member, the
per-phase tell text.

### Open until played

Whether the boundary clamp reads as a fair wind-up or as "the game ate my
crit"; whether three phases is the right count for capstones versus ordinary
finales; whether each dungeon's arena is big enough for its mechanics to
matter; and whether the Foreman's vat weakness reads without an explanation.

## 2026-10-17: Act 0, The First Night (prologue and tutorial)

A new player starts in a prologue act of one dungeon, The First Night, guided by Lemon (Alex's Allay, who lost Alex somewhere in the pocket dungeons). They gather wood, stone, coal and iron by hand, learn the basic parts of the loop one floor at a time, and finish at a single trial spawner. Finishing it unlocks Act 1 and the act never appears again.

### The model

| Thing | Rule | Lives in |
|---|---|---|
| Act | Act 0, a prologue before Act 1. Its one dungeon is the act's capstone | `ActProgress`, `DungeonDef.MIN_ACT` |
| Dungeon | The First Night, 5 floors, `act: 0` | `dungeon/first_night.json` |
| Floors 1 to 4 | Treeline (8 oak logs), Stone Rise (12 stone), Black Seam (8 coal ore), Rust Ravine (6 iron ore). Gather objective, no spawners | node `objective: gather` |
| Floor 5 | The Cave Mouth, exactly 1 trial spawner, no finale wave | node spawner cap |
| Starter tools | One bag, wooden axe, pickaxe, shovel (and bread), each tool tagged so a duplicate is deleted. Replaces the ten bags | `dungeon_bag/starter.json` |
| Lives | Infinite in Act 0, shown from Act 1 | `Lives`, run rules |
| Compass | In the first chest; Lemon comes out of it when the chest is closed | first-join tour |
| Scrap | Finishing Act 0 banks 2 scrap, which is the cost of Compass 2; Lemon explains scrap | `compassCostFirstLevel` |
| Lock | Completed once, per player, forever; hidden from the astrolabe | `Campaign` flag |

### Rules

1. **Entry.** Act 0 is on the astrolabe only while the player has not finished it. After the finish it is not shown at all (no locked door). A full `/dungeon resetkey` reopens it.
2. **Unlock.** The First Night is Act 0's capstone: clearing it completes Act 0, unlocks Act 1 and grants Compass 2 (2 scrap). Act 0 has no Endless Mine and no act gate other than this.
3. **First chest.** The player's first chest holds the Compass. Closing the chest after taking it is when Lemon appears and introduces herself.
4. **Starter bag.** There is no bag choice. The bag chest reads "there seems to be equipment left behind" with a single "Take it" button that hands over the wooden axe, pickaxe and shovel, and it can be pressed again for a replacement set at no cost. Each tool carries a starter tag (`pocketdungeons.starter_tool` with its kind). If a player ever holds two tools with the same tag, across the dungeon pack, Dungeon Storage and any container they have open (a chest, a run storage), the one with the lowest durability is deleted, so a spare set cannot be farmed. The sweep runs only when something changed (the bag chest button, a container opening or closing, a stack moving), not on a timer; if a reliable change hook is missing for one of those, a once-a-second check scoped to players currently in an Act 0 run covers it.
5. **Floor 1.** Gathering only, no fight. The bar and sidebar show the gather count in place of "spawners" ("Oak Log 3/8"). Floors 2 to 4 each introduce another common mechanic (open: which, see below). Floor 5 is a single trial spawner.
6. **Gather quota.** The quota is the floor's count, plus half of it per extra member. Spare blocks stand on the floor but pay nothing extra; the reward for gathering more is simply having more.
7. **Crafting.** The player must craft a stone pickaxe from floor 2's cobble and floor 1's logs to mine iron on floor 4. Crafting tables may be placed on a floor. Smelting is not required; Lemon only tells the player what a furnace does.
8. **Lives.** Act 0 has infinite lives (owner, 2026-10-17). A death costs nothing and never fails the dungeon, so there is no failure and no restart to exploit; failing sends a player to the start of the dungeon, which could be abused for loot. Lives are introduced in Act 1, where Lemon explains them on the first floor. Leaving a floor mid-run still follows the normal leave rule.
9. **Lemon's lines.** All of Lemon's Act 0 lines prepare the player for the dungeons in general. None gives per-floor gathering instructions.
10. **Diaries.** A diary anticipates the next dungeon or gives lore; it does not explain the dungeon just finished. The Act 0 diary (`entry_31`) is rewritten to look ahead. A lore pass over the existing diaries is a separate task.
11. **Hoarding.** The dungeon pack is a separate inventory from survival and persists between trips (the Home room is inside the dungeon dimension), so the finished-once lock is what stops repeated farming of starter materials.
12. **Presentation.** Act 0 gets thematic rooms for each floor (forest, rock outcrop, coal hillside, iron ravine, cave mouth), not the generic halls.
13. **The Proving.** Its sidebar and bar have the same "spawners" problem and get the same fix.
14. **Quit.** Quitting an Act 0 run (the Quit Door) resets Act 0 (owner, 2026-10-17: it is short already): the run restarts at floor 1, the dungeon pack and Dungeon Storage are wiped, and the starting chest and bag chest are available again. Nothing from an unfinished Act 0 run is kept.
15. **No loot before the finish.** Floors 1 to 4 pay no scrap and no completion chest or barrel rolls; the only Act 0 payouts are on the finish (2 scrap, the diary, Act 1). The Home lever does not work in Act 0 until the act is finished (owner, 2026-10-17); quitting is the only way out and it resets the act. This closes the loop where a player gathers, banks to Home, stores the materials in a Home room chest and repeats.
16. **Quit wipes the whole pack.** There is no per-item tag on gathered materials: a quit wipes everything carried, stored or taken from the bag chest (decision 2026-10-17, as built), so crafted planks, sticks and the stone pickaxe go too and the bag chest hands the gear out again. Finishing keeps whatever the player is carrying.

### Migration

A player with any recorded progress (a compass above 1, a finished dungeon or an unlocked act) is marked Act 0 done with Act 1 unlocked, and is never shown Act 0. A player holding one of the old bags keeps working; the ten bags stay as `hidden` data. The old tour steps that mention choosing a bag or a refilling kit are reworded.

### Supersedes

- The ten bags as the player's class (2026-10-04 and earlier); `BagTableTest` rules (food, no armour, stone cap, Pilgrim) lapse for the starter bag.
- The Act 1 Astrolabe row now holds 6 dungeons again (The First Night left it).
- Mineshaft and Lush Caves at compass 2 (2026-10-17, same day) stand: Compass 2 is what Act 0 pays.

### Knobs

`compassCostFirstLevel` (2), the gather counts in `first_night.json`, `actZeroScrap` (2). The quota growth per member is fixed in code (`GatherObjective.quotaFor`).

### Open until played

- Which common mechanic floors 2, 3 and 4 each teach (proposal now that Act 0 has no lives: loot chest and Dungeon Storage; the crafting table and a stone pickaxe; the lever and pad).
- Whether 2 scrap for Compass 2 should apply to everyone or only the first level.
- Whether the duplicate-tool sweep is enough: it does not see a tool handed to another player or kept in a closed Home room chest (the player can still only hold one set at a time, since two in hand are swept).
- Whether gathering without a fight until the last floor holds attention.

---

## 2026-10-17: Vertical floors (stair rooms and drops)

A floor can now go down: you take a stair room to the story below, and some loops let you drop through
a hole instead, if you dare.

Approved by the owner on 2026-10-17 as option A of the vertical layout brief, with every recommended
answer to its open questions. Research behind it: DunGen makes height from stair tiles whose doorways sit
at different heights; Unexplored puts one-way valves only on loops; Dead Cells found random structure
"illogical, chaotic" and kept its structure authored; Minecraft's own Trial Chambers climb between levels
with staircase pieces.

| What | Value | Where it lives | What is at risk |
|---|---|---|---|
| Stories per floor | at most 3 (entrance story plus 2 down) | dungeon node knob, default 1 (flat) | nothing; stairs are always there |
| Story pitch | 8 blocks (`CEILING_Y + 2`) | `RoomGeometry.STORY_HEIGHT` | |
| Direction | down only | engine rule | |
| A drop | one-way, optional, only on a loop | new directed edge kind | position: you land in a room you have not cleared, with no quick way back |
| A drop's landing | powder snow | drop rooms | |
| First dungeon to opt in | Mineshaft (Act 1) | `dungeon/mineshaft.json` | |

### The rules

1. **Down only.** Stories sit below the entrance story, never above. Bounds, protection, the bedrock
   envelope and return paths already reach down (M61).
2. **The way down is a stair room:** a two-story room entered on its top story and left through a door on
   its lower story. Real stairs, two wide; no ladders on the way on, so mobs follow and a fight on them is
   fair. Stair rooms sit on the spine and require nothing.
3. **Every edge is still a door in a wall.** Height changes only inside stair rooms, so a door mask stays
   4 bits per story and rotation is unchanged.
4. **A drop is a loop edge, never the spine.** It runs downward only, from a room with a hole in its floor
   into the room beneath, onto powder snow so the fall does no harm. The solvability pass counts the lower
   end as reached only from above; the spine stays provable with the Pilgrim seed as today.
5. **A drop may land in a room whose spawner is still live.** That is the wager.
6. **Stories may overlap in plan.** A lower story's cells can sit under the story above (same chunk
   column, no extra chunks); a grate in a floor shows the room below before you choose to drop.
7. **Opt-in per dungeon.** A dungeon that sets nothing stays flat and generates exactly as before.

### Naming

Internally a level of a floor is a **story** (the code already says so); "level" is the compass and
"layer" is a dungeon node, so neither is used. Players see **"1 down"**, **"2 down"** on the bar and the map.

### Build order

| Phase | Scope | Owed before the next |
|---|---|---|
| 1 | `PlanCell` gains a story index; story-aware geometry; zero behaviour change, pinned by a layout fingerprint over a seed sweep | the fingerprint matches before and after |
| 2 | per-story door masks, 3 or 4 generic stair rooms (theme placeholders), the generator's step down, Mineshaft opts in | one Mineshaft trip that goes down |
| 3 | overlapping stories, drops (directed edges in the solvability pass), powder snow landings, overlook grates | a drop taken live |
| 4 | the map board, `DungeonMapText`, the Lemon context and the "1 down" bar line | |

### Migration

None. No saved data holds a plan cell, and every existing dungeon stays flat until it opts in.

### Supersedes

- The M74 note "private lower stories, not a new layout engine" (`SituationSpecs`) for stair rooms only;
  existing private lower stories stay private.
- The rubble rule's "a two-story cell's lower story is always a bonus" applies to private lower stories,
  not to stair rooms, whose lower story is the way on.

### Knobs

Stories per floor (a dungeon node field, Phase 2), the drop chance per eligible loop (Phase 3), and
`RoomGeometry.STORY_HEIGHT` and `MAX_SPAN_Y` (fixed by the cell contract; change only with the templates).

### Open until played

- Whether players read a drop as a choice, and whether "1 down" is understood without explanation.
- How mobs path down stair rooms, and whether they chase a player through a drop.
- Whether an overlook grate leaks light or sound that the sculk rooms care about (`PressureSources`).
- Whether a live room under a drop is a fair wager or a trap that feels unfair.

## 2026-10-17: Gold may open a vault

The owner said the hopper interface, not the gold, made the toll room the worst room of a session (PD-186). Gold returns as the key of a vanilla vault: walk up, use one ingot, each player once. Hoppers stay out.

### The model

| Thing | Rule | Lives in |
|---|---|---|
| Gilded Vault | Dead-end loot room, `requires: ["gold"]`, one per dungeon, depth 2 and down. A vanilla vault behind iron bars takes one gold ingot and pays 2 to 3 rolls of iron, emeralds, arrows and an enchanted iron sword or pickaxe | `PayoffSpecs`, `dungeon_room/gilded_vault.json`, `loot_table/vaults/gilded_vault.json` |
| Payoff bias | A cell off the spine draws a room whose `requires` are all provided upstream 3 times as often as its weight says. Rooms whose requires are not met keep their weight and can still appear as a shut bonus | `RoomSelector.PAYOFF_WEIGHT_FACTOR` |
| Main path | Unchanged: the spine never requires a payoff room | `RoomSelector` |

### Rules

1. The bias reads only the tags rooms closer to the entrance provide, not the bag or party seed. A bias that depended on the bag made spur draws differ by bag, a spur's provides feed the spine, and the spine stopped being the same for every bag (`GraphSolvabilityTest.testSpineSameForEveryBag`). A consequence: a party's `mob` tag does not favour `plate_pair`.
2. A vanilla vault consumes exactly the key stack's count and pays each player once (read from `VaultBlockEntity$Server.tryInsertKey` in 26.2).
3. The Gilded Vault is never ominous and has no omen relief life; that stays the Barred Vault's.

### Supersedes

The gold half of 2026-10-09 item 6. `ominous_bargain` stays toll free.

### Open until played

Whether one ingot is the right price once players can find gold (supply chests, and `the_herd` gold blocks, which need an iron pickaxe).

---

## 2026-10-18: Campaign and endgame

Approved by the owner on 2026-10-18 with every recommendation of `docs/plans/2026-10-18-campaign-and-endgame-brief.md`
and option a for Dungeon Storage. The brief holds the research, the stories and the options not taken; this record is
the rule. Built 2026-10-18; see the status line at the end of this section.

The player's sentence: *In the campaign my compass tells me which dungeons to do next, and what's in my pack is at
risk; when I finish the campaign my compass wakes up, the Chart Room opens, and from then on it levels on scrap.*

### The frame: three layers

| Layer | Plays like | Owns |
|---|---|---|
| The floor | Lethal Company, REPO | fear from rules (light, sound, water, sight), not only health bars |
| The trip | Darkest Dungeon | push or go home: the haul at risk, shared lives, the lever |
| Home and the compass | Diablo, Path of Exile | the long climb: the campaign, the compass, gear, the room |

Gear lets you fight; it never makes you safe. A feature passes its layer's test: the floor (a veteran in netherite can
still fear it), the trip (it changes push or go home), home (it makes the next trip possible without making the floor
safe).

### The model

| Thing | Rule | Lives in | At risk |
|---|---|---|---|
| Campaign | Acts 0 to 5; each dungeon keeps its own level (D27); never scales to the player | `FloorLevels`, `DifficultyProfile` | |
| Campaign mob curve | `0.85 + 0.02 x level` (level 8 about vanilla, level 20 at 1.25), starting values | `mobScaleBase`, `mobScalePerLevel` | |
| Deaths harden the floor | 0 in Act 1, 0.1 from Act 2 | `omenDangerScalePerOmen`, per act | |
| Capstone | opens after 3 of the act's dungeons; the act's Endless Mine is an optional dive, not a gate | `capstoneDungeonsNeeded` (3), `ActProgress` | |
| Compass in the campaign | a quest line on the item: `Next: Mineshaft, Lush Caves or Infestation`, `2 more for the Spawner Dungeon`; no number, no gate | `Keystone` lore and name | never |
| Campaign scrap | none; the board's pay line names items | `DungeonScreen.previewGets` | |
| Compass after the campaign | the last capstone on the server shows `Your compass wakes up.`; Compass 1, 0 scrap; then scrap as the 2026-10-07 and 2026-10-09 records say | `DungeonLog` compass record | half the scrap haul on a fail |
| The pack | the 27 inventory slots are the haul, whatever the items' origin | the rescue | moves to a pot on a rescue |
| Safe | armour slots, offhand, the 9 hotbar slots, Dungeon Storage, home room chests | | never |
| Dungeon Storage | during a trip opens only at the Doors and at home; mid-run ender chests stop opening it | `RunStorage` | never |
| The pot | a decorated pot fixture, `<player>'s pack`, a 27 slot virtual menu, opened by its owner and the party | new | |
| An unemptied pot when the floor ends | keeps half | `potKeepPercent` (50) | half |
| A failed dungeon | every pot plus every member's pack keeps half | `failHaulKeepPercent` (50) | half |
| Endgame | the Chart Room opens after the capstone of the last act on the server (Act 2 for release) | `releaseMaxAct` (2) | |
| Uncharted door | a generated 3 floor dungeon from the finished acts' themes; at least half its affixes are rules; a guardian finale on the phased spine; level = compass + step 0 to 3; three doors, reroll per trip | new generator | the pack, and half the scrap haul on a fail |
| Endgame dive | the Endless Mine and the Proving merged: mining floors, every third floor an arena with waves and then the guardian; scales with the compass, no depth cap | `MineGuardians`, `proving_arena`, `BossFight` | |
| Lives | `4 + members` at the first door commit (solo 5, four 8) | `Omen.LIVES` becomes a function | |
| Loot band | the highest act finished (D10); the endgame chase is the score, the Loot percent and room materials, not power | `LootBands` | |

### Rules

1. **Our death.** Nobody dies in a dungeon: a killing blow becomes a rescue (`Instances.rescue`). Every pack rule
   happens inside the rescue, after the life is spent and before the teleport. Vanilla drops and keepInventory are
   never involved.
2. **The pot.** On a rescue every stack in the pack moves into a new pot on the block where the player fell. If that
   is lava, the void, deep water, a voided cell or a sealed ordeal room the player cannot re-enter, the pot stands at
   the nearest door of that cell. Never on the pad or in a door lane. Each rescue makes its own pot. The pot is a
   fixture and cannot be broken.
3. **Legibility.** The sidebar shows `Pots N` while any stand on the floor. The first pot of a trip says once: `Your
   pack stayed where you fell.`
4. **Floor end.** When a floor ends (descend, go home, a finish), an unemptied pot keeps half, into its owner's pack,
   or Dungeon Storage if the pack is full.
5. **Failing.** The fifth rescue makes its pot, then the dungeon fails: every pot of the dungeon and every member's
   pack keeps half, and the party goes home with it (`failReturnsHome`). This replaces "You keep what you carry".
6. **Half, exactly.** Each stack keeps half rounded down; the single items left over keep every other one, best tier
   first, seeded by the trip.
7. **Leaving and disconnecting.** Leaving a floor in progress fails the dungeon for the leaver (PD-192), so rule 5
   applies to them. A disconnect is not a rescue; the reconnect grace still holds the run.
8. **Exempt.** Act 0 makes no pots. A rescue with no room to return to (admin, untimed, visit) makes none.
9. **Campaign gates.** Campaign doors are gated by act order and the capstone rule. `unlockLevel` stops gating them;
   locked doors read `Finish Act 1` or `Finish 3 dungeons`, never `Compass N`.
10. **Endgame gates.** The Chart Room is reached from the Astrolabe Room. A finished act adds its themes to the
    uncharted doors' pool. The compass never drops.
11. **Never soft-lock.** An uncharted door's spine passes the same solvability check; a pot never blocks a path.

### Migration

- A player mid campaign keeps their compass level and bar in the save, unread, as their starting endgame compass.
  The item switches to the quest line on the next join, with one message: `Your compass now shows the way. It will
  count again when the campaign is done. What's in your pack is at risk. Your hotbar, armour and Dungeon Storage are
  safe.`
- A player who has finished the last shipped act's capstone gets the Chart Room at once and keeps their level.
- Pots are floor state: they end with their floor and never outlive the run. (Built amendment, 2026-10-18: the contents are journalled and stored in `DungeonLog` while the pot stands, so a crash or restart returns them in full to the owner's Dungeon Storage; see the T4 report.) A "campaign done" flag only if the stored act set cannot answer it.
- Act 0 ends on the first quest line, `Next: the Mineshaft`, not on Compass 2.

### Supersedes

- D23 (the compass gates dungeons): campaign only; replaced by act order and the capstone rule.
- D29 and the 2026-10-11 Endless Mine act gate rows (every dungeon plus the mine's floor 3): replaced by three
  dungeons and an optional mine.
- 2026-10-07 Haul and Blood Doors, "Failing", and `Instances.java:2233`: a fail settles packs and pots at half (and,
  in the endgame, the scrap haul as before). Its "Earning" applies to the endgame only.
- 2026-10-09 scrap curve (Q2): endgame only.
- U8 Stage 1, "dying inside costs nothing" (`Instances.java:2168`): a rescue now moves the pack into a pot.
- J6, Dungeon Storage from any ender chest mid-run: during a trip only at the Doors and at home.
- J3, five lives per trip: `4 + members`.
- 2026-10-15 The Proving: retired as its own door; kept as the endgame dive's arena floors.
- 2026-10-17 Act 0: the 2 scrap finish (`actZeroScrap`, `compassCostFirstLevel`) and Compass 2; Act 0 ends on the
  quest line.
- VISION section 2, "progress is a key level": endgame only.
- Plan `2026-10-17-design-review.md`: item 2 narrowed to the endgame; items 4, 5 (amended: the release ships the Chart
  Room), 6 and 8 (moved into the Chart Room) are carried in by this record. Items 1, 3, 7 and 9 are still proposals
  for the owner, except that Act 0's last lever banks the pack and the campaign board names items.

### Names (owner approved 2026-10-18)

New player-facing words: **Chart Room**, **uncharted door**, **pack**, **pot**. Settled words keep their meaning:
compass, scrap, haul, lives, charts.

### Knobs

`mobScaleBase` (0.85), `mobScalePerLevel` (0.02), `omenDangerScalePerOmen` (per act: 0 in Act 1, 0.1 from Act 2),
`capstoneDungeonsNeeded` (3), `releaseMaxAct` (2), `potKeepPercent` (50), `failHaulKeepPercent` (50),
`livesPerExtraMember` (1), the uncharted door's floor count (3) and step range (0 to 3), the rule affix share (half),
the Chart Room door count (3), the endgame level cost (`scrapCostBase`, `scrapCostEvery`).

### Held

A timed trial (Greater Rift style) until the uncharted doors have been played. One heavy thing (a relic carried to the
pad) as a later uncharted floor type.

### Build order

| Phase | Scope | Owed before the next |
|---|---|---|
| 1 | curve retune, capstone after three, quest-line compass, no campaign scrap, migration | one Act 1 trip: the compass names the next dungeons |
| 2 | the pack and the pot, Dungeon Storage at the Doors only | a pot retrieved under pressure, and one left for half |
| 3 | the Chart Room and uncharted doors, the compass wakes up | one uncharted door cleared live |
| 4 | the endgame dive (mine plus arena) | a guardian that lives long enough for its rule |
| 5 | one heavy thing | a relic carried to the pad by a party |

### Open until played

Whether the quest line reads as guidance or a chore list; whether `Your compass wakes up.` lands as a reward;
whether walking back for a pot is a story or a chore, and whether `Pots N` is read; whether hotbar stuffing becomes
the default; whether a walk back to the Doors for Dungeon Storage feels like a price or a nuisance; whether three
uncharted floors suit a single session trip; whether rule affixes keep a veteran afraid.

**Status: Built (2026-10-18)** on branch `feature/campaign-endgame` (T0 to T7, merged from `feature/rm-*`). Deviations are listed in `docs/reports/campaign-endgame-build.md`. The timed trial stays held; live checks are L104 to L114.
