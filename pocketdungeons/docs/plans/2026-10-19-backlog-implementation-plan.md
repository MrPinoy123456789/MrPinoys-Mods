# Backlog: implementation plan (2026-10-19)

Everything designed and not yet built, in the order to build it, with what may run in parallel, what each task
owns, and the gates between waves. Master is at `66633a6a` (the campaign and endgame build is merged and deployed to
714a0605). Read with `docs/DECISIONS.md`; the decision wins where this plan disagrees, and the builder records the
deviation.

House rules: every agent follows `.claude/skills/agent-brief/SKILL.md`, the orchestrator follows
`orchestrate-build` and `integrate-branches`. No em dashes or spaced double hyphens. Player text per `player-text`.
Commits authored `MrPinoy123456789`. Never push, never deploy, never touch a server or Lemon tool unless the owner says
so in that session.

## What is in the backlog

| Item | Source | State | In this plan |
|---|---|---|---|
| Two errors from playtest 2026-10-19-1 (window bands, entity at an invalid position) | `docs/playtests/2026-10-19-1.md` | logged, no PD number | wave 0, as PD-218 and PD-219 |
| Phased bosses for every finale floor | DECISIONS 2026-10-15 "Arena floors and phased bosses" | only the Copper Works pilot and the uncharted finale use `BossFight` | wave 1 |
| Vertical floors Phase 1 (story index, no visible change) | DECISIONS 2026-10-17, `docs/plans/2026-10-17-vertical-phase-1.md` | planned, not built (`PlanCell` has no story) | wave 1 |
| Vertical floors Phase 2 (stair rooms, Mineshaft opts in) | DECISIONS 2026-10-17 | approved, not planned in detail | wave 2 |
| Live playtest of the campaign and endgame (L104 to L114) and of waves 1 and 2 | LIVE_CHECKS | owed | gate after wave 2 |
| Vertical floors Phases 3 and 4 (drops, overlook grates, the map and "1 down") | DECISIONS 2026-10-17 | approved, but paused until after release by the release gate (decision 2026-10-18, review item 5) | wave 4, only when the owner lifts the gate |
| Review items 1, 3, 7, 9 (Act 0 one verb a floor, Mending out, three-line board, hopper rooms) | `docs/plans/2026-10-17-design-review.md`, spec Part B | not approved | wave 3, only with the owner's yes |
| Haunts in Acts 1 and 2 (The Counted in the Ossuary, The Listener in Deepslate) | DECISIONS 2026-10-11 "Haunts" | draft; `the_counted.json` exists but no dungeon carries it | not in this plan: needs an owner decision |
| Capstones on the phased spine (Spawner Dungeon brood, Ancient City) | DECISIONS 2026-10-15, open question | open; Ancient City is "playtest first" (L31) | not in this plan |
| Timed trial (Greater Rift style) | DECISIONS 2026-10-18 | held until uncharted doors are played | not in this plan |
| Acts 3 to 5 content, Drowned Vault concept, Wither's Keep | DECISIONS 2026-10-11 | paused; `releaseMaxAct` 2 hides them | not in this plan |

## Branches

Integration branch `feature/backlog-2026-10-19` off `master`, in its own worktree. Task branches
`feature/rm-<id>-<topic>` via `tools/new_worktree.sh` with `PD_BASE=feature/backlog-2026-10-19`. Vertical floors keep
the branch name their plan names only if built alone; inside this plan they are task branches like the rest.

## Waves

At most four agents at once. A task starts when its "needs" are merged into the integration branch.

| Wave | Task | Size | Needs | Owns |
|---|---|---|---|---|
| 0 | W0a PD-218 window bands | S | none | the five Act 0 room manifests named below, `rotation_lock` manifest |
| 0 | W0b PD-219 entity at an invalid position | S | none | whatever the trace finds (report it) |
| 0 | W0c Ledger | S | none | `BUGS.md`, the stale line in `DECISIONS.md` |
| 1 | F1 Phased finales, Act 1 | M | wave 0 | `MineshaftFinale`, `LushFinale`(+`Rules`), `InfestationFinale`, `KennelsFinale`(+`Rules`), `OssuaryFinale` |
| 1 | F2 Phased finales, Act 2 | M | wave 0 | `CowPitsFinale`, `DeepslateFinale`(+`Rules`), `FrostFinale`(+`Rules`) |
| 1 | V1 Vertical Phase 1 | M | wave 0 | `PlanCell`, `PlanGeometry`, `PlanRenderer`, the fingerprint test (per its plan) |
| 2 | V2 Vertical Phase 2 | L | V1 | `DoorMask`, `LayoutGraphGenerator`, `RoomSelector` (story step), new `StairSpecs`, stair room manifests, `dungeon/mineshaft.json` |
| gate | Live playtest | | waves 0 to 2 deployed | |
| 3 | B1 to B4 | see spec | owner's yes, wave 1 | as `2026-10-18-campaign-endgame-build-spec.md` Part B |
| 4 | V3 Drops, V4 Map | L, M | gate, owner lifts the release gate | `LayoutGraphGenerator`, `GraphSolvability`, drop rooms, `DungeonMapText`, `SidebarLines`, `PlayerContext` |

