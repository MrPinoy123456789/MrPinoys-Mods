# M2 — The room

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` §3.1, §4 ·
> Detail: `../MYTHIC_PLUS_RECONCILIATION.md` §3.2–3.2.4 · Status: `../PROGRESS.md`

**Goal:** the selector room becomes a persistent, owned, decoratable room, and the
run ends by walking into it.

**Blocked on:** nothing hard. **Blocks:** M3 entirely.

**This is the milestone with real architectural risk.** Everything else in the
roadmap is additive; this one changes the lifecycle.

**This is also the first milestone that stores player-authored content.**
Every save format up to now holds only *run state* -- losing it is forgivable,
a player just re-keys. A room blob is different: it is hours of someone's
decoration, and losing it is not the same class of failure. Backup-on-write and
an `admin baserestore` command are **not optional** here
(`../MYTHIC_PLUS_RECONCILIATION.md` §3.2). Build both alongside T2.1, not as
a follow-up -- there is no acceptable window where a room blob write can fail
silently.

**This is also the milestone that caps the product.** The room is locked to a
single 1×1 cell footprint by construction: `RoomManifest` validates door
jigsaw layout and footprint, so a decorated room that has drifted from its
shell, its door anchors, or its 1×1 footprint **fails the stamp**. There is
no room-size progression in this milestone or on this roadmap until D4
(multi-cell footprints, `../plans/M8-deferred.md`) is promoted out of M8 --
and D4 itself needs `LayoutGraphGenerator` to stop being 1×1-only, which is
real work with no owner yet. If "the room is the product" is the thesis, its
growth ceiling is fixed at whatever one cell holds for as long as D4 stays
deferred. That is a deliberate cost-control decision for M2, not an oversight
-- but ship a default template whose shell and anchors are pre-placed and
immutable, decorate inward only, so players are decorating *within* a known
cap rather than discovering it by hitting a wall.

---

## The order is not negotiable

```
capture  →  persist  →  clear  →  stamp
```

Capture the room from the entrance cell, write the blob to disk, *then* clear the
cell, *then* stamp it at the terminal cell. Any other order can lose a player's
room to a crash between steps. Write it as one method with no early returns
between capture and persist.

---

## T2.1 — Persist the room as a blob

**Verified in the 26.2 jar:** `StructureTemplate` has `fillFromWorld`, `save`,
`load` and `placeInWorld`.

- Key by **owner UUID**.
- Store outside the world — the room is *not* resident. A `SavedData` alongside
  `DungeonLog` is the natural home, or a per-owner file if the blob is large.
- `DungeonLog` is already the `SavedData` authority keyed by UUID; follow its
  pattern rather than inventing a second one.

**Capture bounds:** the cell interior only — `16 × (CEILING_Y + 1) × 16` anchored
at `cellOrigin`, exactly `TemplateStamper.TEMPLATE_SIZE`. Do **not** capture the
bedrock envelope; it is regenerated (T2.3).

**`fillFromWorld` takes entities.** Decide deliberately: item frames and armour
stands are decoration players will expect to survive; a wandering mob is not.
Capture entities, then filter to a whitelist on load.

---

## T2.2 — Permission mask

| Actor | Break / place | Containers | Stations | Ender chest |
|---|---|---|---|---|
| Owner | ✅ | ✅ | ✅ | ✅ |
| Whitelisted | ✅ | ✅ | ✅ | ✅ |
| Anyone else | ❌ | ❌ | ✅ | ✅ |

⚠ **There is no `PlayerBlockBreakEvents` registration anywhere in this mod
today** — the dungeon is fully breakable by design, and that must stay true for
the quarry (T2.5). So the mask has to be **positional**, not global: deny only
inside a room cell whose owner is not the actor.

Register `PlayerBlockBreakEvents.BEFORE` and a use-block handler, both
early-returning `true`/`PASS` when the position is outside any room cell. The
common case must cost one bounds check.

---

## T2.3 — Bedrock envelope

Sub-floor and over-ceiling **always**; the outer wall ring **only on faces with
no adjacent cell**. Cells are chunk-aligned (`slotPitch` is validated as a
multiple of 16, so a cell is a chunk), which makes "is there a neighbour" a
lookup in the plan, not a world read.

This is what stops a mine being a hole, and it is what makes the quarry safe to
leave standing.

---

## T2.4 — The closed loop

On completion: capture from the entrance cell, persist, clear it, re-stamp at the
**terminal** cell behind a **closed** door. The player opens it and walks in.

- Reuse `PendingClear` — the budgeted tick-spread clear
  (`advance(level, budget)` / `done()`) at `clearBlocksPerTick: 8192`.
- The stamp must complete before the door is openable. Gate on `PendingClear.done()`.

**Protect the silence (`VISION.md` §4):** no message, no sound, no title, no
particle. The single acknowledgement is an advancement on first completion.
Anyone who adds a "Your room has moved!" toast has destroyed the best idea in
the design.

---

## T2.5 — Door-as-entrance, and the quarry

The finished dungeon **lingers**: release the force-load tickets per cell but
keep the blocks. Players can walk back and mine it.

⚠ Instances are force-loaded (`setChunkForced`), which is why teardown is
currently eager. Lingering means the eager path needs a third state between
"live" and "purged". Model it explicitly rather than by omission, or the
slot-pressure logic will collide with it.

---

## T2.6 — Purge on leadership change

Decided in `../MYTHIC_PLUS_RECONCILIATION.md` §7.2. Stricter than today's
purge-when-empty. `InstanceRecord.owner` exists (`InstanceRecord.java:34`) and
`purge()` (`Instances.java:1462`) already does the whole job.

Nobody loses items — exit keeps inventory and there is no death — so a purge
costs the party their run, not their stuff.

---

## Done when

- [ ] A player decorates their room, runs a dungeon, opens the last door, and
      walks into that room with the decorations intact
- [ ] Walking back to the entrance cell finds it **empty**
- [ ] The finished dungeon is still standing and still mineable
- [ ] A non-whitelisted player cannot break a block or open a chest in the room,
      but can use a station and the ender chest
- [ ] Nothing was said to the player about any of it
- [ ] A crash injected between capture and stamp loses no room
- [ ] Every write to a room blob is backed up first, and `admin baserestore`
      recovers a room from that backup on demand -- test this by actually
      corrupting or deleting a live blob and running the recovery, not by
      reading the code and assuming it works
- [ ] `./gradlew build` green
