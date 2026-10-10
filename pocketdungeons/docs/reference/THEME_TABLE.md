# Theme and spawner table (proposal)

Status: **proposal, not implemented.** Written 2026-10-02 from playtest
`2026-10-02-1.md` ("themed spawners should be narrow and consistent, with a rare
chaotic one"; the player's theme ideas at 12:47 and 12:49). Implementation waits
for owner sign-off on the open questions at the end.

## 1. What exists today

### Run themes (`dungeon_theme/*.json`)

A run theme picks the room look (`processors`), the loot flavour (`loot_suffix`)
and which spawner family a themed room draws from (`spawner_prefix`). A theme
with no prefix falls back to the generic spawners.

| Theme | Spawner prefix | Loot suffix | Notes |
|---|---|---|---|
| Basalt Foundry | `basalt_foundry` | `basalt_foundry` | blaze + magma cube |
| Blackstone | none (generic) | none | generic families since 2026-10-02 |
| Copper Works | `copper_works` | `copper_works` | zombie + creeper |
| Deepslate | `crypt` | none | zombie + skeleton |
| Drowned Vault | none (generic) | `_drowned` | generic families; no drowned mobs yet |
| Ender Archive | `ender_archive` | `ender_archive` | enderman + silverfish |
| Endless Mine | `crypt` | none | zombie + skeleton; materials loot role |
| Frostworks | `frostworks` | `frostworks` | stray + zombie |
| Infestation | `infestation` | none | spider + cave spider |
| Ossuary | `ossuary` | `ossuary` | skeleton + stray |
| Prismarine | none (generic) | none | generic families; no guardian mobs yet |
| Rootworks | `rootworks` | `rootworks` | spider/cave spider + witch |

### Spawner configs (`trial_spawner/*`)

Each config is a normal and an ominous file. Tiered configs exist for tiers 1 to
3 and scale `total_mobs` 4, 6, 8 and `simultaneous_mobs` 2, 3, 4.

| Config | Mob pool (normal) | Used by |
|---|---|---|
| `undead_tier_N`, `bones_tier_N`, `spiders_tier_N` (new 2026-10-02) | zombie+husk, skeleton+stray, spider+cave spider (tier 1: one mob each) | themeless rooms, one family per cell |
| `tier_1` / `tier_2` / `tier_3` | broad mixed (3, 6, 11 mob types) | the chaotic spawner only (5 percent of themeless cells, `chaoticSpawnerChance`) |
| `crypt_tier_N` | zombie, skeleton | Deepslate, Endless Mine |
| `copper_works_tier_N` | zombie, creeper | Copper Works |
| `frostworks_tier_N` | stray, zombie | Frostworks |
| `ossuary_tier_N` | skeleton, stray | Ossuary |
| `infestation_tier_N` | spider, cave spider | Infestation |
| `rootworks_tier_N` | spider/cave spider, witch | Rootworks |
| `ender_archive_tier_N` | enderman, silverfish | Ender Archive |
| `basalt_foundry_tier_N` | blaze, magma cube | Basalt Foundry |
| `kennels_tier_N` | pillager, wolf, vindicator from tier 2 (added 2026-10-09, not in the original table) | Kennels |

Room-specific configs: `blaze_cellar` (blaze), `creeper_kennel` (creeper),
`slime_pit` (slime), `wither_loft` (wither skeleton), `kennel_crossing` (wolf),
`breeze_arena` and `ice_run` (breeze), `thicket` (cave spider), `bogged_marsh`
(bogged), `ledge_archers` (skeleton), `sensor_gallery` (zombie; retired 2026-10-09, the Hush Gallery replaced it), `hold_the_plate`
and `barred_vault` (zombie, skeleton), `the_raid` (vindicator, evoker).

Equipment: spawner mobs wear our own `equipment/tier_1..3_*` tables (untrimmed).
Vanilla trial chamber equipment is no longer used anywhere.

## 2. Proposed themes

Rarity: **common** (about 45 percent of rolls together), **uncommon** (35),
**rare** (15), **chaotic** (5). Tiers 1 to 3 scale `total_mobs` 4/6/8 as today;
"tier change" says what else changes.

| Theme (spawner family) | Mob pool and weights | Tier change | Rarity | Fits | Equipment | Identity |
|---|---|---|---|---|---|---|
| **Zombies** | zombie 6, husk 3 (t2+), drowned 1 (wet rooms) | t1 zombie only; t2 adds husk; t3 adds baby zombies 1 | common | any combat room | tier armour, melee | A shambling crowd. Swing and keep moving. |
| **Skeletons** | skeleton 6, stray 3 (t2+) | t3 adds bogged 1 | common | rooms with cover and ledges | bows at the ranged drop floor 0.05 | Archers. Break line of sight. |
| **Zombie + skeleton (Crypt)** | zombie 5, skeleton 4 | t3 adds husk, stray | common | existing crypt rooms | tier armour and bows | The classic mix, kept narrow. |
| **Vanilla** | zombie 4, skeleton 3, creeper 2 | t3 adds spider 1 | uncommon | open rooms | tier armour | What the player expects from a dungeon. |
| **Creepers** | creeper 8, charged creeper 1 (t3) | t2 groups of 2 | uncommon | rooms with rubble or breakable walls (ties to Sapper and rubble) | none | Short fuse, wide blast. Rewards Sapper's Bag. |
| **Bone choir** | skeleton 5, wither skeleton 2 (t2), wither skeleton 4 (t3) | wither share grows by tier | rare | deep floors (depth 3+) | stone swords, bows | Slow poison, heavy hits. Wither rose loot. |
| **Mounted** | spider jockey 4, skeleton horseman 2 (t2+), chicken jockey 2 | t3 adds zombie horseman | rare | long open rooms | none | Fast and tall. Punishes standing still. |
| **Nether** | blaze 3, magma cube 3, piglin 2, wither skeleton 1 (t3) | t3 adds hoglin 1 | uncommon (always with Basalt Foundry) | lava rooms | gold armour on piglins | Heat and fire. Fire Resistance pays off. |
| **Cubes** | slime 5, magma cube 3, small cubes on death | t2 larger starting size; t3 adds a big cube 1 | uncommon | slime pit, sump | none | Splitting swarms. Sweep attacks shine. |
| **Farm** | chicken 4, pig 3, sheep 3, cow 2 (all hostile-neutral stand-ins, drop food) | single tier; only t1 | common, tier 1 only | tutorial floors, level 1 to 3 keystones | none | An easy room that pays food. A breather. |
| **Ender** | enderman 4, endermite 3, silverfish 2 | t3 adds shulker 1 | rare | ender archive rooms | none | Teleporters. Look away, fight in the corners. |
| **Arachnid** (Infestation) | spider 5, cave spider 4 (poison) | t3 adds a spider jockey 1 | uncommon | existing infestation rooms | none | Webs and poison. Wall climbers (see PD-110). |
| **Rootworks** | spider 3, cave spider 3, witch 2 | t3 adds a second witch | uncommon | existing rootworks rooms | none | Potions thrown from a distance. |
| **Pillager raid** | vindicator 3, pillager 3, evoker 1 | t3 adds ravager 1 | rare | the_raid room only | iron axes, crossbows | Grouped and loud. A set piece. |
| **Chaotic** | the broad `tier_N` pools as they are today | as the broad pool | chaotic | any themeless combat room | tier armour | Everything at once. Announced (below). |

Existing themes keep their prefix mapping above; this table is the pool each
prefix should converge on. New families need only new `trial_spawner/<family>_tier_N`
files plus a theme `spawner_prefix`; no Java change except the generic picker if
the generic families grow past three.

## 3. Rules the table follows

- **Narrow by default.** A normal spawner has at most two mob types (three for
  Vanilla). Anything wider is chaotic.
- **One family per room cell,** chosen from the plan seed and the cell, so a
  preview and the built floor agree.
- **Themed rooms keep their theme.** Rooms that name a mob (blaze cellar, kennel,
  slime pit) never take the generic roll.
- **Tier 1 is gentle.** Tier 1 pools are single-mob except Vanilla and Nether.
- **Equipment is part of the identity** and always untrimmed; trims come from
  chests and vaults only (PD-106).

## 4. The chaotic variant

- Appears on **5 percent** of themeless combat cells (`chaoticSpawnerChance`),
  never on a Silenced floor's first room and never in an entrance or exit.
- Announced: when a player first enters the room, an overlay line "The room
  cannot make up its mind." and the existing ominous spawner activation sound.
  A themed floor's affix line never mentions it.
- Pays slightly more: its key and emerald ejects use the next tier's weights.

## 5. Open questions for the owner

1. Do farm animals spawn as real passive mobs fought for food, or as zombified
   stand-ins? The table assumes passive mobs that the trial spawner spawns and
   that drop food; a trial spawner counts them as "mobs to clear".
2. Should Vanilla be the default for themeless rooms instead of three equal
   families (undead, bones, spiders)?
3. Mounted and Pillager raid are the hardest. Are they tier 3 only, or allowed
   earlier with fewer mobs?
4. Should the chaotic spawner be announced at all, or is the mixed pool itself
   the announcement?
5. Rarity numbers above are guesses. Do you want per-theme weights in
   `dungeon_theme/*.json` (a new `spawner_weights` field) instead of one fixed
   table?
6. Drowned Vault and Prismarine have no mobs of their own (drowned, guardians
   need water). Should they get families, or stay generic?
