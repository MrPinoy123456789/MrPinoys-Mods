# Zones spec: Endless Mine and Woodland Mansion

Status: design, approved in direction by the owner on 2026-09-26. Not built.
Builds on `docs/AUDIT_2026-09.md` and the wave plan recorded there. Zones are
wave 4; they depend on wave 2b's zone rules hook (section 2).

## 1. Why zones

Pocket Dungeons has to stand as its own game. Variety is what keeps a player
going for hours, and today it is thin: twelve themes, most of them the same room
library under a different palette, one boss (a single Drowned mob at triple
strength, `BossContent`), and an Endless Mine that is a deepslate palette with
escalating loot tiers. A zone is a place that **plays** differently, not one
that looks different.

Rules every zone follows:

1. **Vanilla only, server-side only.** Vanilla blocks, mobs, items (with
   `custom_data` where needed, as keystones already do), sounds, boss bars,
   text displays. No client mod.
2. **The strict resource economy holds.** Blocks, tools and durability are
   scarce everywhere. A zone may change what is scarce, never make things free.
3. **One loop.** Every zone runs inside the ordinary loop: staging room, door,
   floor, checkpoint, bank. A zone changes floors, rules and rewards through the
   zone rules hook; it never forks the lifecycle again (Pocket2 and the old Mine
   both did, and paid for it).
4. **Every floor is solvable from what the run supplies.** A zone's
   requirements are declared, and the solvability pass proves them against the
   party's kit plus what earlier cells supply.
5. **Omen is the pressure.** Each zone expresses omen in its own fiction, but
   it is always the same number on the same boss bar, feeding the same band.

## 2. The zone rules hook (wave 2b builds this)

Built in wave 2b as `ZoneRules`, read from a `dungeon_theme` file's optional
`rules` block; the field names, ranges and what each one does today are in
`docs/INTEGRATION.md` section 1.6. The table below is the design it grew from.

A zone is data plus a small rules object. Proposed contract, to be refined in
wave 2b:

| Field | Meaning | Default (ordinary dungeon) |
|---|---|---|
| `id` | Zone id, matches a `dungeon_theme` / `dungeon_adventure` pair | theme id |
| `floorKind` | How a floor is planned: `standard`, `shaft`, `drift`, `scenario` | `standard` |
| `floorSequence` | Pattern of floor kinds within an interval, repeating | `[standard]` |
| `capstone` | What the last floor of a full interval is: `none`, `boss`, `scenario_end` | `none` |
| `depthBonus` | Payout multiplier added per floor since the last bank | small |
| `omenBase` | Omen added at the start of each floor after the Nth | 0 |
| `omenScale` | Multiplier on dwell and source omen | 1.0 |
| `lootRole` | Which faucet the zone is (section 5) | `gear` |
| `unlockLevel` | Keystone level at which its door can be offered | 1 |
| `kitTopUpScale` | Multiplier on the safe-visit kit top-up | 1.0 |
| `requires` / `supplies` | Solvability tags for its room pool | per room |

Wave 2b implements the hook with the current dungeon as the default zone and
the current Mine as a second zone with its current behaviour. The mechanics in
sections 3 and 4 plug into it in wave 4.

---

## 3. Endless Mine

### 3.1 Fantasy

You are going down. Not through rooms, but through rock. Every floor you either
drop further or push sideways toward the next drop, the stone gets older and
stranger, and the lift home is always one lever away. The question the Mine
asks is **how deep do you dare go before you bank**.

### 3.2 Floor structure

Floors alternate:

- **Shaft floor** (vertical): one tall column, two to four stories high. You
  enter at the top and leave by a door at the bottom. The problem is getting
  down.
- **Drift floor** (horizontal): an ordinary-width floor of tunnels choked with
  rock. The problem is getting through, and it is where the Mine pays out.

`floorSequence = [drift, shaft]`. No capstone and no final floor: the Mine
never forces a safe room. Every checkpoint has the bank lever, dressed as a lift
call ("The cage rattles down from somewhere far above.").

