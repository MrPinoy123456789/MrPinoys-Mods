# Pocket Dungeons — Progress

> **This is the only file that records status.** `ROADMAP.md` holds the order,
> `plans/M<n>-*.md` hold the how, `VISION.md` holds the why. None of those three
> may contain a checkbox or a status column.
>
> **Last updated:** 2026-08-24 · **Current milestone:** M0

---

## Starting a milestone

Each milestone has a self-contained handoff prompt at
`handoffs/M<n>-handoff.md` — paste the whole file into a fresh chat to start
work on it with no other context required. Every handoff includes the reading
list, the standing rules, the verification bar (including live-server RCON
proof via `tools/rcon.py` where relevant), the doc-update checklist, and the
final step: renaming itself to `M<n>-handoff-completed.md` once the milestone
is actually `DONE` here. A milestone with no `-completed` handoff is either not
started or not finished — check this file's tables for the real status, the
handoff filename is a convenience marker, not the source of truth.

---

## How agents use this file

1. **Read before starting.** Check the task is `TODO` and that its milestone's
   blockers are `DONE`. Milestones run in roadmap order; do not skip.
2. **Claim it.** Set the task to `WIP` with your name and the date *before* the
   first edit, so two agents do not collide.
3. **Work the plan.** `plans/M<n>-*.md` is authoritative for scope. If the plan
   is wrong, fix the plan first and say so in the log — never silently diverge.
4. **Finish honestly.** `DONE` requires the plan's own "Done when" boxes ticked
   and `./gradlew build` green. Anything partial stays `WIP` with a note.
5. **Log it.** One line in the Session Log. Newest first.

### Status values

| | Meaning |
|---|---|
| `TODO` | Not started |
| `WIP` | Claimed, in progress — **must** name who and when |
| `BLOCKED` | Cannot proceed; the note says what by |
| `DONE` | Plan's "Done when" satisfied, build green |
| `CUT` | Deliberately dropped; the note says why |

### Rules that outrank the plans

1. **Verify against the 26.2 jar, not memory.** `javap -cp` / `unzip -l` on
   `~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar`. Unverified claims
   get a `⚠ UNVERIFIED` comment in the source.
2. **No client mod, ever.** `"environment": "server"`, no `assets/`, no custom
   blocks, items or registry entries.
3. **Mods stay strangers** — `kamutotems/INTEGRATION.md`.
4. **Self-sufficiency is a constraint**, checked every change.
5. **The mod stays quiet about the trick** (`VISION.md` §4).
6. **Superseded designs are marked superseded, not deleted.**
7. **Do not reopen a decision in `MYTHIC_PLUS_RECONCILIATION.md` §7** without
   writing down what new information changed it.

---

## M0 — Entry fee and safety · `plans/M0-entry-fee.md`

| # | Task | Status | Note |
|---|---|---|---|
| T0.1 | `RoomManifest` on `/reload` | `DONE` | 2026-08-24, Devin. Wired via `RoomManifest.register()` (`fabric-resource-loader-v0`). Verified live over RCON on the dev server: edited `hall_tee.json`'s `roles`, ran `/reload` with no restart, `/dungeon admin manifest list` reflected the change; reverted and reloaded again to confirm it tracks edits both ways |
| T0.2 | `LICENSE` at repo root | `DONE` | 2026-08-24, Devin. Every `fabric.mod.json` in the suite (16 mods) declares `"license": "MIT"`, so the root `LICENSE` covers the whole suite, not just `pocketdungeons/` |
| T0.3 | Published `dungeon_room` schema | `DONE` | 2026-08-24, Devin. Table + validation failures verbatim in `INTEGRATION.md` §2, sourced from `DungeonRoomMeta`/`RoomManifest` |
| T0.4 | `INTEGRATION.md` | `DONE` | 2026-08-24, Devin. Five surfaces + three non-extensible items, following `kamutotems/INTEGRATION.md`'s structure |
| T0.5 | Owner check on selector doors | `WIP` | 2026-08-24, Devin. Code fixed (`Instances.selectorDoorStep` now also requires `player.getUUID().equals(record.owner)`) and build-green, but the plan's live positive/negative check needs two connected clients clicking blocks, which RCON can't simulate. Deferred to a human multiplayer pass — see Session Log |

## M1 — Themes foundation · `plans/M1-themes-foundation.md`

| # | Task | Status | Note |
|---|---|---|---|
| T1.1 | Wire `processors` | `DONE` | Committed `f7345eb`. Verified in-world over RCON: 4332 deepslate / 0 stone with a datapack theme, 0 / 4802 without. `DungeonRoomMetaTest` added to the harness |
| T1.2 | `theme` field + `RoomSelector` filter | `TODO` | |
| T1.3 | Three proof themes | `TODO` | deepslate / prismarine / blackstone |

## M2 — The room · `plans/M2-the-room.md`

| # | Task | Status | Note |
|---|---|---|---|
| T2.1 | Persist room as a blob | `TODO` | **Backup-on-write and `admin baserestore` are not optional here** — first milestone storing player content |
| T2.2 | Permission mask | `TODO` | Positional, not global — the quarry must stay breakable |
| T2.3 | Bedrock envelope | `TODO` | |
| T2.4 | The closed loop | `TODO` | **capture → persist → clear → stamp**, no early returns |
| T2.5 | Door-as-entrance, lingering quarry | `TODO` | Needs a third lifecycle state |
| T2.6 | Purge on leadership change | `TODO` | Decided §7.2 |

