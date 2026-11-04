# Wild dungeons against the Trial Chambers: proposals (2026-11-04)

Proposals. **A, B1, B2 and a boss at the end were picked and built the same day**: see `docs/DECISIONS.md`,
"2026-11-04: Wild bosses, ominous lodestones and kingdom stakes". The rest are still proposals. What
was built on 2026-11-04 (the fixes, the longer floors, the lifted fight cap, the gold) is in `docs/DECISIONS.md`,
"2026-11-04: Wild dungeons: longer, more fights, paid in gold".

The owner's words: *"The Pocket Dungeons: Wild dungeons are not difficult / engaging enough and need to compete with
minecraft's vanilla Trial Chambers"* and *"come up with other things that would improve it for a minecraft Kingdoms
server, think outside the box as well."*

## What the Trial Chambers do well, and what a wild dungeon can do that they cannot

| Trial Chambers | Lesson for a wild dungeon |
|---|---|
| Bad Omen turns the same place into a harder, richer place (ominous trials, ominous vaults, the heavy core) | Let players choose to raise the stakes on a lodestone they already know |
| Each vault pays each player once, so a group gets more than a soloist | Pay per player where it matters (Sovereigns, Valor), not per chest |
| Unique loot (mace, heavy core, wind burst, trims, flow and bolt patterns) gives a reason to go back | Pay what only a dungeon gives, tied to the kingdoms: race weapons, kingdom trims, war goods |
| Breezes and wind charges make the fights about movement | Use wind charges against the rubble doors and breezes in the spawner pools |

A Trial Chamber is the same for everyone and nobody owns it. A wild lodestone is contested ground on a war server:
that is the thing to lean on.

## Options

### A. Ominous lodestones (recommended first)

**What the player does.** Drinks an Ominous Bottle beside a Ready lodestone, or right clicks it holding one. The menu
says "Ominous. Ready." in purple. The next run is ominous: two levels higher, ominous trial spawners, ominous vaults
(the `_ominous_wild` tables already exist), Sovereigns half again, and the war tier one loot tier early. The cooldown
after the clear is longer.

**Why it is interesting.** It is the Trial Chambers' best idea in a place players can hold. A kingdom holding a
lodestone decides when to spend its bottles; raiders who arrive mid run fight in an ominous dungeon too.

**Scaling.** Trial spawners already scale by the players inside. Ominous bottles come from raid captains, so the
kingdoms that hunt pillager outposts feed it.

**Cost.** One Ominous Bottle a run. No crafted key needed to finish (no soft lock).

**Build.** Medium: a lodestone flag in `WildState`, the menu line, the bottle use in `WildDialogs`, and the
`ominous` flag already in `RunRecipePlan`.

### B. Kingdom stakes on every clear (recommended second, small)

**What the player does.** Nothing new; the clear pays more widely.

1. **Valor and Renown.** Each player inside at the clear is paid through the existing `payoutCommand` (already
   `chartercurrency earn %player% dungeon %level%` for the campaign), so a clear earns personal Valor and kingdom
   Renown. Pocket Dungeons still depends on nothing: the seam `WildRewards.pay` runs the command.
2. **The banner.** The lodestone menu says who cleared it last ("Last cleared by Aurelia, 2h ago") and the kingdom's
   tag colour shows on the entrance marker until the next reset.
3. **The tithe (optional).** One Sovereign per player inside goes to the clearing kingdom's treasury instead of the
   chest, the way the Mint taxes. Needs `/chartercurrency treasury add` or a small command in Charter Currency.

**Why it is interesting.** Holding a lodestone becomes kingdom score, not only loot, without the tribute mechanics
the owner set aside on 2026-11-02.

**Build.** Small for 1 and 2; small to medium for 3.

### C. A boss at the end, and loot only a dungeon gives

**What the player does.** The last room of a long dungeon is a boss arena (the `finale-script` skill already covers
a phased finale). The boss drops one thing nobody can farm, tied to the kingdoms:

- a **Runed Core** that the rank 3 race weapon upgrades need (the War Pick, Oathblade, Tide Trident, Briarthorn
  Staff and Everdelle Wand), so every kingdom's best weapon is earned in the wild;
- **kingdom armour trims** and banner patterns in the five kingdoms' colours (data pack trim patterns, no mod code);
- now and then a **heavy core** or a **mace**, so the dungeon competes item for item with a Trial Chamber.

**Why it is interesting.** It gives a reason to go back that gold alone does not.

**Build.** Medium to large: one finale script per dungeon type, Foundry items and recipe changes, trim data.

### D. The Sovereign Hoard (an event lodestone)

**What the player does.** Every few days one lodestone appears far out with a server wide line: "A Sovereign Hoard
has opened somewhere east." It holds a long ominous dungeon with a vault of 64 Sovereigns that only the first clear
gets, and it collapses after a day.

**Why it is interesting.** It makes kingdoms send war parties to the same place at the same time. That is the PvP
event a war server wants, and the hint (a direction, not coordinates) rewards scouting and outposts.

**Build.** Medium: a special offering, a one off placement in `WildTopUp`, the broadcast, a short lifespan.

### E. Descend or leave (push your luck, a real second floor)

**What the player does.** At the clear the Exit menu offers Leave or Descend. Descend opens a second floor two
levels deeper with richer tables. If everyone inside dies on the lower floor, the dungeon collapses at once and the
upper floor's unopened chests are lost.

**Why it is interesting.** It makes the dungeon longer in the way that matters (a choice with real loss, the
Darkest Dungeon style the owner likes) instead of only more rooms.

**Build.** Large: wild records are one floor today; the campaign's floor loop would need a wild version.

### F. Smaller ideas worth a line each

- **Wind charge rubble.** Once PD-226 is live, put wind charges in the supply chests so the rubble doors become a
  movement puzzle rather than a TNT tax.
- **Breezes in the spawner pools** of the higher levels, for Trial Chamber style fights.
- **Fastest clear per offering**, shown on the lodestone menu, with a weekly Sovereign prize.
- **Charts for sale.** A shop entry, paid in Sovereigns, that names the direction of the nearest Ready lodestone of
  a kind. A gold sink that feeds the gold faucet.

## Recommendation

Build **B1 and B2** next (small, and they make every clear count for a kingdom), then **A** (the Trial Chambers' own
answer, in a place players hold). Keep **C** and **D** for after a few real clears show whether the gold numbers
are right, and **E** for when wild mode needs a second floor anyway.

## Questions for the owner

1. B3: should a clear tithe Sovereigns to the kingdom treasury, or only pay players?
2. A: should an ominous run need the bottle, or should a lodestone sometimes turn ominous by itself?
3. C: is gating rank 3 race weapons behind a dungeon drop acceptable, given weapons are already in players' hands?
