# Wild lodestones: implementation plan (2026-10-30)

Builds the record `docs/DECISIONS.md` "2026-10-30: Wild lodestones". The record wins where this plan disagrees; the
builder notes any deviation in the record. House rules: `CLAUDE.md` (no em dashes, no spaced double hyphens), player text
per the `player-text` skill, dialogs per `dialog-screens`, knobs per `tune-knobs`, tests per `add-tests`. Java files may
be CRLF; edit with `tools/pyedit.py`.

## What the code already gives us

| Need | Existing piece | Use |
|---|---|---|
| A dungeon with no keystone, lobby or staging room | `RunLifecycle.enterUntimed`, `Instances.buildLayout`, `LayoutStamper.stamp(StampParams)` (takes a `NodeStamper.Context` and loot bands) | The template for the wild build. `untimed` records never expire |
| One floor from a dungeon definition | `NodeStamper.contextFor(dungeonId, nodeId, ...)`, `LootBands`, `TripView.def` | Pick one node of an existing `dungeon/*.json` |
| Slot, origin, force-load, teardown | `InstanceRegistry.allocateSlot/originForSlot`, `Instances.forceLoad`, `InstanceTeardown.teardown` | Unchanged |
| Many players in one record | `InstanceRecord.members`, `InstanceRegistry.byMember`, `Instances.admit` | One shared record per lodestone |
| The reward chests | `RunLifecycle.placeFloorRewards` (private) calling `TrialContent.placeRewardContainers` and `promisedItems` | Make package-private, call with `finishedNow=false` |
| The clear check | `RunLifecycle.padGatesRefuse` (needs a player only for messages) | Split into a pure `padRefusal(server, record, level)` both paths call |
| Dialogs | `DialogKit` (`common`, `button`, `notice`, `list`), `DialogScreens`, `DialogRouter` | Two new screens |
| Persistence | `AtomicFiles`, `RoomStore` style JSON under the world `data` dir | Wild state file |
| Mob counts by players | Vanilla trial spawners already scale with nearby players | Nothing to build for decision rule 9 |
| Break protection | A jigsaw block has hardness -1 and a huge blast resistance | Survival players cannot break it; nothing to build |

## What has to change

1. **Inventory:** `InventorySwap.reconcile` stashes on entering `pocketdungeons:void` and restores on leaving. In wild mode
   it must never run `enterVoid`. It must still run `leaveVoid` for any player already stashed (a world that ran the
   campaign first), so nobody is left without their items.
2. **Owner and cap:** `InstanceRecord.owner` is a non-null UUID and `refuseAtInstanceCap` limits instances. Wild records use
   a fixed nil owner and a separate `wildMaxActive` limit.
3. **Pad exit:** untimed records treat the terminal pad as the way out (`Instances` near line 2903). A wild record must not.
4. **Campaign writes:** `completeRun` writes `DungeonLog` (completions, themes, finish bonuses). The wild clear never calls
   `completeRun` or `advanceFloor`, so none of that runs.
5. **Death:** audit every death and respawn hook (`ALLOW_DEATH`, `failRunOmen`, lives, rescue, respawn point) for the
   dungeon dimension. Wild death must be vanilla: items drop, the player respawns at their normal spawn.
6. **Restart:** instance records are memory only. See "Restart and persistence".

## Persisted state (`WildState`)

One JSON file in `<world>/data/pocketdungeons-wild/lodestones.json`, written through `AtomicFiles`. Times are wall clock
epoch seconds so a stopped server does not freeze the clocks.

Per lodestone: `id`, `x,y,z` and dimension of the entrance block, `dungeon` (wild dungeon id), `level`, `createdAt`,
`expiresAt`, `phase` (`READY`, `IN_PROGRESS`, `CLEARED`), `clearedAt`, `resetAt`, `lostAndFound` (positions of the chests
placed at collapse). Per player: last lodestone id (so a player found inside after a restart goes to the right place).

## Restart and persistence

