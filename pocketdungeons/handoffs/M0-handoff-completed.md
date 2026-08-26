# M0 — Entry fee and safety — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` — why the mod exists; §6.1 is what this milestone closes
2. `pocketdungeons/ROADMAP.md` — where M0 sits (first; nothing blocks it)
3. `pocketdungeons/plans/COMPLETED-MILESTONES.md` M0 — **the authoritative scope for this work**
4. `pocketdungeons/PROGRESS.md` — find the `## M0` table for current task status

## Goal

A third party can write a datapack against this mod without reading its source,
and the repo states its own licence.

**Blocked on:** nothing. **Blocks:** M1 iteration speed, all of `VISION.md` §6.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. Anything unverified
   gets a `⚠ UNVERIFIED` comment in the source.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   blocks, items or registry entries.
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`'s rule. No compile-time
   coupling with `spiritwolves` or anything else in the suite.
4. **Self-sufficiency is a constraint, not a mode** — check every change against
   "could a player progress without ever leaving?"
5. **The mod stays quiet about the closed loop** (`VISION.md` §4).
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without writing
   down what new information changed it.

## Process

1. Read the docs above in full first.
2. In `PROGRESS.md`, set each task in the `## M0` table to `WIP` with your
   name/date **before** touching it.
3. Implement per `plans/COMPLETED-MILESTONES.md` M0. If the plan turns out wrong — like the
   version of this milestone before it was corrected, which had filed T0.5 as an
   exploitable permission bug when the code doesn't actually allow that — fix the
   plan file itself and say why. Don't silently diverge from a stale plan without
   correcting it for the next reader.
4. Verify:
   - `./gradlew build` green.
   - T0.1 (`/reload`) needs a live check: edit a `dungeon_room/*.json` weight,
     reload, confirm `/dungeon admin manifest list` reflects it with no restart.
     Use `tools/rcon.py` — see its docstring for the enable/restore steps. Always
     restore `run/server.properties` from your backup afterward; leaving RCON
     open on a stopped dev server is fine, leaving it open and forgotten is not.
   - T0.5 needs both a positive and negative check: a guest in the host's
     selector room gets no door prompt; the owner in their own room still does.
5. Update docs:
   - `PROGRESS.md`: every M0 task → `DONE`/`BLOCKED`/`CUT` with the commit hash
     and what was verified. One line in the Session Log. If M0 is fully done,
     move "Current milestone" at the top to `M1`.
   - `pocketdungeons/INTEGRATION.md` and the schema table (T0.3, T0.4) are
     themselves the deliverable here, not side notes — write them carefully,
     they're what a pack author reads first.
6. Commit, following this repo's existing commit style (see `git log` — explain
   *why*, not just *what*). No `--no-verify`, no force-push.
7. **Last step, after everything above is committed:** rename this file to
   `handoffs/M0-handoff-completed.md` via `git mv`, and commit that.

## Scope

| # | Task |
|---|---|
| T0.1 | `RoomManifest` driven by `/reload`, not just the admin command |
| T0.2 | `LICENSE` at repo root matching `fabric.mod.json`'s MIT claim — confirm scope covers the whole suite, not just `pocketdungeons/` |
| T0.3 | Published `dungeon_room` schema, including the validation failures verbatim |
| T0.4 | `INTEGRATION.md` — the five extensible surfaces, and the three things that are *not* extensible yet |
| T0.5 | Owner check on selector doors — a tidy-up, not a security fix; read the plan's correction before touching this |

## Done when

- [ ] A `dungeon_room` edit takes effect on `/reload` with no restart
- [ ] `LICENSE` exists at the repo root and matches `fabric.mod.json`
- [ ] `INTEGRATION.md` documents all five surfaces and names the three that are
      not extensible
- [ ] The schema table is published and lists the validation failures verbatim
- [ ] A guest in a host's selector room gets no prompt
- [ ] `./gradlew build` green
