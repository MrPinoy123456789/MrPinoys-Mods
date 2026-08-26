# M7 - Recipes - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` - §5.4 (the design this milestone implements) and
   §6 (why the recipe table has to load across every namespace)
2. `pocketdungeons/ROADMAP.md` - where M7 sits
3. `pocketdungeons/plans/COMPLETED-MILESTONES.md` M7 — **the authoritative scope**, including
   the section "Correction, 2026-08-25", which you must read before T7.1
4. `pocketdungeons/PROGRESS.md` - find the `## M7` table, and read the note on
   T7.0 there
5. `pocketdungeons/INTEGRATION.md` §2 - the published `dungeon_room` schema, the
   shape your two new datapack types must match

## Goal

Server folklore. Someone comes back with *New Dungeon Discovered* and their
friends have to ask what they did.

**Blocked on:** M1, with a caveat you need to know before you start. **Blocks:**
nothing downstream.

## Read this before you plan anything

The plan's opening section says "each door already carries level + affix, M1
adds theme". **That describes M1's intent, not what M1 shipped.** Verified
against the code on 2026-08-25:

- No `dungeon_room/*.json` declares a `theme`, and `RoomManifest.matchesTheme`
  treats an empty theme list as matching every request. Requesting a theme
  currently selects the same rooms as requesting none.
- Processor lists are per **room** (`DungeonRoomMeta.processors`, read at
  `LayoutStamper.java:100`), never per run. `theme_deepslate`,
  `theme_prismarine` and `theme_blackstone` are referenced by nothing.
- The only caller that passes a theme is `/dungeon admin untimed`
  (`DungeonCommands.java:333`). `Instances.generateBehindLobby` passes `null`
  (`Instances.java:1220`).

So **a run does not have a theme and the doors do not offer one**, and T7.1 has
nothing to record. That is why the plan now opens with **T7.0**, which is new
and blocks every other task in this milestone. Do T7.0 first. Do not skip it and
invent a theme from the layout seed; the whole milestone rests on the theme
being something a player *chose*.

This does not reopen M1. M1's bar was `/dungeon admin stamptest` producing three
visibly different dungeons, and that bar is met. Leave M1 marked `DONE`.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. Unverified claims
   get a `⚠ UNVERIFIED` comment in the source.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   blocks, items or registry entries. A theme is a processor list plus a
   display name, not a new registry.
