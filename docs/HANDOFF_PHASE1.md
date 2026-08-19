# Handoff prompt — Phase 1 implementation

> Copy the block below into a fresh session with the implementing model. It is
> written to be pasted verbatim. Replace `<TASK>` with the task you want done.
>
> **Run one task per session.** These tasks are deliberately independent so a
> cheaper model never has to hold the whole suite in its head at once.

---

```
You are implementing one task from a written plan in an existing Minecraft
Fabric mod suite. The plan is authoritative. Do not redesign anything.

REPO: a:\MrPinoys Mods
PLATFORM: Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, JDK 25.
Windows. PowerShell is the primary shell; a bash tool is also available.

READ THESE FIRST, IN THIS ORDER, BEFORE WRITING ANY CODE:
  1. PHASE1_PLAN.md  — §0 (pre-flight corrections), §1 (ground rules), then
                       your task card in §2. §0 corrects two things other
                       documents in this repo get wrong; trust §0 over them.
  2. DESIGN.md       — §2 (the coupling rule), §4 (the contract for a mod),
                       §9 (principles).
  3. The README.md / SPEC.md of the specific mod you are touching.

YOUR TASK: <TASK>          (e.g. "T4 — First-join signpost for /shop")

Do only that task. Do not start, prepare, or "while I'm here" any other task.

HARD RULES — violating any of these fails the task:
  1. Every mod is "environment": "server". Vanilla clients install nothing.
     No new registry entries, no resource packs. Custom items are vanilla items
     carrying minecraft:custom_data.
  2. No mod may import, reference, or declare a dependency on another mod.
     Not in Java, not in fabric.mod.json. If your task seems to need one, stop
     and report it instead of adding one.
  3. No Mixins. Nothing in Phase 1 needs one.
  4. Config: missing file -> write defaults and log. File that FAILS TO PARSE ->
     use defaults in memory and NEVER overwrite the file. Canonical impl:
     chatdonkey/core/src/main/java/chatdonkey/core/ReadOrCreate.java
  5. Never destroy a player item or reward. If it will not fit, drop it at the
     player's feet and log it.
  6. Anything testable without Minecraft belongs in that mod's `core` module and
     gets a test. `core` build scripts are deliberately empty so a Minecraft
     import fails compilation. Do not add dependencies to a core module.
  7. Match the surrounding code. These files have a distinctive comment style:
     comments explain WHY a decision was made and what the failure mode is, not
     what the line does. Write in that voice or write nothing.
  8. Sounds go through the mod's existing Chime.java at volume 0.15-0.4.
  9. Rejections are return values, not exceptions. A player doing something
     ordinary-but-invalid gets a message, not a stack trace.

BUILD AND TEST:
  cd "a:/MrPinoys Mods/<mod>" ; ./gradlew build
  The jar auto-copies into ../dist/.

  TRAP: the `dist` Gradle task must depend on `jar`, NEVER `remapJar`. 26.2
  ships unobfuscated so Loom registers no remapJar task. If a build fails
  mentioning remapJar, you have introduced this; do not "fix" it by adding the
  task back.

  Core tests run without Gradle, e.g.:
    javac --release 25 -d build core/src/main/java/<pkg>/*.java core/src/test/java/<pkg>/*.java
    java -cp build <pkg>.<Mod>Test
  Each prints "N passed, 0 failed". Current baselines: chatdonkey 353,
  ballot 148, quizengine 68, bounties 31. Your change must not lower these.

VERIFYING API SIGNATURES — do this instead of guessing:
  Minecraft 26.2 renamed a lot and most tutorials online are wrong for it.
  The compile-classpath jar is:
    C:\Users\Kriss\.gradle\caches\fabric-loom\minecraftMaven\net\minecraft\minecraft-merged-deobf\26.2\minecraft-merged-deobf-26.2.jar
  Check any signature you are unsure of:
    javap -cp "<that jar>" net.minecraft.world.item.ItemStack
  Known 26.2 facts: it is Identifier, not ResourceLocation. Permissions are
  Commands.hasPermission(...) with level constants on Commands, not
  hasPermission(int). UseItemCallback returns InteractionResult, not
  TypedActionResult. The Fabric interaction events module is v0, not v1.
  ItemStack.is(Item) DOES work despite what chatdonkey/PROGRESS.md says.

WHEN YOU ARE DONE, REPORT:
  - Every file you changed, and why.
  - The build result and the test counts, pasted, not summarised.
  - Each acceptance criterion from your task card, marked met or not met.
    If one is not met, say so plainly — do not report a task complete when it
    is partly complete.
  - Anything you could not verify without a running game client, listed
    explicitly as unverified.
  - Any place the plan was wrong, ambiguous, or contradicted the code. Say so
    rather than silently picking an interpretation.

IF YOU GET STUCK: stop and report. Do not invent a design decision, do not
refactor surrounding code to make your change fit, and do not touch a mod your
task does not name.
```

---

## Notes for the person driving this

**Task order:** T1 → T2 → (T3, T4, T5, T6 in any order) → T7. T1 first because
every later session reads those documents as fact. T2 before anything that
signposts quizengine harder.

**Which tasks suit a cheaper model:**

| Task | Suitability | Why |
|---|---|---|
| **T1** docs | Excellent | Mechanical, fully specified, zero API risk. |
| **T2** quiz content | Excellent | Bulk generation. Verify the `correct` index is 0-based *before* it writes 300 questions — that is the one way this task goes wrong at scale. |
| **T3** bounty announce | Good | One file, one pattern to copy. |
| **T6** streaks | Good | Contained to one small single-module mod. |
| **T4** welcome message | Moderate | Touches the join path and the `NameCache` first-join detection. Review the "was this UUID already known" logic — a bug here shows the welcome every join or never. |
| **T5** herald | Moderate–hard | Requires understanding why the lines must go in a *new* pool (`withDefaults` merges missing pools, not missing lines). The plan explains it; check the implementer actually did that and didn't just append to `lecture.during`. |
| **T7** live verify | Human only | Needs a game client. |

**Review these two things regardless of who implements:**
1. **T4's first-join detection** — `names.see()` is called immediately before
   `loginSnapshot.onJoin()` in `CobbleEconomyMod`, so the "have we seen this
   UUID" question must be asked *before* that line runs. Easy to get backwards.
2. **T6's config migration** — an existing `settings.json` with only
   `streakDiamondCap` must still load. The never-overwrite rule means real
   servers will have those files.