**Depth is narrative, absolute Y is not.** A strict descent would run out of
world height in about fifteen stories. Each checkpoint's staging room is a
sealed box, so the next floor can be stamped back at the slot's working height;
the player feels a continuous descent because every shaft goes down and no
floor ever goes up. This is the same trick as the silent room relocation.

### 3.3 Shaft mechanics (getting down)

Each shaft room declares which of these it uses. Every shaft must offer at
least two routes, one of which costs only placed blocks.

| Mechanic | How it plays | Build notes |
|---|---|---|
| Staircase drop | A sheer drop with ledges too far apart. Place blocks to stair down. | Plain template; `requires: blocks >= N` |
| Water clutch | A deep pool at the bottom rewards a water-bucket drop; miss and you land on a ledge and lose time. | Kit or drift-supplied bucket |
| Ladders and scaffolding | Fast and safe, but every piece is a kit item. | Supplied by kit or the drift above |
| Dripstone chimney | Stalactites drop as you pass under them; move ledge to ledge under overhangs. | Java watcher breaks the support block when a player enters a trigger volume, same pattern as `Locks` and `CollapsingBridgeHandler` |
| Slime bounce | A slime pad halfway down: land it and you skip a stretch for free. | Vanilla slime behaviour |
| Cave-in behind | Once you drop past a line, gravel falls in above you. No climbing back. | Java trigger places gravel above the entry; gravity does the rest |
| Rope lift | A broken lift cage; repair it with chain or iron from the drift to ride down safely. | Lock of kind `ITEM_ANY` on a lever |

### 3.4 Drift mechanics (getting through)

| Mechanic | How it plays | Build notes |
|---|---|---|
| Ore seams | Tunnels blocked by stone with ore veins. Mining costs durability; ore and cobble are the payout and the next shaft's block supply. | Breakable-block marking, same system as the "right tool for the job" exemption |
| Hidden pockets | Lava or water sealed behind a one-block shell. Mine carelessly and you open it. | Template-authored; ties to the Molten affix |
| Infested stone | Some blocks are vanilla infested stone. Mine the wrong one and the wall wakes. | Vanilla |
| Collapsing bridges | Planks over a chasm that give way after you cross. | Existing `CollapsingBridgeHandler` |
| Magma crossing | Stepping stones over lava; magma blocks punish standing still. | Vanilla |
| Broken rails | A minecart run with a missing rail; place one from your kit. | Vanilla minecarts |
| Darkness | Unlit tunnels with classic monster spawners that stop spawning once lit. Torches become a real choice. | **UNVERIFIED**: confirm 26.2 spawner light rules before relying on this |

**The self-sufficient loop:** drifts cost durability and give blocks and ore;
shafts cost blocks. A player who mines well descends cheaply. This is the strict
economy doing the level design, and it is why the Mine should never hand out
blocks for free.

### 3.5 Depth layers

| Depth (floors) | Layer | Palette | Threats | Payout |
|---|---|---|---|---|
| 1 to 5 | Upper workings | stone, oak supports, rails | zombies, skeletons, spiders | coal, iron, copper |
| 6 to 11 | Deepslate | deepslate, tuff, dripstone | cave spiders, silverfish, creepers | iron, gold, lapis, redstone |
| 12 to 17 | Deep dark | sculk, deepslate tiles | sculk sensors and shriekers (omen), wardens are **never** spawned | diamond, echo shard, amethyst |
| 18+ | Magma core | basalt, blackstone, magma | magma cubes, blazes, lava | gold, ancient debris (rare), netherite scrap (very rare) |

A layer transition floor marks each boundary: a set piece that tells the player
things just changed.

### 3.6 Mine omen: instability

The Mine's omen is the rock settling. Same number, same bar, own fiction:

- Sources: dwell (existing), sculk sensors and shriekers (existing), explosions,
  and **every floor past the third since the last bank adds +1 base omen**
  (`omenBase`). Pushing deeper is always a gamble.
- Consequences, in world: at omen 2, rumbles and small gravel falls in the
  current cell; at omen 3, a cave-in closes one alternate route; at omen 4, the
  cave-ins start chasing you. The boss bar says what is happening.

### 3.7 Rewards and banking

