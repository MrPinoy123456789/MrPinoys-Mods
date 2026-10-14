# Decision 2026-10-13: Act 3 and 4 floor types

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

## The numbers

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

## The rules

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

## Migration

None needed. `FloorState.objective` is per-floor state only; nothing new
persists, so a save in progress reads exactly as before.

## Supersedes

- `docs/concepts-2026-10-11.md`, "Act 3 onwards: paused": partially reopened
  for these three dungeons only (`sunken_treasury`, `lighthouse_below`,
  `gilded_seam`). The Drowned Vault concept and the remaining Act 3 to 5 items
  stay paused.
- `docs/concepts-2026-10-13-floor-types.md`, the generic objective framework
  note of section 3: built as `FloorObjectives` plus `ObjectiveState`, with
  the generic recovery path as the fallback rule. The brainstorm doc stays as
  provenance.

## Knobs

In `config/pocketdungeons.json`: `heistCoreChannelSeconds` (5),
`heistCrewBase` (2), `heistStrongboxBase` (2), `claimQuota` (3),
`claimExtraVeins` (3), `claimWaveTremor` (4), `claimHunterTremor` (6),
`hauntHuntGraceSeconds` (20), `hauntStunSeconds` (3), `beaconChargeSeconds`
(3), `beaconBanishSeconds` (15).

Fixed in code: `HauntObjective.BEACONS` (2), `HeistObjective.CHANNEL_RANGE`
(4), `HeistObjective.OMINOUS_ECHO_TICKS` (20, the ominous crew echo),
`ClaimObjective.ASSAYER_HEALTH_MULT` (2.0), hoglin pack `1 + members / 2`,
ominous Tremor start 1, ominous keeper speed 1.15x, stagger immunity 5 s.

## Tests

`FloorObjectiveRulesTest` covers the arithmetic pure (fallback, quotas, wave
and hunter tremors, bonus rolls). `HeistFloorGameTest`, `ClaimFloorGameTest`
and `HauntFloorGameTest` cover the floors end to end; `HauntRulesTest` covers
`decideHunt`.

## Open until played

Whether 5 seconds of channel on the core reads as tension or dead time (and
whether a hit breaking it is fair), whether the crew and The Assayer are the
right weight at their acts (they scale as omen waves, untested live), whether
leaving a claim floor while Tremor stays low is too safe (stake the 3 and walk
before the seam answers), whether the Keeper's advance speed and stagger
window read as horror or nuisance, and whether the extra claim rolls and
strongbox payouts inflate the reward economy.