A restart forgives a live run today and the same applies here. On server start every lodestone that was `IN_PROGRESS` or
`CLEARED` is treated as reset: the slot is torn down, ground items are swept to lost and found, the phase becomes `READY`.
A player who logs in inside the dungeon dimension with no live record is sent to their last lodestone.

## Phases

| Phase | Scope | Needs | Hot files touched | Owed before the next |
|---|---|---|---|---|
| 0 | `WildMode` flag, config knobs, `WildState` store, pure tests | nothing | `PocketDungeonsConfig`, `config/pocketdungeons.default.json` | state round trips; mode off changes nothing |
| 1 | Lodestone and exit blocks, the two menus, ops `place`, `list`, `tp` | 0 | `RitualListener`, `DialogScreens`, `DialogRouter`, `DungeonCommands` | right click shows the menu on a hand placed lodestone |
| 2 | The run: build, enter, leave, shared instance, inventory bypass, PvP, spawn protection, travel blocks, death, relog | 1 | `Instances`, `InventorySwap`, `RunLifecycle` | two players share a dungeon, inventories untouched, a death drops items |
| 3 | The clear: shared gate, chests, clear exit block and emblem, cooldown, warning, reset | 2 | `RunLifecycle` | a full clear places chests and exit, starts the cooldown, reset rebuilds |
| 4 | Placement, claim data, lifespan, collapse, lost and found, relocation | 0 (can run beside 2 and 3) | new files only, plus `WildState` | a lodestone collapses, its items are in chests, a new one appears elsewhere |
| 5 | Docs, live checks, code map, record status | 1 to 4 | `docs/` | |

Phases 1 to 3 are sequential because they share `Instances`, `RunLifecycle` and `InventorySwap`. Phase 4 owns only new files
and can be built beside them.

### Phase 0: switch, knobs, store

- `WildMode.active()` reads the `mode` knob (`campaign` default, `wild`). Everything wild checks it; with `campaign` the mod
  behaves exactly as today.
- In wild mode the campaign entry points refuse politely: the room lodestone menu in `RitualListener`, `/dungeon`, `/dungeon
  key` and the keystone commands. `/dungeon admin ...` stays for ops.
- Knobs (see "Knobs"). Use `tools/new_knob.py`.
- `WildState` plus JSON codec. Pure `WildStateTest`: round trip, phase transitions, cooldown arithmetic, expiry.

### Phase 1: blocks and menus

- `WildLodestones.place(level, pos, id)` stamps a small ruin marker with a jigsaw in the middle. The jigsaw's block entity name
  is `pocketdungeons:wild_entrance` (arrival and clear exit use `wild_exit_arrival` and `wild_exit_clear`) so ops can see what
  it is. The lookup is by position in `WildState`, not by parsing the tag.
- Click handling goes first in `RitualListener.onUseBlock` and returns `SUCCESS` so vanilla never opens the jigsaw screen.
- Screens (built with `DialogKit`; Esc runs the exit button; no button-less list):
  - Outside: dungeon name, the promise, the state, then `Enter` and `Not now`.
  - Inside (arrival and clear exit): `Leave` and `Stay`.
  - Draft words (short; final pass with the `player-text` skill):
    - `Copper Works` / `Iron. Deep.` / `Ready.` or `Cleared. Resets in 2h.` or `In progress. 3 inside.`
    - The collapse line: `This place is collapsing in 3 days.` Shown only inside the last day.
- Ops commands under `/dungeon admin wild`: `place` (at the caller), `list` (id, coordinates, phase, time left), `tp <id>`,
  `ready <id>`, `reset <id>`, `collapse <id>`.

### Phase 2: the run

- `WildRun.enter(player, lodestone)`:
  1. If no live record, pick the dungeon, build it (`buildWildLayout`, the `enterUntimed` path with the node context and no
     owner room), make a record with `untimed = true`, a new `wild` flag and the nil owner, `startFloor`, phase `ACTIVE`.
  2. `Instances.admit` the player. Arrival point is the layout entrance, with the arrival exit block beside it.
  3. Spawn protection starts.
  4. Failure to stamp tears the slot down (existing behaviour) and tells the player the place is not ready.
