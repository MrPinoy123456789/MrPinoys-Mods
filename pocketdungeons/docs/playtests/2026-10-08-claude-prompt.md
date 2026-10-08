You are working in `A:\MrPinoys Mods\pocketdungeons`, a Minecraft dungeon mod.
A live playtest (report: `docs/playtests/2026-10-07-2.md`, session notes
`docs/playtests/2026-10-07-2.notes.md`) filed bugs PD-167 through PD-177 in
`docs/reference/BUGS.md` against commit e97db42 (Haul and Blood Doors). Read
those three documents first.

## House rules (enforced)

- Never write an em dash or a double hyphen as punctuation in code comments,
  docs, player-facing strings, or commit messages. Colons, semicolons,
  periods or parens. Command flags and code operators are exempt.
- Match existing code conventions; read neighboring files first.
- Do not commit, deploy, or restart the server. Never touch server 365f9682.
- Verify with the project's test suite (gametests + unit tests via gradle)
  and add a regression test per fix, matching how previous PD fixes did it.

## Fixes, in priority order

1. PD-167 (HIGH): an owner's death ends the run for the whole party at any
   lives count. `Instances.rescue` calls `RunLifecycle.dropMember`, whose
   PD-26 leadership branch purges the record when the owner detaches with
   members present. Under Blood Doors a death should spend one life and
   rescue plus re-admit the owner exactly like a solo death (the `stillLive`
   re-admit path already exists). Non-owner deaths already work correctly.
   Do not break the intended leadership end for voluntary owner exit.
2. PD-170 (HIGH): `SpurToll.tollFor` returns `Items.TRIAL_KEY` and
   `Locks.holdsKey` is an exact `stack.is` check, so ominous trial keys
   never pay a toll. On an ominous floor every dropped key is ominous and
   the vault can hold the last required spawner: a soft-lock. Accept the
   floor's key kind (or both kinds). Also improve discoverability: the
   hint line only appears on door use and the player never got that far.
3. PD-168 (HIGH, design first): quitting and death-purges never settle the
   haul. `quitDoor` never calls `bankHaul`; the orphan rule banks it at
   100 percent on next join, and a purge carries the haul whole into the
   next trip. The fail-half rule is only reachable via the fifth-death
   path, so quitting strictly beats failing. Propose the ruling to the
   owner before coding: is a quit a fail for haul purposes, and should a
   purge settle like a fail? Player's own suggestion: quitting should just
   fail the run rather than depleting the compass.
4. PD-169 (MEDIUM): `/dungeon quit` then leaving the lobby puts the player
   in the overworld still wearing dungeon gear while the survival
   inventory stays stashed. The quit -> lobby -> exit path skips the
   inventory swap that a normal exit performs.
5. PD-175 (MEDIUM): a vault ejected `enchanted_book` with no
   `stored_enchantments`. Find the vault loot roll that can produce a
   book with no enchantment and fix or guard it.
6. PD-172 (MEDIUM): `the_herd` sinks its gold blocks at y=0, the immutable
   shell floor row; the room's quiet-mining premise cannot happen. Raise
   the gold into the editable interior or exempt it.
7. PD-173 (MEDIUM): floor names are invisible. `FloorStartTitle.titleFor`
   shows only `themeName` (the dungeon). Put the floor's node name in the
   title (the player asked twice: reported it, then had to ask what the
   floor was called). Optionally subtitle it on floor 1. Second half is
   content: "The Great Drip Cavern" generated all generic halls, so named
   floors oversell. Consider a room bias for final nodes or temper the
   names; flag whichever you do not do.
8. PD-174 (MEDIUM): `OmenBarText.clearedTitle` joins headline, loot and
   lives with " | " into one title line that ran off screen. Split or
   shorten; the detail can live on the bar or in chat.
9. PD-171 (MEDIUM): rubble sat on the route toward a room holding the
   last required spawner, compounding with a toll door on the same path.
   `RubbleOrdeal` promises doorway rubble only gates bonus paths; audit
   whether a rubble plug can land on a route the required-spawner path
   needs, especially when the destination is itself gated.
10. PD-176 (MEDIUM, harness): `lemon_reply` delivers the reply but does
    not cancel the 45 s fallback, so `lemon unanswered` journals on
    answered asks (five times in one session). Fix the pending-question
    lifecycle: a delivered reply (or an ambient `heard` that answers it)
    should clear the fallback.
11. PD-177 (LOW, harness): `pdmark-*` marker commands print "Unknown or
    incomplete command" in latest.log on the remote server. Find what
    emits them on the remote path and whether they belong to player
    resolution (possible PD-163 root cause).

## Design task: replace `hold_the_plate`

The owner verdict: the room works fine but is kind of lame. This is the
second strike: in playtest 2026-09-29-2 the player called the hold
"pointless" after the early-complete fix, and his counter-proposal then
(waves spawning off the plate itself, escalating with remaining time) is
what shipped. Even with its own waves the verdict is still lame.

Current implementation for context (`HoldThePlateOrdeal.java`): stand on a
stone pressure plate for 30 s while it raises waves through the room's
trial spawner ({30,1},{20,1},{12,2},{5,2} seconds-left/mob-count),
stepping off pauses the count, caps at 6 alive / 16 spawned, and
completion opens the exit's iron door.

Before coding, propose two or three replacement ordeal designs that keep
the same contract (an `Ordeal` subclass: arm at stamp, tick on a period,
resolve opens the exit; danger is the room's own mob table; progress is
legible to the player). For each: one paragraph on what the player does,
why it is more interesting than standing still, and how it scales with
party size and ominous. Consider the player's past leanings: mechanics
rescued not skipped, verticality and geometry that demands movement,
rooms that tell a story. Recommend one and wait for owner approval before
implementing. The template `dungeon_room/hold_the_plate.json` may be
replaced or repurposed; `FloorRooms`, `PressureSpecs`, `Situations` and
`LayoutStamper` reference the room and the ordeal.

## Player design asks to weigh while in the code (not bugs; do not implement
without owner sign-off)

- "pull the lever is the payout ritual": bank the haul on the GO HOME
  lever even after a finish, with quit/disconnect as the failsafe, so the
  cash-out is always a deliberate act. The finish currently auto-banks.
- The haul model is invisible to the player ("feels like it doesn't
  exist"): the +N scrap line and lore exist but nothing anchors it.
- Lives should scale with party size; vaults once per player; failure
  should land at Home with a "Wasted" screen, not a boot out; skew
  spawner ejects toward keys (now 50/50); witch poison ~10 s not 30+;
  copper chest loot thin vs the end barrel; redstone needs a merchant
  sink; more seam-style ore rooms; Rootworks palette nodes are invisible
  (it has no hiddenOre, so nothing is ever buried in walls).

Report back per PD number: what changed, test names covering it, and
anything unverifiable without a live server.
