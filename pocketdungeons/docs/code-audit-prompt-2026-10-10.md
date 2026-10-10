# Prompt: code smell and refactor audit of Pocket Dungeons

Give this whole file to the auditing agent. It is self-contained.

## The project in two sentences

Pocket Dungeons is a server-side Fabric mod for Minecraft 26.2 (Mojang names, Java 25, Gradle with Loom) in
`A:\MrPinoys Mods\pocketdungeons`, about 256 main classes and 70,000 lines in `src/main/java/pocketdungeons`.
Players enter instanced roguelike dungeons from a Home room, carry a haul of scrap out, and raise a compass level;
the content is mostly data (`src/main/resources/data/pocketdungeons`) driven by Java systems.

## House rules for everything you write

- **No em dashes and no spaced double hyphens as punctuation**, anywhere: reports, comments, javadoc, commit
  messages (`A:\MrPinoys Mods\CLAUDE.md`). Use a colon, semicolon, comma, parentheses or a new sentence. Command
  flags (`--stacktrace`), `i--` and markdown rules are fine. Do not mass rewrite existing comments for this.
- Match the surrounding code: naming, comment density, idiom. No drive-by reformatting.
- Player-facing text follows the `player-text` skill (few words, no jargon). Do not reword strings in a refactor.
- Work on a new branch off `feature/bug-testing-and-refinement` named `refactor/<topic>`. Never touch `master`.
  Never deploy, never restart or touch any server, never push, never commit to another agent's branch.
  Commit messages end with: `Co-Authored-By: Claude Sonnet 5.5 <noreply@anthropic.com>`.

## The job

Audit the main source tree for **code smells, fragility and refactor opportunities**, then make the **safe**
refactors and report the rest. The goal is clean and robust code, not a different mod: **behaviour must not
change**. A refactor that needs a design decision is reported, not done.

### Read first

- `A:\MrPinoys Mods\CLAUDE.md` and `pocketdungeons/docs/AUDIT.md`, `docs/AUDIT_2026-09.md` (earlier audits: do not
  repeat findings already fixed or ruled on).
- `docs/reference/BUGS.md` (the bottom sections) so you know which oddities are known bugs with an owner decision.
- The skills in `A:\MrPinoys Mods\.claude\skills`: `add-tests`, `dialog-screens`, `dungeon-content`,
  `room-building`, `tune-knobs`, `deploy-test-server` (read, do not run).

### Where to look (largest and riskiest first)

`Instances.java` (3,500 lines), `RunLifecycle.java` (2,400), `DungeonCommands.java` (2,100), `DungeonLog.java`
(1,700), `RoomSelector.java`, `PocketDungeonsConfig.java`, `RoomTemplateGenerator.java`, `DialogScreens.java`,
`TrialContent.java`, `InventorySwap.java`, `Lemon.java`, plus `mixin/`. Then sweep the rest.

### What to look for

1. **God classes and long methods:** methods over about 80 lines, classes mixing unrelated jobs, parameter lists
   over 7 (the `DungeonLog.Entry` record is copied by hand in a dozen `withX` methods: look for the same shape
   elsewhere).
2. **Duplication:** copy-pasted blocks, parallel switch statements, the same loop over members or cells written
   several ways.
3. **State and lifecycle bugs:** static maps keyed by slot, UUID or cell origin (`HostileWolves`, `Whelp`,
   `PressureSources.ARMED`, `LibrarianNPC`, `KennelSpecs`, `CapstoneFights`, `FinaleWave`) that are not cleared on
   instance teardown, disconnect, floor end or server stop; leaks; stale references; iteration while mutating.
4. **Threading and timing:** work done off the server thread (note PD-12 style disconnect handlers), reliance on
   tick order, and `runAfterDelay` style callbacks that outlive their instance.
5. **Null and failure handling:** unchecked `getPlayer`, `getLevel`, `getBlockEntity` and `getEntity` results,
   swallowed exceptions, `catch (Exception)` that hides a bug, `Optional.get()` without a check.
