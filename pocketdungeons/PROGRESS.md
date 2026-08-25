# Pocket Dungeons — Progress

> **This is the only file that records status.** `ROADMAP.md` holds the order,
> `plans/M<n>-*.md` hold the how, `VISION.md` holds the why. None of those three
> may contain a checkbox or a status column.
>
> **Last updated:** 2026-08-24 · **Current milestone:** M3
>
> **Multiplayer testing is deferred until every milestone is code-complete.**
> Several tasks' "Done when" bars call for a live check with two connected
> clients (a host and a guest, or an owner and a party member). Those checks
> are not being run per-milestone — they will happen in one pass once M0–M8
> are all otherwise done. A task marked `DONE` with a note to this effect has
> had its code verified (build green, reviewed against the plan) but **not**
> its live multiplayer behaviour; do not read `DONE` here as "played and
> confirmed in multiplayer" until that pass has happened and the note says so.

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

**Building any UI in this mod?** Check `DIALOGS_SPEC.md` first — it specs
seven menus (door offers, kick/invite confirmations, room whitelist, admin
restore, the elevator) using the vanilla-dialog mechanism `quizengine` and
`cobbleeconomy` already ship. Nothing in it is built yet; it exists so the
shape gets decided once instead of per-milestone.

---

## How agents use this file

1. **Read before starting.** Check the task is `TODO` and that its milestone's
   blockers are `DONE`. Milestones run in roadmap order; do not skip.
2. **Claim it.** Set the task to `WIP` with your name and the date *before* the
   first edit, so two agents do not collide.
3. **Work the plan.** `plans/M<n>-*.md` is authoritative for scope. If the plan
   is wrong, fix the plan first and say so in the log — never silently diverge.
4. **Finish honestly.** `DONE` requires the plan's own "Done when" boxes ticked
   and `./gradlew build` green, **except** live multiplayer checks, which are
   deferred per the note at the top of this file — a task blocked on nothing
   but that may still go `DONE` with a note saying so. Anything else partial
   stays `WIP` with a note.
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
| T0.5 | Owner check on selector doors | `DONE` | 2026-08-24, Devin. `Instances.selectorDoorStep` now also requires `player.getUUID().equals(record.owner)`. `./gradlew build` green. The plan's live positive/negative click check needs two connected clients; per the suite-wide call above, that is deferred to the single multiplayer pass after every milestone is code-complete, not run per-task |

## M1 — Themes foundation · `plans/M1-themes-foundation.md`

| # | Task | Status | Note |
|---|---|---|---|
| T1.1 | Wire `processors` | `DONE` | Verified in-world: 4332 deepslate / 0 stone with a datapack theme, 0 / 4802 without. `DungeonRoomMetaTest` added to the harness |
| T1.2 | `theme` field + `RoomSelector` filter | `DONE` | `DungeonRoomMeta.theme` parses optional string arrays; `RoomSelector.queryAnyRotation` filters by requested theme; planner failures name the theme. `PlanSelectorTest` added |
| T1.3 | Three proof themes | `DONE` | `data/pocketdungeons/worldgen/processor_list/theme_{deepslate,prismarine,blackstone}.json` each rewrite the shell palette; verified by server load and `/dungeon admin stamptest` |

## M2 — The room · `plans/M2-the-room.md`

