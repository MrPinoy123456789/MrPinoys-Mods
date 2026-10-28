# NPC strongholds: pillager keeps and outposts

Status: approved by the owner on 2026-10-28, not built. A separate task from
`2026-10-28-government-forms-brief.md`; do it after that and after the calendar work
are committed. The ground rules in section 0 of
`2026-10-27-charter-book-implementation.md` apply (branch `feature/charter-build`,
commits authored as MrPinoy123456789 with the `Co-Authored-By: Claude Sonnet 5.5`
line, never push, never deploy, no dashes as punctuation, register every new test,
write problems under Builder's notes).

## Why

Solo players and small factions have nobody to fight, and the siege rules have no
target on a quiet server. NPC strongholds give them one, using the same mechanics as
player keeps and camps, so nothing new has to be learned and the siege rules get a
standing live test.

## What exists to reuse

- `ScenarioRunner` (`fabric/.../scenario/`) already builds a rival faction with a keep
  and a stocked granary through `PlaceNexus` and a granary helper (`keep(...)`,
  `granary(...)`). Reuse those steps for placing a stronghold.
- `LandGraph.keepProblemForNew` and `keepGapChunks` give legal keep sites.
- `Knobs.plunderShare` (0.25) is the raze plunder; the raze outcome already spills it.
- The absent-ruler rule in `ActionEngine` tick (`vacateWhenAbsent`) must not touch NPCs.

## The design

### N1. An NPC faction is a normal organization with a flag

- Add `npc` to `Organization` (persisted in the org state block; old saves load it as
  false). Created as a Lordship with a fixed sentinel `PlayerId` as its Lord, status
  ACTIVE.
- NPC factions are never listed on the Join page, take no applications or invitations,
  sign no treaties or contracts, never declare war and never plant camps. They can be
  besieged, razed and plundered like any faction. The book must not offer an NPC
  faction anywhere a player faction is offered. Exempt them from the absent-ruler tick
  and from dormancy.
- Names and a banner come from a list in lang files (the vanilla pillager banner).
  Include a few jokes that still read as plain names, for example "The Pillage Village
  People", "Evoker Fan Club" and "Ironhide Raiders". One lang key per name.

### N2. Two kinds of site

- **Stronghold (a keep).** A keep with a Nexus, a small granary and a treasury, held by
  the NPC faction. It falls like any keep when its granary runs dry.
- **Outpost (a camp).** A raised camp (`CoreKind.CAMP`) with a stocked camp store, held
  by the NPC faction, cheaper to take than a keep. Read how player camps end (cut off,
  starved or worn down) and use exactly that. Check what a camp spills when it falls;
  if nothing, an outpost drops a small loot chest of its store.
- Overworld only, `dimensionAllowed`.

### N3. The reward is plunder

- A fallen stronghold can only be razed. Hide the occupy choice for an NPC keep (no
  tribute, no treaty). The Nexus breaks and the plunder share of its treasury spills in
  the core chunk, as for any keep.
- The treasury is the prize. Stock it at spawn by tier (below) with diamonds and
  cobblestone; it never refills except by a respawn.

### N4. Difficulty by distance from the world spawn

Three tiers (knobs, defaults): near, middle, far.
- Granary share at spawn: 0.30, 0.50, 0.70.
- Defenders (vanilla pillager, vindicator, ravager mix, tagged `charter.npc`): 3, 5, 8.
- Treasury in diamonds: 16, 32, 64, plus cobblestone for camps' war banners to be
  worth capturing: 128, 256, 512.
- Outposts have 1 to 3 defenders and a store worth a stack or two.
Defenders stay within the footprint, never count as siege attackers or as members,
and are removed when the site falls or is razed.

### N5. Placement and respawn

- A scheduler on the real clock. `npcMaxStrongholds` (default 4) and `npcMaxOutposts`
  (default 4) bound what stands at once. When a site falls, a replacement appears
  elsewhere after `npcRespawnDays` (default 3, real days).
- Choose a random generated, dry, flat enough surface site, at least `keepGapChunks`
  from every keep, with no claim in its footprint, and at least `npcPlayerClearChunks`
  (default 8) from any player claim or any online player. Try up to 20 candidates per
  attempt; if none works, log it and retry on the next tick. Never place inside a
  claim.
- Build with the existing scenario build helpers or a simple block pattern (a wall
  ring, a gate, the Nexus, granary chests); no new structure template is required.
- Persist the sites (a new state block) so a restart keeps them and the respawn timer.

### N6. Never raids players in this version

No NPC camp is ever planted against a player. Camps against player keeps come later
with the Ragnarok season work. Say so in a code comment so nobody wires it in early.

### N7. Chronicle and admin

- Chronicle lines (in `ChronicleRenderer`, prose, not routine): a stronghold or outpost
  is raised, falls, is razed, and is replaced. Write them in the same plain voice as
  the existing lines.
- Commands under `/charter admin npc`: `spawn <stronghold|outpost>` (tier by position),
  `list`, `clear <id>`, `respawn` (run the scheduler once). Admin only.

## Tests

- Core: an NPC org cannot join, invite, treat, declare war or plant a camp; the absent
  tick leaves it alone; plunder spills on raze; occupy is refused for an NPC keep.
- Placement logic as a pure function with fixed random seeds: respects `keepGapChunks`,
  the player clearance, the cap and the respawn delay.
- Fabric: persistence round trip with old saves; a gametest that spawns a stronghold,
  starves it and razes it, then checks the plunder spilled and the defenders are gone;
  a gametest for an outpost falling.

## Build order

1. N1 and N3 in core (flag, refusals, raze only), with tests.
2. N5 placement as a pure function, then the scheduler and persistence.
3. N2 and N4: building, tiers, defenders, outposts.
4. N7 commands and Chronicle lines, then `RegistryTest` lang lines.
5. Builder's notes, then stop and report the commit and counts.

## Out of scope (decided)

NPC raids against players, permanent captures, NPC treaties and tribute, and any
gameplay effect of the seasons.