3. **Mods stay strangers** - `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode.** A recipe dungeon's loot
   table still owes M6's guaranteed floor; do not author a table that drops the
   floor because it is a special dungeon.
5. **The mod stays quiet about the closed loop** (`VISION.md` §4), and this
   milestone has its own version of the same discipline: **do not build a
   recipe hint system, a progress bar, or a recipe book.** T7.3 is explicit.
   The goal is word of mouth, and a UI that tells players what to try converts
   folklore into a checklist and kills the feature. If you are tempted to add
   "helpful" UI here, do not. That is the one wrong turn this milestone can
   take.
6. **Superseded designs get marked superseded, not deleted.** The plan's
   original opening section stays where it is, with the correction beneath it.
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it.

## Decisions already taken. Do not relitigate them

| Decision | Why |
|---|---|
| A matched recipe **replaces door 3's offer**; three physical doors stay | `selectorDoorStep` resolves doors by hardcoded wall positions (`along` 7/8/9, `Instances.java:1147`). A fourth door is template and geometry work for no design gain. Door 1 must stay the safe `+1` |
| A room's own `processors` **wins** over the run theme | A grove inside a deepslate run is still a grove. A strange chamber that recolours to match the walls is not strange |
| Door themes are seeded from `(owner, level, step)`, never from run randomness | The doors are re-rendered by both the watcher and the dialog. Same stability requirement M4 T4.2/T4.4 met |
| Nothing is consumed when a recipe fires | Completing the recipe dungeon pushes its own theme into the window, which breaks the match on its own. A "used" flag is state that can disagree with the window beside it |
| The theme never touches `InstanceLayout` | 16-component record, several construction sites including `forClearingOnly`, and teardown does not want to know the theme |
| The player's room cell is never themed | It is a raw `RoomStore` blob through `placeRotated`, which runs no processors by design |

## Process

1. Read every doc above in full.
2. In `PROGRESS.md`, set each M7 task to `WIP` with your name and the date
   *before* the first edit.
3. Implement per `plans/COMPLETED-MILESTONES.md` M7, in task order. T7.0 first; T7.4 last.
   - **T7.0's loaders**: model `ThemeManifest` on `RoomManifest` line for line,
     including the startup ordering guard its `server` field documents (the
     resource reload runs before `SERVER_STARTED`, so the first pass is covered
     by the explicit load in `Instances.register()`, not by the listener). Keep
     the Gson parse in a separate no-Minecraft-imports class the way
     `DungeonRoomMeta` is, so it stays unit testable.
   - **T7.1's `DungeonLog` change** needs `optionalFieldOf` plus a default, the
     same trick `keystone_affix` uses. Verify old saves explicitly rather than
     assuming the pattern transferred: load a `dungeon_log.dat` written before
     your change.
   - **T7.2's recipe table** must load via `listResources` across **all**
     namespaces, matching `dungeon_room`. Load themes *before* recipes so a
     recipe naming an unloaded theme is rejected loudly at load. A recipe that
     silently never fires is indistinguishable from one nobody has found, which
     is the worst failure mode this feature has.
   - **T7.3** is one line in `/dungeon log`. If it grows past that, re-read
     standing rule 5.
4. Verify:
   - `./gradlew build` green.
   - Pure-JDK tests, in the existing style (`AffixMathTest`,
     `DungeonRoomMetaTest`, `PlanSelectorTest`): theme parse and rejection;
     door-theme stability across 100 repeat calls for one owner and level, and
     divergence across owners; recipe match including **the negative**
     (`[a,c,b]` must not match `[a,b,c]`); `DungeonLog` window push/truncate and
     pre-M7 load.
   - Live-server proof via `tools/rcon.py` on the 26.2 dev server:
     - Three doors show three different theme names, and the dungeon behind the
       chosen one is visibly that palette. Probe blocks, do not eyeball a
       screenshot: M6 T6.3 has the working idiom for this.
     - A grove room inside a themed run still has its grove blocks.
     - Complete three themes in a specific order, confirm the recipe dungeon is
       offered on door 3; **then confirm the same three in a different order do
       not offer it.** Order sensitivity is the core claim of this milestone and
       needs an explicit negative test, not an assumption.
     - Add a recipe as a JSON file in a scratch datapack, `/reload`, confirm it
       is live with no restart. Then add one naming a theme that does not exist
       and confirm it lands in `rejections()`.
     - `/dungeon log` shows completed themes and counts, and nothing anywhere
       shows a combination.
   - Multiplayer checks (a companion's theme window being their own, not the
     owner's) are deferred to the suite-wide pass, per the note at the top of
     `PROGRESS.md`. Mark them deferred; do not mark them done.
5. Update docs:
   - `INTEGRATION.md`: two new datapack surfaces, `dungeon_theme` and
     `dungeon_recipe`, with their schema tables and validation failures
     verbatim, matching §2's existing shape. This is the platform pitch; a pack
     author cannot ship a recipe against an unpublished schema.
   - `PROGRESS.md`: every M7 task including T7.0 → `DONE` with what was
     verified and what was deferred. One Session Log line, newest first. Move
     "Current milestone" forward. M8 needs an explicit promotion decision per
     its own file; do not promote anything from there automatically.
   - If the plan turns out to be wrong about something, **fix the plan first and
     say so in the log**, the way the T7.0 correction was handled. Never
     silently diverge.
6. Commit, following this repo's commit style (`git log`, `f7345eb`).
7. **Last step, after everything is committed:** `git mv
   handoffs/M7-handoff.md handoffs/M7-handoff-completed.md` and commit that.

## House style

`../CLAUDE.md` at the repo root: **no em dashes and no `--` as punctuation**,
anywhere a person reads. Player-facing strings, markdown, javadoc, commit
messages. Command line flags and `i--` are not punctuation and are fine. Do not
mass-rewrite existing comments to satisfy this; it governs what you write.

## Scope

| # | Task |
|---|---|
| T7.0 | A run has a theme, and the doors offer it. New `dungeon_theme` type, run-wide processors, theme on `Keystone.Offer`. **Blocks everything below** |
| T7.1 | Record the last N completed themes in `DungeonLog`, `optionalFieldOf` + default, no migration |
| T7.2 | Recipe table as datapack JSON: `[theme, theme, theme] → theme id`, order matters, `/reload`-driven, replaces door 3 |
| T7.3 | Discovery floor. Completed themes visible in `/dungeon log`, combinations not. **No hint system** |
| T7.4 | First recipe dungeon. Cheap and data-shaped: one theme JSON, one recipe JSON, one loot suffix |

## Done when

- [ ] Three doors offer three themes, and the run behind the chosen door is that
      palette
- [ ] Completing three specific themes in order offers a dungeon that is not in
      the normal pool
- [ ] The same three themes in a different order do **not**
- [ ] A player can see which themes they have completed, and cannot see the
      combinations
- [ ] A pack author adds a theme and a recipe with JSON files and `/reload`
- [ ] Old saves load with no migration step
- [ ] `INTEGRATION.md` publishes both new schemas
- [ ] `./gradlew build` green