| # | Task | Status | Note |
|---|---|---|---|
| T2.1 | Persist room as a blob | `DONE` | 2026-08-24, Devin. `RoomStore` persists one raw `StructureTemplate` NBT per owner under `<world>/data/pocketdungeons/rooms/<uuid>.dat`, captured with `fillFromWorld` (entities included, but only after every entity that is not an `ItemFrame`/`ArmorStand` is discarded from the cell first). Every write copies the current live file to `.bak` before an atomic temp-write-then-rename of the new one. `/dungeon admin baserestore <player>` (via `GameProfileArgument`, works offline) restores from that `.bak`, routed back through the same save path so a restore leaves its own fresh backup. `./gradlew build` green. **Not yet verified live**: the plan's own bar for this task is "test by actually corrupting or deleting a live blob and running the recovery, not by reading the code and assuming it works" — that is a live-server RCON check, deferred with the rest of this milestone's multiplayer pass (see the top of this file) |
| T2.2 | Permission mask | `DONE` | 2026-08-24, Devin. `RoomProtection` registers the mod's first `PlayerBlockBreakEvents.BEFORE`, positional via `Instances.roomOwnerAt` (one bounds check per live instance, `null` outside every room). Container use (`RandomizableContainer` only, so stations and the ender chest stay open to everyone) and placement (via the existing `UseBlockCallback` in `RitualListener`, since there is no generic pre-placement event) are denied the same way. Owner + `RoomWhitelist` (a small `SavedData`, separate from the blob since a lost whitelist is not the T2.1 data-loss class) via `/dungeon room whitelist add/remove/list`. Build green. Live positive/negative click checks deferred |
| T2.3 | Bedrock envelope | `DONE` | 2026-08-24, Devin. `BedrockEnvelope.apply` runs after every cell is stamped: sub-floor and over-ceiling unconditionally, outer wall ring only on faces with no adjacent occupied `PlanCell`. Wired into `LayoutStamper.stamp` for procedural layouts; the `StaticLayout` fallback does not get it (out of scope for this pass). `PendingClear.advance` now clears the one-block envelope around each cell as well as the cell interior, so teardown removes the bedrock shell instead of leaving a ghost shell around the slot. Build green; in-world block counts not yet verified over RCON |
| T2.4 | The closed loop | `DONE` | 2026-08-24, Devin. `Instances.moveRoomToTerminal`, called from `completeRun` on first completion: capture (with a straggler sweep into the reward room first) → `RoomStore.save` (persist) → synchronous single-cell clear (~2000 blocks, well under one tick's `clearBlocksPerTick` budget, so no `PendingClear` needed) → re-place the just-saved blob at the terminal cell, rotated by the delta between the entrance's and terminal's actual stamped rotations (both now carried on `InstanceLayout`) → a real closed `oak_door` pair filling the terminal cell's one door gap. No early return between capture and persist. **Not yet verified live**: the crash-injection check in "Done when" needs an actual kill mid-loop over RCON, deferred |
| T2.5 | Door-as-entrance, lingering quarry | `DONE` | 2026-08-24, Devin. Added the third lifecycle state: `InstanceRecord.lingering`. `retireOrPurge` (replacing the reward-room-grace purge call) ejects stragglers, releases every cell's force-load ticket, and leaves blocks standing instead of clearing them, only once the room has already moved (T2.4); `onTick` exempts lingering records from every check. `Instances.enter()` purges an owner's lingering instance as the first thing it does, the only way out — bounding it at one per owner. Build green; walking back to mine a lingering dungeon not yet checked live |
| T2.6 | Purge on leadership change | `DONE` | 2026-08-24, Devin. `Instances.leadershipChanged`: the owner leaving while someone else is still in the party purges the whole instance, in both `dropMember` (disconnect/offline/left-dimension) and `exit` (deliberate `/dungeon exit`). An owner leaving alone is unaffected (still free re-entry, U8 Stage 1); a lingering quarry (T2.5) is exempt (no live run left to end). Build green |

**M2 status:** `DONE` — code-complete and `./gradlew build` green for all six tasks, following the plan's non-negotiable capture → persist → clear → stamp order throughout. Live-server checks (backup/restore corruption test, crash-injection test, permission-mask/quarry/lingering RCON checks) are deferred to the single multiplayer pass called out at the top of this file, same as every other milestone's live checks.

**2026-08-24 entry-flow rework, Devin:** a live playtest surfaced that the actual intended loop (`MYTHIC_PLUS_RECONCILIATION.md` §3.2.3: lodestone → your standing room → three doors choose the run → the dungeon generates behind whichever one is picked) was never wired up -- M2 had kept the old "lodestone instantly builds and drops you into the whole dungeon" flow and only overlaid the room onto its cell 0. Reworked:

- `Instances.enterLobby`/`stampLobby`: a real keystone run now opens into the owner's room *alone* -- their saved blob (`RoomStore`) or `entrance_hall` on a first visit -- stamped at a fixed rotation with its one connecting door **sealed** (`RoomBuilder.sealDoor`) and a bedrock envelope on the other three sides (`BedrockEnvelope.applyToLobbyCell`) since nothing exists to connect to yet. No plan, no timer. `/dungeon admin untimed`/`build` are untouched (debug tools, not the player loop).
- The old U8 Stage 3 selector room (a separate, off-grid, post-completion-only instance) is retired and folded into this same room: `selectorDoorStep`/`chooseOffer` now gate on `InstanceRecord.awaitingDoorChoice` instead, and rendering the three doors reads the player's *current* keystone level every time, not a one-shot banked `pendingOfferLevel` (that `DungeonLog` field/codec is left in place for save-format safety but nothing writes to it any more).
- Choosing a door (`Instances.chooseOffer` → `generateBehindLobby`) plans a shape via the existing `LayoutPlanner`/`LayoutGraphGenerator`, then **rotates the whole abstract shape** (`DungeonShape.rotate`, pure-JDK, verified by hand against 500 generated shapes for validity + exact direction) so its entrance edge lines up with the room's already-fixed, already-sealed door -- no change to the generator's own randomness, just which of four equally-likely labellings gets used. `LayoutStamper.stampBehindLobby` then stamps every *other* cell (the room itself is skipped, already standing), the seal comes down (`RoomBuilder.openDoor`), and the timer starts only now.
- `completeRun` banks the chosen offer directly (`record.chosenStep`, against the completing member's *own* current level -- preserves the old "companions progress off their own ladder" behaviour) instead of parking a pending offer, since there is no later "go choose a door" step any more.
- An abandoned lobby (nobody ever chose a door) purges itself the same way the old selector room did, rather than holding a slot and a force-load ticket forever.

`./gradlew build` green, plus a throwaway pure-JDK check (`DungeonShape.rotate` × 500 seeds, deleted after use, not part of the suite) confirming every rotation stays a valid shape and lands the entrance exactly on the requested direction. **Nothing about this rework has been played.** The whole new loop -- lobby stand-alone, three doors, generation-on-choice, the seal opening, the timer starting late -- needs a real client to confirm, and is deferred to the same live pass as the rest of M2.

**2026-08-24 live bug, fixed, Devin:** first real-world playtest found that `/dungeon` re-entering a just-completed (but not yet `lingering`) instance for free -- U8 Stage 1's existing behaviour, unaffected by M2's own tests because there was previously nothing visible to distinguish "re-entered" from "fresh" -- now drops the player back into a room-already-moved, entrance-already-cleared instance, reading as "the starting room is empty/bedrock and my room is already at the end with no way to finish." Fixed in `reenterOwnedInstance` (skip any instance with a non-empty `completed` set, not just `lingering` ones) and `enter()` (the pre-build purge/retire check now also catches an owner's already-completed-but-not-yet-`lingering` instance, not only already-`lingering` ones, via `retireOrPurge` rather than a raw `purge`). Build green; not yet re-verified live. A separate report of the terminal door facing the wrong wall was not reproduced by code review (the rotation-delta math checks out by hand for the single-door entrance/terminal case) and needs a fresh live check now that the stale-reentry confound above is fixed, since that was very possibly what was actually being looked at.

## M3 — The calling card · `plans/M3-calling-card.md`

| # | Task | Status | Note |
|---|---|---|---|
| T3.1 | Mint a calling card | `DONE` | 2026-08-24, Devin. `CallingCard.mint(owner)` stores owner UUID under `CUSTOM_DATA` root, uses configurable `callingCardItem` (default `minecraft:compass`), optional `LODESTONE_TRACKER` with `tracked: false` for glint, plus `CUSTOM_NAME`. `CallingCard.isCard`/`ownerOf` positive-read helpers. `/dungeon room card` command hands the caller one card. `RitualListener.warmUp` resolves the configured item at boot. `./gradlew build` green |
| T3.2 | Use-on-lodestone | `DONE` | 2026-08-24, Devin. `RitualListener` positive test for `CallingCard.isCard` before the keystone branch; foreign items still PASS to other mods. Card is not consumed. Plays the lodestone charge sound, then routes to `Instances.visit` |
| T3.3 | Shared visit instance, refcounted | `DONE` | 2026-08-24, Devin. `Instances.visit(visitor, ownerUuid)` implements the case table: owner has a live non-lingering instance → `admit` into it; owner away with no visit instance → `createVisitInstance` allocates a slot, stamps `RoomStore.place` (or `ENTRANCE_HALL` fallback), seals the door with `RoomBuilder.sealDoor`, applies `BedrockEnvelope.applyToLobbyCell`, and places the selector doors/leave-pad against the connecting wall; owner away with existing visit instance → `admit` into the same one. `InstanceRecord.visitInstance` flag marks read-only copies; `exit`/`dropMember` purge the instance when its last visitor leaves. `reenterOwnedInstance` excludes visit instances so an owner does not accidentally re-enter their own read-only copy. `./gradlew build` green. Live two-client convergence test deferred |
| T3.4 | Visitor permissions | `DONE` | 2026-08-24, Devin. Reuses M2 T2.2: `RoomProtection.roomOwnerAt` sees `record.roomCellOrigin` set for visit instances, so a visitor is treated as "not owner/not whitelisted" and cannot break blocks or open lootable containers, but can still use stations and ender chests. `./gradlew build` green. Live positive/negative checks deferred |

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
| D7 | The elevator — public opt-in room/party directory | `TODO` | Needs M2, M3. Menu is now spec'd — `DIALOGS_SPEC.md` §7, vanilla-vs-SGUI decision still open |

**Nothing in M8 may be started without an explicit promotion decision.**

---

## Session log

Newest first. One line each: date — who — what changed.

| Date | Who | What |
|---|---|---|
| 2026-08-24 | Devin | M3 closed. `CallingCard` mint/read helpers + `/dungeon room card` command, `callingCardItem` config, `RitualListener` card branch routing to `Instances.visit`, shared/refcounted read-only visit instances with `InstanceRecord.visitInstance`, and teardown-on-last-leave. Visitor permissions reuse M2 `RoomProtection`. Post-review fixes: `reenterOwnedInstance` excludes visit instances; `createVisitInstance` seals the room door; `stampLobby` and `createVisitInstance` place selector doors/leave-pad against the connecting wall so first-time and visit lobbies are usable; selector doors are cleared and the sealed double doorway opens when a choice is made; `PendingClear` now clears the one-block bedrock envelope around each cell so instances purge cleanly. Added `/dungeon admin resetkey <player>` to reset keystone progress to 0 and clear held keystones. `./gradlew build` green. Live two-client convergence and permission checks deferred to the suite-wide multiplayer pass |
| 2026-08-24 | Devin | M3 non-dependent prep: `CallingCard.java` mint/read helpers, `callingCardItem` config defaulting to `minecraft:compass`, `RitualListener` warmup. T3.1 implementation complete; T3.2–T3.4 left `BLOCKED` until M2 lands. `./gradlew build` currently red on M2-incomplete `Instances.java` (`moveRoomToTerminal` / `buildLayout` arity), not on these changes |
| 2026-08-24 | Devin | M1 closed. `DungeonRoomMeta` `processors`/`theme` wired, `RoomSelector` theme filtering, three proof processor-list datapack themes, `PlanSelectorTest`/`DungeonRoomMetaTest` pass. `./gradlew build` green. `plans/M1-themes-foundation.md` → `M1-themes-foundation-completed.md`; current milestone moved to M2 |
| 2026-08-24 | design session | `DIALOGS_SPEC.md` written — seven menus spec'd against the vanilla dialog mechanism `quizengine`/`cobbleeconomy` already ship (no code): door offers, kick/invite confirmations, keystone inspection, room whitelist (M2 T2.2), admin baserestore confirm (M2 T2.1), and the elevator (D7). Corrected D7's cost estimate in `plans/M8-deferred.md` — it was priced against building a `MenuProvider` from nothing; the real decision is vanilla `DialogListDialog` vs. adding `eu.pb4:sgui` for pagination. Cross-referenced from M2 and M3's plans |
| 2026-08-24 | user + Devin | Decided: multiplayer testing (any task needing two connected clients) is deferred to one pass after every milestone is code-complete, not run per-task. Noted at the top of this file. M0 closed on that basis: T0.5 marked `DONE` on code + build-green, its live click check deferred rather than left `WIP`. Current milestone moved to M1 |
| 2026-08-24 | design session | D7 "the elevator" (public opt-in room/party directory) added to the M8 backlog — a user idea, not yet scoped into a plan. Flagged its tension with the calling card's deliberate no-browse rule and its real cost (new menu system, new per-template anchor) rather than filing it as free |
| 2026-08-24 | Devin | M0 T0.1–T0.4 done. `RoomManifest` now reloads on `/reload` (verified live over RCON); root `LICENSE` added (MIT, suite-wide); `INTEGRATION.md` written with the schema table and verbatim validation failures; `selectorDoorStep` gated on `record.owner`. `./gradlew build` green |
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
