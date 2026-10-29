# Protected keeps, outposts as war prizes, raider waves

Status: approved by the owner on 2026-10-28, not built. The owner's team does not want
griefing, so keeps can no longer be destroyed or taken. This reverses the siege design
that sections 10.8 and 16 of `DESIGN.md` describe. Read the ground rules in section 0 of
`2026-10-27-charter-book-implementation.md` first; they all apply (branch
`feature/charter-build`, commits authored as MrPinoy123456789 with the
`Co-Authored-By: Claude Sonnet 5.5` line, never push, never deploy, no dashes as
punctuation, register every new test class, write problems under Builder's notes).

## Why

Players who build a home should never lose it to another player's blocks. War still has
to matter, so the thing at stake moves from the keep to the outpost and its deposit.
Raiders become the place where combat is the whole game: you fight them, you drain
them, they fall, the ground is clean again.

## Supersedes (delete the old paths, do not leave them dead)

- `DESIGN.md` 10.8 (raze or occupy, `occupationCapDays`, a castle changing hands only by
  treaty cede) and every row of section 23 that mentions them. A keep never falls to a
  player. `Falls`, `ChooseFall`, `PendingFall`, `Occupation` and their dialogs and
  Chronicle lines are removed for player keeps. `Knobs.plunderShare` stays only if the
  raider path still uses it (it does not; see N below), so remove it with the rest.
- Forms brief R3 ("sieges do not change") and the "occupy" wording anywhere in the book.
- Forms brief R4 step 2 ("a seceding sub-faction takes its keeps"): a sub-faction can
  never hold a keep, so a breakaway always founds a new one (the existing placement path,
  paying `keepPriceDiamonds` from its own treasury). A sub-faction owning a keep is now
  impossible; make `PlaceNexus` refuse it.
- NPC brief N2 and N3 (raze at once, plunder from a treasury, the long starvation path)
  and N5's respawn timer (replaced by waves below).
- Camps as an attack on a keep: a camp no longer starves a keep. The word "camp" becomes
  "outpost" everywhere a player reads it. Keep from the camp code only what outposts use
  (raising time, the store as food supply, the banner, cut off). Delete the keep
  perimeter, starvation and worn-down loops and the war banner cost (256 cobblestone).
- Notoriety for taking claims (`notorietyPerClaimTaken`) and the new-faction siege
  protection (`newFactionProtectionDays`): nothing is taken any more; remove.

If a removal breaks a stored save, add a migration that drops the dead state silently
(old saves must load).

## The rules

### K1. Keeps cannot be damaged by non-members

- Inside a keep's claim, blocks cannot be broken, burned, exploded or replaced by anyone
  who is not a member of that nation, and no player can open its containers. The Nexus
  is unbreakable by everyone but its Lord, who can only dismantle it by `abdicate` or
  dissolving the nation. Wilderness outside every claim stays fully vanilla.
- Reuse the existing protection hooks (`Protection`); a keep's claim is what they already
  guard. Add the missing cases (explosions, fire spread, pistons across the border,
  liquids across the border) and a gametest for each.

### K2. Upkeep: the granary

- The granary is the keep's upkeep. It drains over `keepUpkeepDays` (default 14) from
  full. Warnings go to every member on login and in chat, and appear on the faction
  page, at `keepWarnDays` (default 7, 3, 1).
- At zero the claim lapses: the land is released to the wilderness (the protection ends),
  the blocks stay standing, the nation keeps its members, treasury and sub-factions, and
  may put a new Nexus down (it still pays the founding price). A Chronicle line tells
  it plainly.
- No other rule changes the drain. Feeding it is the existing deposit-food path.

### K3. One of each

- Each player has one bed (vanilla spawn point); `/home` teleports to it. Charter does
  not own beds; `/home` reads the vanilla respawn point. No other mod in the workspace
  defines `/home` (`rehome` is about villagers), so the name is free; if a command
  clashes on the live server, register under `/charter home` too.
- Each nation has one keep: `/keep` teleports a member to it. Not a sub-faction.
- Each organization (the nation and each sub-faction) has one outpost: `/outpost`
  teleports a member to their organization's outpost; a member of a sub-faction uses the
  sub-faction's. A member with no outpost is told how to place one.
- Teleports: a warmup (`teleportWarmupSeconds`, default 5) cancelled by taking damage or
  moving; a cooldown (`teleportCooldownSeconds`, default 30); same dimension only.
- `/outpost` costs 1 food from that outpost's supply (`outpostTeleportFood`, default 1)
  and refuses with a plain line when it is empty. `/keep` and `/home` are free.
- A sub-faction needs at least `outpostMinMembers` (default 2) members to place an
  outpost. This stops one-person puppet factions.
- The outpost deposit (O1) is the cost of expansion: the Nth outpost a nation holds in
  total (nation plus all sub-factions) needs `outpostDeposit` diamonds
  (default 8) plus `outpostDepositStep` more per outpost already held (default 4).

### O1. Outposts: the war prize

- An outpost is the old camp, renamed. It has a banner, a food store (its supply), and a
  **deposit** in diamonds paid by its owner when it is placed. The deposit is held by the
  outpost, not the treasury, and is visible to anyone who inspects the banner.
- An outpost is **protected** (cannot be damaged or captured) unless a war goal against
  its organization names it. Declaring a war goal reuses the existing war declaration
  path; the goal is "capture an outpost".
- **Capture** works like a capture point: while the goal is active, if attackers stand in
  the outpost's chunk for `outpostCaptureMinutes` (default 10) with no defender of the
  owning organization in the chunk (defenders are members within the chunk, or the
  chunk's defenders by the existing presence rules), the outpost is captured. A defender
  entering resets the timer. Owners may `/outpost` in as the defence, which costs food.
- On capture: the banner is removed; the claimed chunks held by it are released; the
  deposit goes to the attacking organization's treasury; the attackers hold the outpost
  chunk for `outpostWinnerWindowMinutes` (default 30) and may place their own outpost
  there (their deposit still applies); then the chunk reverts to the wilderness. The war
  goal is completed and the truce (`truceDays`) starts as today.
- Outposts raised by NPCs are the raiders' (below), not part of this.

### N. Raiders (replaces the NPC brief's reward and clock)

- Strongholds stay destructible; they are the one keep that can fall. They have a
  **supply** (the old granary) and a **pool** (their member count by tier: 3, 5, 8,
  `npcPoolByTier`).
- **Waves.** Every `npcWaveMinutes` (default 5) a site spawns up to its pool in raiders;
  each raider spawned costs `npcSupplyPerRaider` supply (default 1). With no supply
  left, no more spawn. A raider killed does not refund supply.
- **Falling.** When the supply is zero and no raider of the site is left alive, the site
  falls. Killing raiders is the way to drain it; there is no starvation clock.
- **Cleanup.** At spawn the site snapshots the blocks it replaces (a compact list of
  positions and block states, persisted with the site). When it falls, restore them. If
  the snapshot is missing or the chunk is gone, clear the footprint to the surface and
  remove the claim. No ruins may remain. The Nexus, its chests and its banner are removed.
- **Failsafe.** A site nobody fought for `npcExpireDays` (default 10) is cleaned up the
  same way and replaced after `npcWaveMinutes`.
- Reward: what the raiders carry. Each raider drops its pillager table plus a small share
  of the site's stocked treasury (diamonds and cobblestone, as before), paid out as the
  site falls: `npcFallLoot` stacks spilled in the core chunk. Remove treasury plunder.
- Keep the caps (4 strongholds, 4 raider outposts), distance tiers, defender tags,
  Chronicle exclusion and admin commands. `admin npc list` also shows supply and pool.
- Raider outposts keep working as small camps (1 to 3 raiders, a stack or two of food);
  they use the same waves and fall when supply is zero and no raider is alive.

## Edge cases decided

- Can owners `/outpost` into their own outpost during a capture? Yes. That is the defence,
  and it costs food.
- Can a nation hold zero keeps? Yes, after a lapse. It can still hold its outpost.
- Can a lapsed keep's land be claimed by someone else? Yes, like any wilderness.
- Dormancy (`dormancyDays`) stays as is. Dead accounts are a separate problem.
- Keeps never hold a deposit. Only outposts do.

## Build order

1. N: raider supply, pool, waves, snapshot and restore, failsafe, short fall. Stands
   alone and fixes the ruins on the live server. Tests: a stronghold spawns, is drained,
   falls, and its ground is restored byte for byte; the failsafe; old saves load.
2. K1 and K2: protection gaps, upkeep drain, warnings, lapse. Remove raze and occupy,
   falls, plunder and their pages, with migrations.
3. K3: `/home`, `/keep`, `/outpost`, the limits and the deposit price.
4. O1: outposts with deposits and capture, then the book pages (faction, war, Join page
   text that no longer promises sieges). Update `DESIGN.md` 10.8, section 16 and section
   23, and the Join, Freedoms and Who decides pages.
5. Builder's notes, then stop and report the commit and counts.

## Out of scope (named, for the next brief)

Other war goals (capped tribute, treaty demands, vassalage), where treasury income comes
from, ranking nations by wealth, and a protected vault share of the treasury. The owner's
idea is that nations rank by wealth and wealth makes a nation a target; design it next.
Also out: seasons' gameplay effects, NPC raids against players, dead accounts.
