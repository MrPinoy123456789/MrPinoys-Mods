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
machinery — appended after U1–U3 shipped. It is a second update sharing the
document, not the sixth step of this one, and it is untouched.

| | Status | |
|---|---|---|
| **U1** — room library | ✅ shipped, verified | 14 templates, 0 rejected, 53/53 coverage, 200/200 plan success |
| **U2** — plan stamping + `Instances` glue | ✅ shipped, verified, **hardened** | procedural `/dungeon`, rotation-correct, tick-spread teardown |
| **U3** — tiered loot & scaled mobs | ✅ shipped, verified, **hardened twice** | `DifficultyProfile`, mob spawning, chest retargeting, loot tables, boss stones, `/dungeon party` |
| **U4** — lodestone ritual | ✅ shipped | `RitualListener`, `ConfiguredItem`, `Instances.enter` returns boolean. Config paths verified live; **every right-click path is client-only and unverified** |
| **U5** — dungeon log & payout | ✅ shipped | `DungeonLog` (`SavedData`), `Payout`, `PayoutMath` + test, `ExitReason`, `/dungeon log`. Streaks and persistence verified live; **every in-dungeon payout path is client-only and unverified** |
| **U6** — trial-chambers rework | ⬜ not started | separate update, depends on U1/U2, touches U4 and U5 at one point each |

**All five milestones of this update are code-complete.** What remains before
calling it shippable is a real client walkthrough — see below.

## Git state

U1–U3 **are** committed (`ae45f9a`, `d4b2ed0`, `2b20017` — the last two being
the two hardening passes). The prior handoff note claiming nothing was
committed was stale by the time it was read; check `git log` rather than
trusting a handoff on this.

U4 and U5 are **not** committed — `git status --short` shows five new files
(`RitualListener`, `ConfiguredItem`, `DungeonLog`, `Payout`, `PayoutMath`), one
new test (`PayoutMathTest`), and edits to `Instances`, `DungeonCommands`,
`PocketDungeonsMod`, `build.gradle.kts`, plus the three docs. Commits happen
only on explicit request per this project's working agreement — ask first.

## What just happened before this handoff

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

Two candidates, in rough priority order:

1. **A real client walkthrough of U4 and U5.** `CLIENT_TEST_CHECKLIST.md`
   sections 15–25 are written and unrun. Given that every user-reported bug on
   this mod so far came from exactly the paths a console session cannot reach,
   this is the highest-value next step and the thing standing between "code
   complete" and "shippable".
2. **U6, the trial-chambers rework** (`UPDATE_PLAN.md`, search for `## U6`). A
   second update sharing the document. It supersedes most of U3's difficulty
   machinery and touches U4 (an ominous-bottle ritual branch) and U5 (an ominous
   payout multiplier) at one point each.

Also outstanding from a previous session: a fix to `kamutotems`'s
`KamuForge.java` (deferring the Fusion panel's hub reopen to the next
`END_SERVER_TICK`) is sitting uncommitted in the sibling working tree and could
never be live-verified here. Worth asking the user whether it actually resolved
the symptom.
