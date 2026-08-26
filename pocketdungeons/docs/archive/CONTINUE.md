> Archived 2026-08-25 (M9 C5): superseded by `PROGRESS.md`'s Session log and
> the per-milestone `handoffs/M<n>-handoff.md` files.

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
section if you want the full reasoning). **`UPDATE_PLAN.md` also carries a
U6** — a trial-chambers rework that supersedes most of U3's difficulty
machinery — appended after U1–U3 shipped, **a U7** (the keystone/Mythic+ meta
on top of U6), and **a U8** (this session's work, superseding most of U7's
exit handling and all of U6's ominous entry paths). Each is a second update
sharing the document, not the next step of the one before it.

| | Status | |
|---|---|---|
| **U1** — room library | ✅ shipped, verified | 14 templates, 0 rejected, 53/53 coverage, 200/200 plan success |
| **U2** — plan stamping + `Instances` glue | ✅ shipped, verified, **hardened** | procedural `/dungeon`, rotation-correct, tick-spread teardown |
| **U3** — tiered loot & scaled mobs | superseded, then **deleted** | its mob-spawning path (`DifficultyProfile.mobCount`/`effectiveTier`/`mobRoster`, `RoomContent.spawnMobs`) was the `trialsEnabled: false` rollback and nothing else; deleted outright in T17 once the trial loop was confirmed live. Chest retargeting, loot tables and boss stones live on inside the trial-spawner path; `/dungeon party` survives untouched (it was never difficulty scaling) |
| **U4** — lodestone ritual | ✅ shipped | `RitualListener`, `ConfiguredItem`. Config paths verified live; **every right-click path is client-only and unverified** |
| **U5** — dungeon log & payout | superseded by U8 | the streak and the item payout are both deleted; `DungeonLog` and `/dungeon log` survive in reduced form |
| **U6** — trial-chambers rework | ✅ shipped, mostly superseded | trial spawners and vaults survive; the ominous-by-depth ramp and the choice vaults are gone (U8) |
| **U7** — keystones (Mythic+ meta) | superseded by U8 | `Keystone`/`Keystones`/`KeystoneMath` survive in reduced form (two depletion outcomes, not six); the three choice vaults, the four-outcome depletion table and the disconnect-parking path are all gone |
| **U8** — the timer is the run | ✅ code-complete, **T1–T17 all landed** | see below |

**U8, this session's work (`IMPLEMENTATION_PLAN_U8.md`), T1–T17 all landed.**
The timer now ticks independent of membership and is the only thing that can
deplete a keystone; re-entering an owned instance is free; a completed run
teleports to a reward room whose three chests score how fast you were; the
upgrade choice is three real doors in a private selector room instead of three
vaults that vanilla would never unlock; the remote is a recovery compass, not
a trial key; ominous is now purely the keystone's own affix. **T17** (deleting
the U3 rollback path) landed in a follow-up session on explicit instruction:
`spawnerDensEnabled`, `trialsEnabled`, `baseMobsPerEncounter`, `maxMobsPerRoom`,
`DifficultyProfile.effectiveTier`/`mobCount`/`mobRoster` and
`RoomContent.spawnMobs` are all gone, and the `partySize` thread into
difficulty math is gone with them (`/dungeon party` itself is untouched --
it never was difficulty scaling). Confirmed headless: a keystoneless
`admin build` now stamps a real trial spawner exactly like a keystone run,
since there is no longer a second path for it to fall back to. See
`UPDATE_PLAN.md`'s U8 "Shipped:" section for the full measured verification
and every deviation.

## Git state

U1–U3 **are** committed (`ae45f9a`, `d4b2ed0`, `2b20017` — the last two being
the two hardening passes). The prior handoff note claiming nothing was
committed was stale by the time it was read; check `git log` rather than
trusting a handoff on this.

U4, U5, U6 and U7 are **not** committed — `git status --short` shows five new files
(`RitualListener`, `ConfiguredItem`, `DungeonLog`, `Payout`, `PayoutMath`), one
new test (`PayoutMathTest`), and edits to `Instances`, `DungeonCommands`,
`PocketDungeonsMod`, `build.gradle.kts`, plus the three docs. Commits happen
only on explicit request per this project's working agreement — ask first.

## What just happened before this handoff — U6 and U7