`FinaleScripts.registerAll()`, `PocketDungeonsConfig`, `fabric.mod.json` are hot: minimal local edits. F1 and F2 never
touch `BossFight` itself; if the spine needs a change, F1 makes it in its first commit and F2 merges it first.

---

## Wave 0: fixes and ledger

### W0a: PD-218, window bands dropped on room edges

The log (06:15 and 06:22 on 2026-10-19) says rooms disagree on window `none`, so the band is dropped, on six edges:
`black_seam_fork` and `hall_cross_rotunda`, `stone_rise_fork` and `exit_hall`, `black_seam_nook` and
`hall_cross_rotunda`, `rust_ravine_run` and `exit_hall`, `rotation_lock` and `black_seam_bend`, `rust_ravine_bend` and
`rotation_lock`. Five of the six involve the Act 0 rooms (built 2026-10-17).

1. Read the band rule (grep the log message in the source) and the two manifests on each edge.
2. Fix at the data: give each Act 0 room's window the material the rule expects, or set `none` on both sides where the
   room has no window. Do not change the band rule.
3. Test: a pure or manifest test that walks every pair of rooms that can share an edge and fails on a `none`
   disagreement, so the class of bug cannot come back.

### W0b: PD-219, a block-attached entity at an invalid position

`BlockPos{x=9, y=65, z=1000710}` at 06:22 on 2026-10-19. A z of a million suggests a slot origin or an offset applied
twice. Trace which entity (an item frame, painting or lead knot from a template) and which stamp path adds the offset;
Act 0 rooms are the first suspect (same session). Fix the offset, add a regression test that stamps the culprit room
and asserts every block-attached entity sits inside the cell's bounds. If the trace finds nothing, report what was ruled
out and leave the PD open.

### W0c: Ledger

Add PD-218 and PD-219 to `docs/reference/BUGS.md` (status filled in by W0a and W0b). In `DECISIONS.md`, the campaign and
endgame section's line "Nothing is built." now reads "Built 2026-10-18; see the status line at the end of this section."
Note in `docs/playtests/2026-10-19-1.md` that the zero chests on floors 2 to 4 were Act 0's no-loot rule (the rooms
are `stone_rise`, `black_seam`, `rust_ravine`), not a fault.

---

## Wave 1: phased finales and the story index

### F1 and F2: every ordinary finale on the phased spine

The rule (DECISIONS 2026-10-15): every finale floor fights a boss under `BossFight`, three phases bounded at 2/3 and
1/3 health, damage clamped at each bound, a `bossPhaseStaggerSeconds` stagger with a title, particles and a sound, then
the next phase's mechanic arms. Phase one is the baseline fight; each later phase adds the mechanic that dungeon's boss
workshop row already built (DECISIONS 2026-10-11). Copper Works (`CopperFinale`) is the reference: copy its shape.

| Dungeon | Boss (existing elite) | Phase 2 arms | Phase 3 arms |
|---|---|---|---|
| Mineshaft | the wave's elite | the lights fail (dark phase) | the rest of the wave comes as cave spiders |
| Lush Caves | the giant slime | the potion drip quickens | the first split doubles (the workshop rule) |
| Infestation | the giant silverfish | it burrows and bricks mature into silverfish | bricks mature faster |
| The Kennels | the Alpha | the handler's windup (kill him to cancel) | the pack scales up if the handler lived |
| Ossuary | the coffin wave's lead | coffins open as players pass | a skipped coffin opens behind a turned back |
| Cow Pits | Specimen 0 (only if woken) | the herd charges with it | the herd scales |
| Deepslate | the Deep Sentry | the Heard meter fills on noise | a full meter sends a whelp |
| Frostworks | the Frost Warden | freeze ticks away from the braziers | the warm spots shrink |

Each row maps mechanics that already exist in that finale onto phase bounds; no new mechanic is designed here. Where a
finale's current trigger is "half the wave dead" (`onHalfDead`), move it to the boss's 2/3 bound. Where the table does
not fit what the code does, keep what the code does, put it on the spine, and record the mapping in the report.

Rules for both tasks:
1. Health per member and ominous echo as `BossFight` already does.
2. The pad gate is unchanged: the arena opens when the boss dies.
3. A finale with no elite today (check each) gets the wave's strongest mob as the boss; never add a new mob type.
4. Tests: one gametest per dungeon: three phases play in order under burst damage, each arms its mechanic, the pad opens
   on the kill. Use separate gametest slot ranges per task.
5. A live check fragment per act (`tools/livecheck.py new`).

### V1: Vertical floors Phase 1

Build exactly `docs/plans/2026-10-17-vertical-phase-1.md` (story index on `PlanCell`, story-aware geometry, the layout
fingerprint over a seed sweep, zero behaviour change). Use the task branch from this plan instead of the branch that
plan names. Done when the fingerprint matches before and after every commit.

---

## Wave 2: vertical floors Phase 2