- `depthBonus`: each floor since the last bank adds to the payout multiplier.
  With `omenBase` rising, there is a real push-your-luck decision every
  checkpoint.
- Payout is raw materials: the Mine is the game's **materials faucet**
  (section 5). Ordinary dungeons pay gear.
- Keystone banking uses the same average-of-doors rule as everywhere else, so
  the Mine cannot out-level the normal loop.
- Unlock: keystone level 10.

---

## 4. Woodland Mansion

### 4.1 Fantasy

A scenario, not a corridor. The mansion belongs to someone. Three wing lords
hold its halls, and their master waits behind a door that only their seals will
open. You are not exploring; you are laying siege.

### 4.2 Structure

One mansion is one large floor (about 13 to 16 cells) on a fixed topology with
randomised room fill:

```
                     [ Throne ]
                          |
                  (three-seal door)
                          |
[ West wing ] ---- [ Grand hall ] ---- [ East wing ]
                          |
                   [ North wing ]
                          |
                       (entry)
```

- **Grand hall:** the hub. Safe once cleared.
- **Three wings:** each is a corridor, two to three rooms, and a lord's
  chamber.
- **Throne room:** sealed by a `Locks` gate needing all three seals (three
  frame slots of kind `ITEM_KEY`, one seal each).
- The planner needs a `scenario` floor kind that places a fixed graph and fills
  each slot from a tagged room pool. Wings may appear in any order.

The mansion is offered as a door at a checkpoint once the keystone reaches 25,
costs fuel like a Greater door, and counts as **two floors** for banking. After
the final boss the bank lever is the natural exit, but bank-anywhere still
lets a party push on.

### 4.3 Wing lords

Each lord has its own boss bar, two or three phases at health thresholds, and
drops a **seal** (a vanilla item with `custom_data`, bag-tagged so it never
leaves the dungeon).

| Lord | Base mob | Fight |
|---|---|---|
| The Summoner | evoker | Vex waves that speed up as its health falls; fang lines across the chamber. Phase 2: it teleports between two balconies. |
| The Champion | vindicator | Axe hits break shields. Phase 2 below half health: berserk (speed, knockback resistance) and it calls two vindicators. |
| The Beastmaster | ravager with a pillager rider | The arena's pillars crumble when the ravager charges into them, removing cover as the fight goes on. Kill the rider first or it heals the beast. |
| Rare variant: The Illusionist | illusioner | Replaces one lord about one mansion in five. Blindness and decoys. Vanilla mob, never naturally spawned. |

### 4.4 The final boss: the Arch-Illager

An evoker lord, three phases:

1. **The Court:** vex waves and fang lines, the Summoner's tricks at full force.
2. **The Guard:** invulnerable while two champions stand; kill them to break
   the ward.
3. **The Beast:** summons a ravager and fights beside it. Enrages at 20%.

Scaling uses the existing mob scaling plus party size (the solvability pass
already knows it).

### 4.5 Boss framework (engine work)

`BossContent` is 86 lines and handles one mob. Wave 4 replaces it with a small
data-driven framework:

- A boss definition: base mob, health multiplier, phases.
- A phase: a health threshold and a list of actions: spawn adds, become
  invulnerable until tagged adds are dead, run an arena change (a named block
  edit set in the room's metadata), apply an effect, say a line.
- A vanilla boss bar per boss, visible to members in the room.
- Pack-authorable JSON, so the Drowned Warden, the lords and future capstone
  bosses share one implementation (and so other servers can add bosses without
  Java, which is `VISION` section 6's platform promise).

### 4.6 Mansion mechanics

- **Alarm bells:** the first time an illager in a room with a bell targets a
  player, the bell rings: a reinforcement wave and +1 omen. Clearing a room
  quietly pays.
- **Secret rooms:** dark oak panels that are marked breakable hide side rooms
  with a vault and a harder spawner. This is where the Pocket2 replacement
  lives.
- **The caged allay:** an optional objective. Free it with a key found
  elsewhere in the mansion for -1 omen and a small loot bonus.
- **Objectives on the bar:** "Wing lords 1/3", then "The throne is open".

### 4.7 Rewards

The mansion is the **trophy faucet**: things you cannot get anywhere else, for
the home.

- A trophy per lord and one for the Arch-Illager (a named banner or a textured
  player head), placeable in the room and kept by room saves.
- Vex and ward armour trims at their vanilla-appropriate rarity.
- A dark oak mansion room shell unlock.
- Emeralds and Forge materials at a high tier.
- **No totems of undying.** Vanilla evokers drop them; the mansion's loot
  tables must override that. A totem is a free life in survival, and death is
  already not the failure state inside a dungeon.

---

## 5. Balance

### 5.1 Each zone has one economic role

| Zone | Role | Pays | Costs hardest |
|---|---|---|---|
| Ordinary dungeon | Gear faucet | gear, emeralds, some materials | durability, fuel for Greater doors |
| Endless Mine | Materials faucet | ores, blocks, rare materials at depth | blocks, pickaxe durability, torches |
| Woodland Mansion | Trophy faucet | trophies, trims, shells, high-tier Forge materials | fuel to enter, consumables in boss fights |

Ordinary dungeon chests should carry fewer bulk materials once the Mine exists,
so the Mine has a reason to exist.

### 5.2 Targets

- **Reward per real minute is roughly equal across zones at the same keystone
  level.** Omen risk raises the reward; time spent does not.
- **Kit:** a clean interval ends with about a quarter to a third of the kit
  left; a messy one runs dry near the end. The safe-visit top-up (by band)
  restores it toward baseline, never above.
- **Keystone pace:** banking is the average of the floors' door steps in every
  zone, so no zone is a faster ladder. Zones differ in what they pay, not in how
  fast they level you.
- **Unlock ladder:** Mine at 10, Mansion at 25, further capstone bosses at
  later milestones (to be set from playtest data). The reward for climbing is
  new places.

### 5.3 Measure it

Balancing without numbers is guessing. Each completed floor logs one line:
zone, floor kind, keystone level, party size, time, omen and band, blocks
placed, durability spent, deaths, chests opened. A `/dungeon admin balance`
command summarises the last N floors per zone. Tuning knobs live in the zone
JSON and config, not in code.

---

## 6. Build plan (wave 4)

| Step | Work | Depends on |
|---|---|---|
| 4.1 | Boss framework (4.5); port the Drowned Warden onto it | wave 2b hook |
| 4.2 | Floor telemetry and the balance command (5.3) | wave 2b |
| 4.3 | Mine: shaft and drift floor kinds, stories beyond `MAX_SPAN_Y = 2` for shafts, Y reset at checkpoints, depth layers, instability consequences | 4.2 |
| 4.4 | Mine content: about 6 shafts, 8 drifts, 3 layer transitions, 4 hazard set pieces | 4.3 |
| 4.5 | Mansion: scenario floor kind, seal locks, alarm bells, secret panels, allay | 4.1 |
| 4.6 | Mansion content: grand hall, 4 wing corridors, about 12 rooms (library, map room, bedrooms, dining hall, chapel, storage, spider room, wool room, 3 secret rooms), 3 lord arenas, throne room | 4.5 |
| 4.7 | Balance pass from telemetry | 4.2, playtests |

Engine risks to check against the 26.2 jar before building: dimension height
and the slot origin's Y (how many stories a shaft can own), spawner light rules
(3.4), whether a two-story cell can open the layer between its stories for a
double-height throne room, and illager and ravager AI inside 16-wide cells.

Room building is the real cost of this wave. Procedural drafts can come from
`RoomTemplateGenerator`, but hand-built rooms in the room editor
(`/dungeon admin buildroom` and `saveroom`) will look far better.

## 7. Open questions

1. Should a mansion be a full interval on its own, or one floor inside a normal
   interval? (This spec assumes one floor worth two for banking.)
2. How often should a mansion door be offered: any checkpoint once unlocked, or
   rarer, tied to a weekly bounty?
3. Party scaling for lords: health only, or extra adds as well?
4. Should Mine depth reached be a permanent record (a personal best shown in the
   room), a weekly bounty, or both?