Both were built in one session, in the order the task set: **every new config
field first** (18 of them, U6's and U7's together, so three features would not
churn `apply`/`defaultsJson` in sequence), **then the ominous spike**, then U6,
then U7.

**The spike passed, and it was worth running first.** `TrialSpawnerBlock`'s
server ticker reads `BlockStateProperties.OMINOUS` off the blockstate every tick
and hands it straight to `TrialSpawner.tickServer`, which assigns it to
`isOminous` unconditionally — no player, no effect, no structure involved. Held
in-world for ten seconds of ticking with nobody in any dimension. So the mod
stamps ominous itself and never depends on vanilla noticing a Trial Omen, which
is what the whole ominous half rests on.

**Three deviations worth knowing before touching this code again** (the full
list is in each milestone's `Shipped:` section):

1. **Spawners and vaults are placed at stamp time, not authored into the
   `.nbt`s.** The room library was **not** regenerated — its 14 files, 53/53
   coverage and 200/200 plan success are byte-identical to what U1 shipped. The
   rotation-safety §3 demanded is still satisfied because both anchors come from
   rotation-transformed sources (a `pocketdungeons:spawn` jigsaw, an authored
   chest's placed position), never from an offset off `cellOrigin`.
2. **One key kind per run.** The plan wanted `loot <= encounter` to hold *per
   key kind*. It cannot, cheaply: `ominousAt` is a function of depth, so a plain
   run contains both plain and ominous cells and therefore two key types with no
   guarantee either balances. The key became a run-level property instead (with
   one new data file per tier, `ominous_plain_key.json`), which makes the global
   count sufficient exactly as written. **Found in-world on the first headless
   pass, not in review.**
3. **Reaching the exit pad no longer ejects you.** It completes the run — pay,
   log, hand over the token — and the *second* pad contact leaves. Forced by U7
   putting three vaults in the exit room; before this the token would have
   arrived in the overworld with the vaults left behind. This is the one shipped,
   client-tested behaviour U7 changes, and `/dungeon admin build` runs (no
   keystone) keep the old single-contact behaviour.

**Two ordering bugs were found by tracing rather than testing**, both in the same
place and both now fixed: a player who completed the run and then typed
`/dungeon exit` to walk out of the exit room was charged `depletionOnExit`, and
one killed by a leftover mob in that room was charged `depletionOnDeath` — after
finishing. `Instances.returnKeystone` now upgrades any non-`SERVER` outcome to
the completion outcome once that member is in `record.completed`.

**Also worth not re-discovering:** a misspelt trial-spawner config id does **not**
throw. The codec drops the field and the block silently keeps
`FullConfig.DEFAULT`. That is why `admin cellreport` reads the ids back out of
the block entity rather than trusting the write. And `VaultServerData.
getRewardedPlayers()` is package-private, so the vault-claim check goes through
`saveWithoutMetadata` + `UUIDUtil.CODEC_LINKED_SET` on `server_data.
rewarded_players` instead.

## What happened in the session before that

U4 and U5 were both built in one session.

**U4 (lodestone ritual)** — new `RitualListener.java`, plus a new
`ConfiguredItem.java` shared with U5 for parse-cache-log-once item-id
resolution. `Instances.enter` now returns `boolean` so the key is consumed only
after entry actually succeeds. The strict "no `custom_data` at all" key rule
shipped (not the narrow kamutotems-only version), a sneak guard was added that
the plan does not mention, and the ritual sound plays *before* entry so the
player being teleported away can actually hear it.

**U5 (dungeon log & payout)** — new `DungeonLog.java` (`SavedData`, list-keyed,
modelled on `wondrous/WondrousState`), `Payout.java`, and `PayoutMath.java`
(pure logic, no Minecraft imports, with `PayoutMathTest` wired into
`tasks.test`). `Instances.exit` now takes an `ExitReason`; only `EXIT_PAD` pays,
guarded once per member per instance by `InstanceRecord.paid`. `/dungeon log`
and `/dungeon log <player>` added, plus two dev-only commands
(`/dungeon admin log record|show`) that exist specifically so the streak rule
can be tested without waiting several real days.

**A real bug was found by reading bytecode, before it ever shipped:**
`Inventory.add(ItemStack)` returns "did I move **any** of this" and mutates the
passed stack down to the remainder — so the suite's usual
`if (!player.getInventory().add(stack))` idiom **silently destroys** whatever
did not fit. A payout into a nearly-full inventory is exactly when that
happens. Shipped code tests `stack.isEmpty()` afterwards instead. **The same
idiom appears in at least eight other files across this suite** (`bounties/
Rewards`, `chatdonkey/Rewards`, `cobbleeconomy/ItemBank`,
`kamutotems/AssignedQuestHost`, `ballot/BallotCommands`, and more) with the
same hole — worth raising with the user as its own piece of work.

**A documentation discrepancy, resolved in favour of the formula:** U5 Stage 3's
prose says a tier-3 run on a 10-day streak pays 28; its own formula
(`streakBonusPercent * (streak - 1)`) makes that 26, with 28 arriving on day
eleven when the 100% cap is reached. The formula shipped and both numbers are
asserted in `PayoutMathTest` so it cannot drift again silently.

**One thing that looked like a bug and was not:** `dungeon_log.dat` does not
appear under `run/world/data/`. In 26.2 a `ServerLevel`'s `SavedDataStorage`
writes to `run/world/dimensions/<ns>/<path>/data/`, so the overworld's store is
at `run/world/dimensions/minecraft/overworld/data/pocketdungeons/dungeon_log.dat`.
`world/data/` holds the vanilla server-level stores only. Ten minutes went into
chasing a phantom save failure here; don't repeat it.

## Carried-forward lessons (all still current)

- **Verify Minecraft API shapes against the real jar with `javap`, and check
  what a method *does*, not just that it exists.** U3's enchanted-book bug
  needed `EnchantmentHelper.selectEnchantment`'s bytecode; U5's `Inventory.add`
  bug needed the same treatment; U4's "is it safe to shrink a stack after a
  cross-dimension teleport" question was answered by confirming
  `ServerPlayer.teleport` is `aload_0 … areturn` throughout and never recreates
  the player.
- **Headless testing has a hard blind spot.** Nothing in a console session ever
  right-clicks a block, opens a chest, or clicks a GUI button. That is what hid
  U3's container-drop bug and a kamutotems GUI bug, and it is why *all* of U4's
  and most of U5's checks are in `CLIENT_TEST_CHECKLIST.md` rather than marked
  verified.
- **Check the generated data, not just that generation ran.** U3's two
  user-reported bugs both survived a pass that confirmed the loot tables parsed
  and the right item type appeared, but never inspected an enchantment component
  or compared a roll's size to the plan's own narrative.
- **Trace cross-cutting interactions by hand, not by diff.** U2's teardown/
  slot-reuse pass, U3's mob-spawn undercount, and U5's payout ordering (eject
  first, pay second, so a dropped reward lands in the overworld and not inside a
  dungeon teardown is already clearing) were each found or confirmed that way.

## Operational notes for whoever picks this up

- **Dev server testing harness.** There's no Minecraft client available in this
  environment. Verification happens by driving `./gradlew.bat runServer
  --offline` headlessly from a Python driver that pipes timed console commands
  into its stdin and reads stdout for markers (`Done (` for boot). A working
  driver is written fresh each session; the shape that works is: launch
  `["cmd","/c", r"A:\MrPinoys Mods\pocketdungeons\gradlew.bat", "runServer",
  "--offline"]` with `cwd` set to the mod root, a reader thread pumping stdout
  into a queue, wait for `Done (`, then write commands to stdin with a short
  drain after each, then `stop`. Two gotchas, worth not re-discovering:
  - The background shell's `python` is native Windows Python, not Git Bash's —
    POSIX-style paths like `/a/tmp/...` do **not** resolve from inside the
    script. Use Windows paths for anything the Python process itself opens, and
    an absolute Windows path for `gradlew.bat` (`cmd /c gradlew.bat` alone does
    not find it even with `cwd` set correctly).
  - Any block/entity console command targeting a scratch position needs
    `/execute in pocketdungeons:void run forceload add <x1> <z1> <x2> <z2>`
    first, or every command silently no-ops with "That position is not loaded".
  - `/loot insert <pos> loot <table>` (not `/loot give`) is the move for
    inspecting a loot table from the console with no player attached.
  - `run/eula.txt` needs `eula=true` (already present, but `rm -rf run/world`
    between clean-slate tests can occasionally take `run/` state with it).
- **The Minecraft jar for `javap` introspection** is at
  `/c/Users/Kriss/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged-deobf/26.2/minecraft-merged-deobf-26.2.jar`.
  `javap -c -p -cp "$JAR" <class>` for bytecode; plain `javap` for signatures.
  Note that `ItemStack.is(Item)` no longer appears in `javap ItemStack` — it is
  inherited from `TypedInstance.is(T)`.
- **`clearBlocksPerTick` has a hard floor of 1024** in
  `PocketDungeonsConfig.apply`. The stale `run/config/pocketdungeons.json` that
  used to carry an invalid `20` has been fixed to `8192`; the boot-time error it
  logged is gone.
- **Manifest loads on `SERVER_STARTED`** — no need to run
  `/dungeon admin manifest reload` by hand on a fresh server.
- **Dev-only commands available for headless work:**
  `/dungeon admin build [seed]`, `admin cellreport <slot>`, `admin stamptest`,
  `admin coverage`, `admin plansurvey <n>`, `admin log record <name>
  <pathLength> <date>`, `admin log show <name>`.

## What to do next

**A real client walkthrough is now the only thing left, and it is overdue.**
`CLIENT_TEST_CHECKLIST.md` sections 15–33 (U4–U6, still current) and §36,
§44–49 (U8, new this session) are written and unrun. Every user-reported bug
on this mod so far came from exactly the paths a console session cannot reach,
and U8 replaced most of the player-facing loop.

Suggested order, highest risk first:

1. **§46 — take each of the three doors.** This is U8's core mechanic and the
   direct replacement for U7's broken vault claim: a door click must be
   intercepted before vanilla's own door open/close runs, and `/dungeon choose`
   must write the right level and affix. Nothing here has run against a real
   client yet.
2. **§45 — leave mid-run, come back, finish.** Free re-entry is the load-bearing
   change U8 Stage 1 makes: membership dropping to zero must not tear the
   instance down, and re-entering must land in the *same* instance, not a new
   one.
3. **§48 — time out while offline.** The one way left to lose a keystone level,
   and its failure mode (a silently un-depleted or double-depleted keystone) is
   invisible without checking `/dungeon key` afterward.
4. **§44 — three-chesting.** Confirm the chest count actually tracks the clock
   and that the reward room's loot tables match the run's tier and affix.
5. Then the carried-forward U6 risks: **§31** (Trial Omen must not leave the
   dungeon, by all three exits) and **§26's warning** (no creepers, ever — a
   hole into the void is M0's one hard safety contract).

**T17 has already landed** (`IMPLEMENTATION_PLAN_U8.md`'s Phase 6, on explicit
instruction): the U3 rollback path is gone --
`RoomContent.spawnMobs`/`DifficultyProfile.mobCount`/`effectiveTier`/
`mobRoster`, the `partySize` thread into difficulty math, `spawnerDensEnabled`
and `trialsEnabled` are all deleted, and the trial-spawner/vault loop is now
the only encounter/loot path there is. Nothing in the list above changes
because of it -- T17 never touched the code any of §44–49 or §31/§26 exercise
-- but it does mean there is no more `trialsEnabled: false` escape hatch if
the trial loop turns out to need one; that risk is now carried by the same
client walkthrough already listed above, not a separate one.

Both open decisions from the previous pass are now settled (see
`DISCOVERIES.md`): `ominous_plain_key.json` and its dead branch in
`TrialContent.ominousConfigId` are deleted, and a late-but-finished completion
now depletes the run's keystone by `lateCompletionDepletion` (default 2, config-
exposed) via a new `Keystones.Outcome.LATE` -- mitigated by the door offer
still being granted at the post-depletion level, so even a `+1` door nets only
`-1` overall. Worth a client check alongside §46 and §48: confirm the message
("your keystone is depleted") shows on a genuinely late completion, and that
the door offer that follows really is computed off the lower number.

Also outstanding from a previous session: a fix to `kamutotems`'s
`KamuForge.java` (deferring the Fusion panel's hub reopen to the next
`END_SERVER_TICK`) is sitting uncommitted in the sibling working tree and could
never be live-verified here. Worth asking the user whether it actually resolved
the symptom.