- `WildRun.leave(player)`: remove from the record, teleport to the lodestone, never touch the inventory. The record stays up
  with nobody in it until reset or collapse.
- `InventorySwap.reconcile`: in wild mode run only the leave branch for an already stashed player; skip `enterVoid`.
- Pad: a wild record's pad does nothing.
- PvP: confirm nothing in `DungeonWorldRules` or the party code blocks player vs player damage. Spawn protection is an
  `ALLOW_DAMAGE` check on a per player expiry tick; it ends early if the protected player attacks.
- Travel blocks: ender pearls and chorus fruit refused in the dungeon dimension (`UseItemCallback`). Teleport commands from
  other mods (`tpasserver`, `rehome`, `unstick`): add a configurable `wildBlockedCommands` list checked by a command
  pre-execution hook. Verify which Fabric event fits before building; fall back to a mixin on command dispatch. Ops bypass.
- Death: vanilla. The audit in "What has to change" 5 decides what to remove. No lives, no omen failure, no rescue teleport.
- Relog: a player who joins inside with a live record is put back where they logged out; with no record they go to their last
  lodestone.

### Phase 3: the clear

- Extract `padRefusal(server, record, level)` from `padGatesRefuse` (same checks, no player). `padGatesRefuse` becomes a thin
  wrapper that messages the player. `RunLifecycleGateTest` style test pins that the wrapper's answers did not change.
- `WildClear.tick` runs once a second per `IN_PROGRESS` record. The first tick where `padRefusal` is `null` is the clear:
  1. `placeFloorRewards(..., finishedNow=false, ...)` (now package-private) places the barrel and the copper chest.
  2. Place the clear exit block beside the chests and an item display above it with the `Exit` text (a plain display for v1; the
     Glyph system can replace it later).
  3. Seal the far wall; do not stamp a staging room.
  4. `phase = CLEARED`, `clearedAt = now`, `resetAt = now + cooldown(dungeon)`. **This is the cooldown start.**
  5. Tell everyone inside, once.
- Reset at `resetAt`: warn at 120 seconds (chat and action bar), eject everyone to the lodestone, sweep ground items to lost
  and found (same routine as collapse), tear down the slot, `phase = READY`. The next `Enter` builds a fresh dungeon with a
  new seed.
- Cooldown tiers come from the wild dungeon definition: a longer promise means a longer cooldown.

### Phase 4: placement, lifespan, collapse

- **Wild dungeon data:** `data/pocketdungeons/wild_dungeon/*.json`, one file per offering: `id`, `name`, `dungeon` (existing
  def id), `node` (one node of it), `promise` (the two or three words on the menu), `cooldownMinutes`, `weight`,
  `minDistance`. Validate at load like the other manifests. Use the `dungeon-content` skill to pick nodes that make a good
  single floor.
- **Grid:** the world is cut into cells of `wildGridSize` blocks. Each cell has one deterministic jittered candidate position
  from `hash(worldSeed, cellX, cellZ, generation)`. The placer keeps `wildTargetActive` lodestones, filling empty cells in
  random order, a few per minute so it never stalls the tick.
- **Candidate checks:** load the chunk through a short ticket, take the surface height, then reject: water or lava underfoot,
  within `wildMinSpawnDistance` of world spawn, inside a claimed area, or with player built blocks nearby (a small tag list of
  builder blocks within a radius). A rejected cell retries with the next generation number.
- **Claims:** an interface `ClaimSource` with one method, `isClaimed(x, z)`. v1 reads the public Charter data once a minute
  from a configured URL or file (`wildClaimsSource`). The format is for the Charter agent to publish; see the open items.
  With no source configured the check is skipped.
- **Level:** `level = f(distance from world spawn)`, clamped, in `WildState`.
- **Lifespan:** `expiresAt = createdAt + wildLifespanDays`, with a little jitter so they do not all die together.
- **Collapse:** warn inside at 24 hours and again at 10 minutes. At `expiresAt`: eject everyone, sweep ground items in the
  slot bounds into chests beside the old lodestone (double chests, as many as needed, with a hard cap that logs when hit),
  tear down the slot, replace the marker with rubble, remove the lodestone from `WildState`, and let the placer fill the gap.
  The lost and found chests are plain world blocks after that; their positions stay in the ops `list`.