## M3 — The calling card · `plans/M3-calling-card.md`

| # | Task | Status | Note |
|---|---|---|---|
| T3.1 | Mint a calling card | `TODO` | Owner UUID, **not** a position |
| T3.2 | Use-on-lodestone | `TODO` | Positive test; foreign items must PASS |
| T3.3 | Shared visit instance, refcounted | `TODO` | Read-only, never writes back |
| T3.4 | Visitor permissions | `TODO` | Reuses T2.2 wholesale |

## M4 — Affixes · `plans/M4-affixes.md`

| # | Task | Status | Note |
|---|---|---|---|
| T4.1 | `Affix` enum → set | `TODO` | 3 branch sites; no codec migration |
| T4.2 | Thresholds 5 / 11 / 17 | `TODO` | Seeded from the key. **No weekly rotation** |
| T4.3 | Depletion takes the max | `TODO` | Capped at 2×, never the product |
| T4.4 | Naming | `TODO` | Enum order, always |
| T4.5 | Swarming / Overclocked / Molten / Silenced | `TODO` | Every one owes a kiss |

## M5 — Wolves and Feral · `plans/M5-wolves-feral.md`

| # | Task | Status | Note |
|---|---|---|---|
| T5.1 | Spawn at stamp time | `TODO` | Reintroduces a spawn path deleted in T17 — keep it narrow |
| T5.2 | Coats by tier | `TODO` | 9 variants |
| T5.3 | Bones as a guaranteed floor | `BLOCKED` | On T6.1 |
| T5.4 | Spirit Stone permanence | `TODO` | Zero code both sides; verify no import appears |

## M6 — Supply · `plans/M6-supply.md`

| # | Task | Status | Note |
|---|---|---|---|
| T6.1 | Floors for consumables, rolls for treasure | `TODO` | Unblocks T5.3 |
| T6.2 | Tiered building blocks | `TODO` | |
| T6.3 | Grove room; seeds and dirt | `TODO` | One template, one JSON, no Java |
| T6.4 | Nether/End products, not ingredients | `TODO` | Lava is Molten's job |
| T6.5 | Entry / exit / join / stray plumbing | `TODO` | |

## M7 — Recipes · `plans/M7-recipes.md`

| # | Task | Status | Note |
|---|---|---|---|
| T7.1 | Record last N themes in `DungeonLog` | `TODO` | |
| T7.2 | Recipe table as datapack JSON | `TODO` | `/reload`-driven from day one |
| T7.3 | Discovery floor | `TODO` | Ingredients visible, combinations not |
| T7.4 | First recipe dungeon | `TODO` | Data-shaped, not a generator variant |

## M8 — Deferred · `plans/M8-deferred.md`

| # | Item | Status | Note |
|---|---|---|---|
| D1 | Outdoor themes | `TODO` | Needs a second dimension |
| D2 | One rule-breaking dungeon | `TODO` | Endless Mine only |
| D3 | Data-driven affixes | `TODO` | After 5–6 exist in Java |
| D4 | Multi-cell footprints | `TODO` | Generator is 1×1 |
| D5 | Room size, station unlocks | `TODO` | After M2 and M6 |
| D6 | Lava as a second faucet | `TODO` | Only if M6 shows a pinch |

**Nothing in M8 may be started without an explicit promotion decision.**

---

## Session log

Newest first. One line each: date — who — what changed.

| Date | Who | What |
|---|---|---|
| 2026-08-24 | Devin | M0 T0.1–T0.4 done, T0.5 code-complete pending a human multiplayer test. `RoomManifest` now reloads on `/reload` (verified live over RCON); root `LICENSE` added (MIT, suite-wide); `INTEGRATION.md` written with the schema table and verbatim validation failures; `selectorDoorStep` gated on `record.owner`. `./gradlew build` green |
| 2026-08-24 | design session | Spec reframed on Skyblock/StoneBlock, Factions cut. Weekly affix rotation cut. Kiss/curse made the affix rule. Calling card designed as the visit mechanism. Every open question closed (§7). Roadmap, plans and this file created |
| 2026-08-24 | design session | T1.1 `processors` wired, verified end to end on a 26.2 dev server over RCON, committed `f7345eb` |

---

## Decisions that are closed

Do not reopen without new information, written down.

| Decision | Where |
|---|---|
| Skyblock is the proof, **not** the model — no scarcity, no bootstrapping | `VISION.md` §2, §3.7.1 |
| Visitability is the thesis, not a backlog item | `VISION.md` §2.1 |
| Weekly affix rotation is **cut**; affixes seed from the key | §4 |
| Thresholds are **5 / 11 / 17** | §4.1 |
| Every affix owes a kiss | §5.0 |
| Depletion takes the `max`, capped at 2× | §7.1 |
| Leadership change **purges**, never transfers | §7.2 |
| Molten is the lava faucet; Silenced deafens the mobs | §5.5, §7.3 |
| Wolves: 1-in-3 per bone, angry refuses, tamed sits — spawn **neutral** | §7.4 |
| The mod says **nothing** about the closed loop | `VISION.md` §4 |

*(§ references without a filename are `MYTHIC_PLUS_RECONCILIATION.md`.)*
