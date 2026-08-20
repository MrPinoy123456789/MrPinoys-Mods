# Pocket Dungeons — continue from here

Paste this into a fresh session to resume work.

---

## What this is

`pocketdungeons` is a server-side Fabric 1.21.5 mod in the MrPinoys Mods suite
(`a:\MrPinoys Mods\pocketdungeons`). It's mid-way through a feature update
adding procedural layouts, a real room library, loot/difficulty scaling, an
in-world entry ritual, and a dungeon log. The full implementation plan is
**`UPDATE_PLAN.md`** in the mod's root — read it in full before doing anything.
It is the living source of truth: each shipped milestone has a "✅ *shipped*"
marker with measured verification results and any deviations from the original
design written directly into that section.

Also read **`PLAN.md`** (the base mod's build plan, M0–M9, mostly shipped
already) and **`FEATURE_PROPOSAL.md`** (the original lean proposal
`UPDATE_PLAN.md` expanded from) for background, but `UPDATE_PLAN.md` is
authoritative where they'd disagree — it supersedes the milestone numbering
in both.

## Current status

Milestones are **U1–U5**, numbered as this update's own dependency-ordered
sequence (not PLAN.md's M-numbers — an earlier draft borrowed those and it was
actively misleading about execution order; see `UPDATE_PLAN.md`'s opening
section if you want the full reasoning).

| | Status | |
|---|---|---|
| **U1** — room library | ✅ shipped, verified | 14 templates, 0 rejected, 53/53 coverage, 200/200 plan success |
| **U2** — plan stamping + `Instances` glue | ✅ shipped, verified, **hardened** | procedural `/dungeon`, rotation-correct, tick-spread teardown |
| **U3** — tiered loot & scaled mobs | ✅ shipped, verified | `DifficultyProfile`, mob spawning, chest retargeting, loot tables, boss stones, `/dungeon party` |
| **U4** — lodestone ritual | ⬜ **not started — do this next** | fully independent, no dependency on U3 |
| **U5** — dungeon log & payout | ⬜ not started | depends only on U2 (already shipped), not U3 |

**First shippable build = U1 + U2 + U3 — all shipped.** U4 and U5 are the
remaining work; either can go next (U4 has no dependency on anything in this
update, U5 depends only on the already-shipped U2).

## Important: nothing is committed to git yet

Every change across U1, U2, and U3 is still in the working tree (`git status
--short` will show a large number of modified/added files under
`pocketdungeons/`, all uncommitted). Do not assume any of this survived a
reset. If you want a checkpoint, ask the user before committing — commits
happen only on explicit request per this project's working agreement.

## What just happened before this handoff

U3 (tiered loot and scaled mobs) was implemented and verified end to end:

1. `DifficultyProfile.java` — new pure-logic file (no Minecraft imports),
   tier/mob-count/roster curves, unit-tested in `DifficultyProfileTest`
   (wired into `tasks.test`).
2. `RoomContent.java` extended: `encounter` cells now spawn
   `DifficultyProfile.mobCount(depth)` mobs rolled from the tier roster,
   `loot` cells retarget their chest to `chests/tier_N` with an explicit
   seed, and the `spawnerDensEnabled` kill switch swaps authored spawners for
   mossy cobblestone.
3. `chests/tier_1.json` (rewritten), `tier_2.json`, `tier_3.json`,
   `bonus.json` — the full item pools from the plan, including boss-stone
   entries for the `kamutotems` cross-mod hook.
4. `LayoutStamper`/`Instances` thread a `partySize` through to stamp time;
   `/dungeon party <player>` (new) pre-registers a companion **before**
   entry so the difficulty curve reflects the real party size, per the
   plan's Stage 5.
5. `/dungeon admin cellreport <slot>` (new, dev-only) — per-cell chest/mob
   introspection, since there's no client to check role dispatch by hand.

**A real bug was found and fixed by live verification, not code review:**
`RoomContent`'s mob-overflow jitter could land on a wall and silently skip a
mob, systematically undercounting encounter cells (observed 2–3 mobs instead
of the intended 4). Fixed with a fallback to the guaranteed-open spawn jigsaw
position when jitter misses.

**A second, more visible bug was then reported by the user from an actual
client session:** `encounter`/`corridor` rooms showed loose items on the
floor where the chest should have been removed entirely. Root cause (found
via `javap` bytecode inspection, not guessing): `Block.UPDATE_SUPPRESS_DROPS`
only suppresses a removed block's *own* item drop, not a container's
*contents* -- that's a separate flag, `UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS`,
which neither `RoomContent` nor `RoomBuilder` (used by teardown) ever
included. Worse, a container's contents are dropped by iterating `getItem()`,
which lazily unpacks a still-pending loot table on first access -- so
breaking a never-opened placeholder chest generated its loot right there and
scattered it as the chest vanished. Fixed in both places; re-verified live
with a zero-item-entities census across a freshly built instance. Full
writeup, plus the loot-table/boss-stone verification results and one
deliberately-scoped-down deviation (`chestRolls()` is unit-tested pure logic
but not yet wired into a dynamic per-party chest bonus), are in
`UPDATE_PLAN.md` under the U3 section.