6. **Magic numbers and strings:** repeated literals that should be a named constant, config key or data field
   (see the `tune-knobs` skill for what already is a knob); stringly-typed ids and tags used in more than one file.
7. **Mixins:** each mixin should be minimal, gated to the dungeon dimension where it should be
   (`DungeonWorldRules.applies`), and robust to a mapping change; flag any that touch more than they need.
8. **Dead code and stale comments:** unused methods, fields, imports, constants, config keys with no reader,
   comments that contradict the code, TODOs with no owner.
9. **API shape:** package-private versus public, mutable records, boolean parameters, methods that return null
   instead of Optional or an empty list, inconsistent naming for the same concept (the player words are compass,
   scrap, haul, lives, charts; internal names such as omen and keystone are old and may stay).
10. **Test gaps that hide fragility:** logic that is pure and untested, or tested only through a live server.
11. **Data and code drift:** a Java list that must match a JSON registry (room ids, theme ids, action ids) with no
    test tying them together. Propose a scanning test (see `RubbleRulesTest`, `BookLootTest` for the pattern).

### What to leave alone

- Anything in `docs/` that is a historical plan, handoff or playtest report.
- Generated `.nbt` room templates (edit the `RoomSpec`, never the `.nbt`).
- Behaviour the owner decided on (the leaving rules, the scrap curve, the haul model, the sculk Heard meter, the
  one-Warden rule for the Ancient City). If a smell sits inside one of these, report it without changing it.
- Other mods in the workspace (`A:\MrPinoys Mods\<other>`).

## Method

1. Build a map first: for each of the ten largest classes, one paragraph on its jobs and who calls it (use Grep,
   do not guess). Put the map in the report.
2. Rank findings by **risk if left** (crash, leak, wrong behaviour) before **tidiness**.
3. For each finding you act on: write the smallest change that removes the smell, add or extend a test that
   would have caught the old shape where one is practical, and keep each refactor its own commit.
4. Do not combine a refactor with a feature or a bug fix. A real bug you find gets its own commit and its own
   entry (next free number in `docs/reference/BUGS.md`, newest `## <date>` section) with **Reported** and
   **Status** lines, and is called out at the top of your report.
5. After every few commits run the full suite from `pocketdungeons`:

   ```
   .\gradlew.bat test runGameTest --console=plain
   ```

   Read the tail for `BUILD SUCCESSFUL` and the line `All N required tests passed` (N is 241 at the start; a new
   gametest class only runs if listed in `src/gametest/resources/fabric.mod.json`, so check N rises). If a test
   fails, stop and find out whether your change or the test is wrong; never weaken a test to pass it.
6. Mixed line endings: source files are CRLF. Edit with tools that preserve them (do not rewrite whole files with
   a script that normalises endings). Large shell heredocs with embedded quotes have failed in this repo; use the
   file tools or a small script file.

## Deliverables

1. The branch with the safe refactors, one commit per refactor, suite green at the tip.
2. A report at `docs/audit-2026-10-10-report.md`, in this shape:
   - **Summary:** counts by category, what was done, what was left, and the test count before and after.
   - **Bugs found:** each with file and line, a failure scenario (concrete inputs, wrong output), and the BUGS.md
     number you gave it.
   - **Refactors done:** one row per commit: files, the smell, the change, the test that covers it.
   - **Refactors proposed, not done:** each with why it was left (needs a design decision, too risky without a
     live server, touches an owner ruling), the shape you would use, and an effort size (S, M, L).
   - **Map of the ten largest classes** (from the method step).
   - **Unverifiable without a live server:** anything you changed whose behaviour only a play session can confirm.
3. A reply that gives the branch name, the report path, and the three findings you think matter most.

## Hard limits

- No behaviour change, no string change, no config or data change, no new dependency, no new mixin.
- No deleting a test. No disabling a check. No `@SuppressWarnings` to hide a finding.
- Do not run the Lemon tools, `pdserver.mjs` or anything that talks to a server.
- If a step is blocked or the suite will not go green after your change, revert that commit and report it.
