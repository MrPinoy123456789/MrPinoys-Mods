# Claude Code prompt: design pass, then implement the 2026-10-06 playtest findings

> Paste everything below the line into a Claude Code session opened at
> `A:\MrPinoys Mods`. It is two phases: first a design pass over the playtest
> findings, then implementation of the approved design.

---

You are working on Pocket Dungeons, a server-side Fabric mod for Minecraft
26.2 (Java 25). The mod lives in `pocketdungeons\`; the git repo root is
`A:\MrPinoys Mods` on branch `feature/dungeon-structure`. A GitHub remote
exists (`MrPinoy123456789/MrPinoys-Mods`) but do not push unless asked.

This task comes in two phases. **Phase 1 is design only: no code changes.**
Present the design, get the owner's approval or revisions, then move to
Phase 2.

## House rules (read before anything else)

- Never write an em dash or a double hyphen as punctuation, in code comments,
  strings, docs or commit messages. Use a colon, a semicolon, a comma or a
  new sentence.
- Read `A:\MrPinoys Mods\CLAUDE.md` for the full conventions.
- Player-facing naming is settled law: "compass" not "keystone", "chart
  scrap" and "charts" (5 scrap = 1 chart, leftovers lost on go-home).
  Internal API names may keep `keystone`.
- The owner's taste is settled too: boards and screens should read like
  Steve Jobs wrote them. Few words, no jargon, no labels a position can
  carry, color does the grouping. When you must choose between a shorter
  text and a clearer text, propose both and say which you prefer and why.

## Read first, in this order

1. `pocketdungeons\docs\playtests\2026-10-06-1.md`: the session this work
   comes from. Its "Design decisions" section holds the owner's rulings.
2. `pocketdungeons\docs\reference\BUGS.md`: PD-157 to PD-160 and the
   reopened PD-149. PD-153 is superseded.
3. `pocketdungeons\docs\DUNGEON_STRUCTURE_DESIGN.md`: the model the code
   implements. It will be amended by the decisions below.
4. `pocketdungeons\docs\playtests\AGENDA.md` emerging themes and
   `LIVE_CHECKS.md`: context for what has already been tried and rejected.
5. Then the code: `DungeonScreen.java`, `IntervalBanking.java`,
   `TripDoors.java`, `StoreNPC.java`, `DiaryDelivery.java`,
   `ConnectorStamper.java`, `OmenBar.java`/`OmenBarText.java`,
   `RoomSelector.java`/`RoomEligibility.java`, `RunStorage.java`.

## Phase 1: design pass (no code changes)

Work through the items below. For each, read the relevant code and the
playtest evidence, then write a short design proposal: the mechanic or
screen as the player experiences it, the exact strings or layout, the edge
cases, and the alternatives you rejected and why. Show the owner rendered
examples of every player-facing surface (use ASCII mocks of the boards).

Design questions to resolve:

1. **The door board.** Owner-approved skeleton exists (below), but finish
   the details: how a resource floor's palette lists (`coal · copper · iron
   · gold` after stripping `deepslate` and deduping), how a too-easy floor
   reads, how `loot xN` counts rolls, what the final floor's line looks
   like, and how the 20-percent-smaller body text is achieved (two
   TextDisplays: title + body, as the history board already does).

   ```
   INFESTATION · floor 2 of 4
   The Web Gallery · Overclocked
   3 scrap · 2 echo shards
   loot x3 · 2 echo shards
   ```

2. **The GO HOME board.** Design where "carried scrap" lives and how the
   take-home/stays split reads:

   ```
   GO HOME
   7 scrap carried
   take home: 1 chart · 2 scrap stays
   ```

   Consider whether the door board should hint at carried scrap too (owner
   chose GO HOME for it, but if the design argues otherwise, say so).

3. **No identical doors.** Owner: "if they are the same then they should
   have different affixes." Design how `TripDoors.dealNext` repeats get
   distinct deals: reroll affix signature, or differentiate something else
   if affixes are exhausted. Deals must stay seeded and stable between
   preview and commit.

4. **The Store.** Two live-observed bugs (PD-159) plus the owner's wish to
   stop leaning on vanilla trade mechanics. Design the shop interaction as
   the player experiences it (what a click does, what a left-click does,
   what the stock line says, how the currency is shown) before touching
   `MerchantGui`/`StoreNPC`. If a custom click UI is the right call, design
   it; if the vanilla merchant screen can be made honest with less code,
   argue for that instead.

5. **Run storage as a container.** Owner: "store stuff in the run storage
   like it's an enderchest." Design it: where the player opens it, whether
   it persists across trips, what happens to the auto-return on logout,
   capacity, and what happens on a failed run. This may be implementable
   now or may be too big; say which and why.

6. **Resource floors pay scrap.** Owner decision is settled (they pay like
   normal floors, gated by underlevel). The design question: floor level is
   currently minted as owner compass + step, so solo players can never
   outlevel a dungeon. Decide whether authored fixed floor levels are worth
   designing now (it changes difficulty, affixes, loot bands) or deferred.

7. **Ore hidden in walls.** Owner: hide ore pockets inside interior walls
   ("a very cheap feature"). Design how it works in `mineshaft_seam` and
   whether it generalizes to other resource rooms.

8. **Diary books.** PD-157 is mechanical (paginate at build time), but
   decide the details: where pages split, whether the found-copy shuffle
   keeps split sub-pages together.

9. **Iron doors.** PD-160: reconcile the lever placement with the intended
   challenge. Design whether connectors always get a reachable opener or
   whether some stay locked-by-design (and if so, how a locked door reads so
   it does not look broken).

Present the full design as one reviewable document (in chat or a doc the
owner can read), item by item, with your recommendation on each and the
open questions marked. Wait for the owner's answers before Phase 2.

## Phase 2: implementation

After approval, implement in this order:

1. **PD-159** the Store: clean delivered stacks everywhere, left-click must
   never consume stock without delivering, custom currency model per the
   approved design.
2. **PD-157** diary pagination.
3. **PD-149** reopened: reproduce the live miss (nodes_total 0 floor) before
   fixing; the unit test passes, so find what differs live.
4. **PD-160** iron door connectors per the approved design.
5. **PD-158** omen bar vs interval omen.
6. **No identical doors** per the approved design.
7. **Door board + GO HOME board** per the approved layout.
8. **Resource floors pay scrap**; note the solo-underlevel caveat in the
   design doc as a follow-up if deferred.
9. **Small items**: diamond pickaxe durability bump within the cap scheme;
   hidden wall ore if the design says cheap; run storage if in scope.

## Verify

From `A:\MrPinoys Mods\pocketdungeons`: `.\gradlew.bat test`,
`.\gradlew.bat runGameTest`, then `.\gradlew.bat build dist`. All must pass;
the dist jar lands in `A:\MrPinoys Mods\dist\`. Update BUGS.md statuses as
each fix lands and amend `DUNGEON_STRUCTURE_DESIGN.md` where decisions
changed. Do not deploy; do not push.