**Lesson worth carrying forward:** a headless console smoke test cannot catch
this class of bug -- nothing ever *opens* a chest via console commands, so
the lazy-unpack-on-access path this bug depends on never fires there. Some
things genuinely need a real client. Keep an eye out for other
container/inventory-adjacent code for the same reason before assuming a
headless pass alone clears it.

**Lesson carried forward from U2, reconfirmed here:** a live check on
"what happens when demand exceeds supply" (spawn points vs. mob count) caught
something the pure-logic unit test structurally couldn't, because
`DifficultyProfile` has no notion of a room template's authored spawn-point
count. Keep budgeting live verification for anything where two independently-
correct pieces interact, not just for state machines.

## Operational notes for whoever picks this up

- **Dev server testing harness.** There's no Minecraft client available in
  this environment. Verification happens by driving `./gradlew.bat runServer
  --offline` headlessly from a Python driver script that pipes timed console
  commands into its stdin and reads stdout for markers (`Done (` for boot,
  etc.) rather than fixed sleeps. Two gotchas hit this session, worth not
  re-discovering:
  - The background shell's `python` is native Windows Python, not Git Bash's
    — POSIX-style paths like `/a/tmp/...` do **not** resolve from inside the
    script. Use Windows paths (`r"A:\tmp\..."`) for anything the Python
    process itself opens, and pass an absolute Windows path to `gradlew.bat`
    (`cmd /c "A:\...\gradlew.bat" runServer --offline`) — `cmd /c gradlew.bat`
    alone did not find it even with `cwd` set correctly.
  - Any block/entity console command targeting a scratch position (e.g. a
    throwaway chest for `/loot insert` + `/data get block` checks) needs
    `/execute in pocketdungeons:void run forceload add <x1> <z1> <x2> <z2>`
    first, or every command silently no-ops with "That position is not
    loaded" — easy to miss since it doesn't error.
  - `/loot insert <pos> loot <table>` (not `/loot give`) is the move for
    inspecting a loot table from the console with no player attached — it
    fills a real block container you can then `/data get block` directly.
  - `run/eula.txt` needs `eula=true` (already present, but `rm -rf run/world`
    between clean-slate tests can occasionally take `run/` state with it —
    check `run/eula.txt` exists before assuming a server boot failure is a
    real bug).
- **The Minecraft jar for `javap` introspection** is at
  `/c/Users/Kriss/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
  Use it to verify any Minecraft API shape before writing code against it —
  this project has a strong "verify against the real jar, don't guess"
  discipline. U3 used it to confirm `SetCustomDataFunction`, `SetNameFunction`,
  `SetLoreFunction`, `SetItemCountFunction`, `EnchantWithLevelsFunction`,
  `EntityType.spawn`, and `Mob.setPersistenceRequired` all match the shapes
  the plan assumed — all correct on the first try this time, but don't skip
  the check on that account.
- **`clearBlocksPerTick` has a hard floor of 1024** in
  `PocketDungeonsConfig.apply` — a config value below that silently falls back
  to the default 8192. The `run/config/pocketdungeons.json` left over from a
  prior session currently has an invalid `clearBlocksPerTick: 20` in it,
  which logs a (harmless, expected) error at boot every time; leave it or fix
  it, it doesn't affect anything since the default kicks in.
- **Manifest loads on `SERVER_STARTED` now** — don't assume you need to run
  `/dungeon admin manifest reload` by hand before testing on a fresh server.

## What to do next

U4 (lodestone ritual) and U5 (dungeon log & payout) are both unstarted and
neither depends on the other. Read `UPDATE_PLAN.md`'s sections for both
(U4 starts around line 1103, U5 after it — search for the headings, don't
trust line numbers to stay accurate) and either is a reasonable next pick;
U4 is fully independent of everything in this update, U5 depends only on the
already-shipped U2. Whichever is picked, keep the established discipline:
verify Minecraft API shapes against the 26.2 jar via `javap` before trusting
them, and do a live headless-server pass (not just a diff review) on
anything where two independently-correct pieces of this milestone interact —
that is exactly the kind of bug U2's teardown/slot-reuse pass and U3's mob-
spawn undercount were both caught by.