- **Sweep routine** (shared with reset): force load the slot chunks, collect `ItemEntity` in the layout bounds, merge by stack,
  fill chests, discard the entities.

## Knobs

`mode`, `wildGridSize`, `wildTargetActive`, `wildMaxActive`, `wildMinSpawnDistance`, `wildLifespanDays`,
`wildCollapseWarnHours`, `wildResetWarnSeconds`, `wildSpawnProtectionSeconds`, `wildClaimsSource`, `wildBlockedCommands`,
`wildLostAndFoundMaxChests`, plus per dungeon `cooldownMinutes`, `weight`, `minDistance` in `wild_dungeon/*.json`.

## Tests

| Test | Kind | Covers |
|---|---|---|
| `WildStateTest` | pure | JSON round trip, phases, cooldown and expiry arithmetic |
| `WildPlacementTest` | pure | deterministic candidates, spacing, rejection and generation retry |
| `WildTextTest` | pure | menu strings: short, no em dash, no double hyphen |
| `PadRefusalTest` | pure or gametest | the extracted check gives the same answers as before |
| `WildEnterGameTest` | gametest | inventory identical before and after entering and leaving; second player joins the same record |
| `WildClearGameTest` | gametest | gate passes, chests and exit block appear, cooldown starts, no staging room |
| `WildResetGameTest` | gametest | reset ejects, sweeps items to chests, rebuilds on next enter |
| `WildCollapseGameTest` | gametest | overflow chests, marker replaced, lodestone removed, placer refills |
| `WildPvpGameTest` | gametest | spawn protection holds, then ends; a player can hit another |
| `WildModeOffTest` | source scan | with `mode: campaign` no wild hook changes behaviour |

Register pure tests in `build.gradle.kts` and gametests in `src/gametest/resources/fabric.mod.json` (`add-tests`).

## Live checks to add to `docs/playtests/LIVE_CHECKS.md`

Enter with a full inventory and leave with it intact. Two players, one dungeon. Die inside, come back for the pile. Clear it
and watch the exit and the cooldown. Wait for a reset with a player inside. Let a dungeon collapse with items on the floor.
Camp the arrival block. Relog inside, and restart the server with a player inside.

## Risks

- **Hot files:** `Instances` (3300 lines), `RunLifecycle` (2600) and `InventorySwap` (1000) take small, guarded edits only.
  Everything else is new files.
- **Hidden keystone assumptions:** several systems read `record.isKeystoneRun()`, `stagingCellOrigin`, the omen bar, wager and
  affixes. `advanceFloor` already returns early when `stagingCellOrigin` is null. Phase 2 starts with a grep audit of each and
  a list of what a wild record must stub.
- **Chunk loading for placement:** loading arbitrary chunks can hitch the tick. The placer is rate limited and uses short
  tickets.
- **Stamp failure:** the existing teardown on failure covers it; the lodestone stays `READY` and the player is told to try
  again.
- **Sibling mods:** blocking `tpa`, home and unstick commands from here is a patch; the cleaner fix is for those mods to
  check a flag. Out of scope.

## Open items before phase 3 and 4

1. **Resolved (owner, 2026-10-30).** The dungeon stays enterable and open for the whole cooldown; at reset there is a 120
   second warning and then everyone is moved out. Leftover chests are first come first served. The record's rule 3 now says so.
2. **Charter data.** The owner says the kingdom data is already published. Phase 4 still needs its URL and format
   (rectangles, chunk lists or radii) before `ClaimSource` can be written.
3. **Confirmed (owner, 2026-10-30):** reset also sweeps ground items to lost and found; each reset uses a fresh seed; spawn
   protection ends early if the player attacks.
4. **Starting numbers:** grid 1500 blocks, 12 active lodestones, 7 day lifespan, 4 to 24 hour cooldowns by promise, 5 second
   spawn protection, 120 second reset warning. All are knobs.
