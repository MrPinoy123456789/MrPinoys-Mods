# M1 — Themes foundation — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` — §5.1–5.3 is what this milestone is for
2. `pocketdungeons/ROADMAP.md` — where M1 sits
3. `pocketdungeons/plans/COMPLETED-MILESTONES.md` M1 — **the authoritative scope**
4. `pocketdungeons/PROGRESS.md` — find the `## M1` table for current status

**T1.1 ("wire `processors`") is already `DONE`**, committed as `f7345eb`. Read
that commit (`git show f7345eb`) before starting — it establishes the pattern
this milestone's remaining tasks build on, including how the reconciliation
worktree/RCON verification was done. Don't redo T1.1.

## Goal

14 templates × N processor lists instead of 140 hand-authored `.nbt` files.
After this milestone a theme is datapack JSON and needs no recompile.

**Blocked on:** M0 (only for iteration comfort — not a hard block).
**Blocks:** M7 (recipes need themes to exist), all future content work.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`.
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`'s rule.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4).
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it.

## Process

1. Read the docs above in full, including the T1.1 commit.
2. In `PROGRESS.md`, set T1.2 and T1.3 to `WIP` with your name/date before
   touching them.
3. Implement per `plans/COMPLETED-MILESTONES.md` M1.
4. Verify — **this milestone is exactly where end-to-end proof matters most**,
   because a theme that silently fails to apply is invisible until someone
   notices a wall is the wrong colour:
   - `./gradlew build` green.
   - Use `tools/rcon.py` the way T1.1's commit message documents: build a proof
     datapack, enable RCON (back up `server.properties` first), start
     `./gradlew runServer --offline` in the background, wait for "RCON running"
     in `run/logs/latest.log`, then drive it.
   - **Do both a positive and negative check** — build a dungeon with the theme
     active and `fill ... replace` count the themed blocks; build one without it
     and confirm the count is zero. A single positive result proves nothing on
     its own (the template could already have been that colour).
   - Confirm a bad processor-list id is rejected **at manifest load**, named in
     the rejection message — not silently ignored at stamp time.
   - Always restore `server.properties` from your backup and stop the server
     when done. Delete any scratch datapack you created under `run/world/`.
5. Update docs:
   - `PROGRESS.md`: T1.2/T1.3 → `DONE` with commit hash and what was verified
     (include the actual block counts from your RCON check, the way T1.1's entry
     does). Session Log line. Move "Current milestone" to `M2` if M1 is fully done.
   - If the theme-family cost table in `VISION.md` §5.3 needs adjusting based on
     what you actually found cheap/expensive, update it there, not here.
6. Commit, following this repo's commit style (see `git log`, and `f7345eb` for
   the level of verification detail expected in the message). No `--no-verify`.
7. **Last step, after everything is committed:** `git mv
   handoffs/M1-handoff.md handoffs/M1-handoff-completed.md` and commit that.

## Scope

| # | Task | Status going in |
|---|---|---|
| T1.1 | Wire `processors` through `TemplateStamper` | **Already `DONE`** — `f7345eb` |
| T1.2 | `theme` field on `DungeonRoomMeta`, symmetric with `roles`; filter in `RoomSelector` | |
| T1.3 | Three proof themes as processor lists (deepslate, prismarine, blackstone) | |

Key hazards from the plan:
- Theme processors must be added **after** `JigsawReplacementProcessor` (already
  the case in the committed T1.1 code) or doorway blocks stay untinted.
- Filtering the room pool by theme could break `RoomSelector.validate`'s
  reachability guarantee if a theme doesn't cover all 53 (mask, role)
  combinations — but themes are processor lists on *shared* templates, so the
  pool shouldn't shrink unless T1.2's filter is written to exclude non-matching
  rooms rather than just preferring matching ones. Get this right; a subtle bug
  here fails as "the generator sometimes can't finish a layout," not obviously.
- Keep `sea_lantern` in every theme unless you have a replacement light source —
  the dimension is `ambient_light: 0.0`, so removing light sources makes a
  themed dungeon unplayable rather than atmospheric.

## Done when

- [ ] Adding `"processors": "pocketdungeons:theme_deepslate"` to one
      `dungeon_room` json and running `/reload` changes that room's palette
- [ ] A bad processor-list id is rejected at manifest load, named in
      `rejections()`, not silently ignored at stamp time
- [ ] Three themes produce three visibly different dungeons from the same 14
      `.nbt` files, proven with positive *and* negative RCON block counts
- [ ] No recompile was needed for any of the above
- [ ] `./gradlew build` green