From DECISIONS 2026-10-17 rules 1 to 3 and 7, Phase 2 row. Write a short plan file first
(`docs/plans/2026-10-19-vertical-phase-2.md`, the same shape as Phase 1's) and commit it before code.

1. **Per-story door masks.** A cell's door mask stays 4 bits per story; rotation unchanged (`DoorMask`).
2. **Stair rooms.** Three or four generic two-story rooms (`StairSpecs`, room-building skill): entered on the top
   story, left by a door on the lower story, real stairs two wide, no ladders on the way on, so mobs follow. They sit
   on the spine and require nothing. Theme placeholders; processors re-skin them per theme.
3. **The generator's step down.** `LayoutGraphGenerator` may place a stair room on the spine and continue the walk one
   story down, at most to story -2 (`RoomGeometry.STORY_HEIGHT` pitch). A dungeon node opts in with a stories field
   (default 1, flat). Nothing else changes for a dungeon that sets nothing.
4. **Solvability.** The spine stays provable with the Pilgrim seed as today; add stair cells to the reach walk.
5. **Mineshaft opts in** (`dungeon/mineshaft.json`), two stories on its deeper nodes.
6. **Envelope.** Bounds, protection and return paths already reach down (M61); assert it with a test that stamps a
   two-story Mineshaft plan and checks the bedrock envelope and the pad.
7. Tests: generator seed sweep (every plan solvable, flat dungeons unchanged by fingerprint), a stamp gametest of a
   stair room with a mob path down it.

The owed check before Phase 3 (decision): one Mineshaft trip that goes down, played live.

---

## Gate: live playtest

Deploy the integration branch (deploy-test-server skill, owner's go) and play, in this order: L105 and L111 (the pack
and pot), the phased finales of one Act 1 and one Act 2 dungeon, a Mineshaft trip that goes down a story, L112 to L114
(Chart Room, dive, Relic), then the quest line on a second account (L104, L107, L109) and party lives with two (L110).
Run the `playtest` skill afterwards; fixes from it go in before wave 3 or 4.

---

## Wave 3: review items (only with the owner's yes)

B1 Act 0 teaches the dungeon, B2 Mending leaves the shop, B3 the three-line board, B4 the last hopper rooms: built
exactly as Part B of `docs/plans/2026-10-18-campaign-endgame-build-spec.md`. B1 also absorbs W0a's Act 0 room edits.
The integrator records the approval as a dated section in `DECISIONS.md`.

## Wave 4: vertical floors Phases 3 and 4 (only when the owner lifts the release gate)

V3: overlapping stories, drops as directed loop edges (never the spine, downward only, from a hole into the room
beneath onto powder snow), the solvability pass counting the lower end as reached only from above, overlook grates.
V4: the map board and `DungeonMapText` per story, `PlayerContext` (Lemon) per story, and the bar line "1 down",
"2 down". Each gets its own short plan file first, like Phase 2.

---

## Definition of done (per wave)

- Every task in the wave merged into `feature/backlog-2026-10-19`; `tools/ship.sh --quick --no-dist` prints
  `SUITE GREEN` with more tests than before the wave (use `PD_TMP=A:/tmp-ci`; `A:/tmp` holds too many files).
- `tools/check_dashes.sh` clean; live-check fragments folded; `CODEMAP.md` regenerated.
- A short report per wave in `docs/reports/backlog-2026-10-19.md`: built, deviations, test counts, live-only checks.
- Nothing pushed, nothing deployed without the owner's go, `master` untouched until the owner says merge.

---

## Prompt for the builder (paste below the line into a Sonnet session in `A:\MrPinoys Mods`)

---

**Scope: waves 0 to 2.** (Add "and wave 3" only if the owner approved review items 1, 3, 7 and 9; add "and wave 4"
only if the owner lifted the release gate.)

You are the orchestrator for a multi-agent build of Pocket Dungeons in `A:\MrPinoys Mods\pocketdungeons`. Read, in
order: `A:\MrPinoys Mods\CLAUDE.md` (no em dashes or spaced double hyphens anywhere);
`pocketdungeons/docs/plans/2026-10-19-backlog-implementation-plan.md` (this plan: waves, ownership, rules, done);
the `DECISIONS.md` sections it cites; `.claude/skills/orchestrate-build/SKILL.md`, `.claude/skills/agent-brief/SKILL.md`
and `.claude/skills/integrate-branches/SKILL.md`; `docs/CODEMAP.md` before any source. Build the waves in scope on
`feature/backlog-2026-10-19` off `master`, at most four sub-agents at once, each told its task, branch, owned files,
report path and "Follow `.claude/skills/agent-brief/SKILL.md`". Merge each finished branch at once and keep a status
table at the bottom of the plan. Player data is sacred (old shapes read, golden fixtures). Never soft-lock a floor.
Never push, deploy, or use a server or Lemon tool. Commits authored `MrPinoy123456789` and ending
`Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`. If the plan and a decision disagree, follow the decision
and record it. Stop at the gate after wave 2: report to the owner once, under 300 words (merged and green, not built,
deviations, live-only checks, machine problems), and ask to deploy.
