# M2 — The room — Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Where this is

Repo: `A:\MrPinoys Mods` (git, branch `master`). This milestone lives entirely
in `pocketdungeons/`, a server-side Fabric mod for MC 26.2.

Read, in order, before writing anything:

1. `pocketdungeons/VISION.md` — §3.1 (why the room is the centre of the design)
   and §4 (the closed loop, and why it must stay silent)
2. `pocketdungeons/MYTHIC_PLUS_RECONCILIATION.md` — §3.2–3.2.4 (the detailed
   design this milestone implements) and §7.2 (the leadership-purge decision)
3. `pocketdungeons/ROADMAP.md` — where M2 sits
4. `pocketdungeons/plans/M2-the-room.md` — **the authoritative scope**
5. `pocketdungeons/PROGRESS.md` — find the `## M2` table

## Goal

The selector room becomes a persistent, owned, decoratable room, and a run ends
by walking into it.

**Blocked on:** nothing hard, but this is the milestone with real architectural
risk — everything before it was additive, this one changes the lifecycle.
**Blocks:** M3 entirely. Nothing in M3 can start until this is `DONE`.

## Standing rules (outrank the plan if they ever conflict)

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`.
2. **No client mod, ever.**
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint, not a mode.**
5. **The mod stays quiet about the closed loop** (`VISION.md` §4) — this is the
   rule this entire milestone is built to protect. No message, no sound, no
   title, no particle on capture/clear/re-stamp. If you catch yourself writing
   a player-facing string anywhere in this milestone's code path, stop and
   re-read §4 before deciding whether it actually belongs there.
6. **Superseded designs get marked superseded, not deleted.**
7. **Do not reopen a `MYTHIC_PLUS_RECONCILIATION.md` §7 decision** without
   writing down what changed it. §7.2 (leadership purges rather than
   transfers) is settled; implement it, don't relitigate it.

## Process

1. Read every doc above in full before writing code. This milestone has more
   design detail behind it than any other — do not start from the plan alone.
2. In `PROGRESS.md`, set each M2 task to `WIP` with your name/date before
   touching it.
3. Implement per `plans/M2-the-room.md`. **The capture → persist → clear → stamp
   order is not negotiable** — write it as one method with no early returns
   between capture and persist, so a crash mid-sequence cannot lose a room.
4. Verify:
   - `./gradlew build` green.
   - Live-server proof via `tools/rcon.py` (see its docstring, and `f7345eb` /
     the M1 handoff for the pattern): decorate a room, run a dungeon, complete
     it, confirm the player lands in the *same* decorated room at the terminal
     cell, and confirm the entrance cell is genuinely empty afterward — not
     just visually, check for lingering block entities / force-load state.
   - Inject a failure between capture and stamp (e.g. `/kill` the server or
     interrupt mid-`PendingClear`) and confirm no room is lost. This is the one
     test in the whole roadmap worth spending real effort on.
   - Confirm a non-whitelisted player cannot break a block or open a container
     in the room, but can use a station and the ender chest — test all three,
     don't assume the mask generalizes correctly from one check.
   - Confirm nothing was said to the player anywhere in this flow. Read your
     own diff for player-facing strings before calling this done.
5. Update docs:
   - `PROGRESS.md`: every M2 task → `DONE`/`BLOCKED`/`CUT` with commit hash and
     what was verified. Session Log line. Move "Current milestone" to `M3` if
     M2 is fully done — and note there that M3 is now unblocked.
   - If you had to make a call the plan left open (the exact lifecycle state
     model for the lingering quarry, T2.5's "third state between live and
     purged"), write the decision into `MYTHIC_PLUS_RECONCILIATION.md` in the
     same style as the existing §7 entries, so it doesn't get relitigated later.
6. Commit, following this repo's commit style (`git log`, `f7345eb`). Consider
   splitting into a few commits along the plan's task boundaries (T2.1–T2.6)
   rather than one giant commit — this milestone is large enough that a single
   commit will be hard to review or bisect later.
7. **Last step, after everything is committed:** `git mv
   handoffs/M2-handoff.md handoffs/M2-handoff-completed.md` and commit that.

## Scope

| # | Task |
|---|---|
| T2.1 | Persist the room as a `StructureTemplate` blob keyed by owner |
| T2.2 | Owner + whitelist permission mask — **positional, not global**; the quarry must stay breakable |
| T2.3 | Bedrock envelope — sub-floor and over-ceiling always, outer ring only on faces with no adjacent cell |
| T2.4 | The closed loop — capture → persist → clear → stamp, order non-negotiable |
| T2.5 | Door-as-entrance; the finished dungeon lingers as a quarry — needs a third lifecycle state |
| T2.6 | Purge on unexpected leadership change, per §7.2 |

## Done when

- [ ] A player decorates their room, runs a dungeon, opens the last door, and
      walks into that room with the decorations intact
- [ ] Walking back to the entrance cell finds it empty
- [ ] The finished dungeon is still standing and still mineable
- [ ] A non-whitelisted player cannot break a block or open a chest in the room,
      but can use a station and the ender chest
- [ ] Nothing was said to the player about any of it
- [ ] A crash injected between capture and stamp loses no room
- [ ] `./gradlew build` green
