# M8 — Deferred

> Roadmap: `../ROADMAP.md` · Status: `../PROGRESS.md`

**These are held deliberately.** Each is a milestone wearing a feature's clothes,
and each has a specific reason it is not next. This file exists so nobody
re-discovers the reasons the expensive way.

**Do not start any of these without an explicit decision to promote it.**

---

## D1 — Outdoor themes (needs a second dimension)

**Why it is held:** the dimension is `has_skylight: false`, `ambient_light: 0.0`,
`effects: minecraft:the_end`, `fixed_time: 6000`. A village at sunset needs real
sky, and **sky is a per-dimension property** — it cannot vary per room.

Supporting both means a **second dimension**, and `Instances` is built around one
level with one slot grid (`bySlot`, `allocateSlot`, `originForSlot`).

**Why it is worth doing eventually:** the village-street-then-the-doors-close
moment is the best beat available to this design. Village street, forest path,
ruined camp, flooded town.

**Do not bundle it with the cheap themes.** Biome and Realm families (lush cave,
dripstone, frozen, jungle, badlands, swamp, cherry, pale garden, Nether, End,
Deep Dark) are processors plus `spawn_potentials` and belong in M1/M6. Outdoor is
not a theme; it is a milestone.

---

## D2 — One rule-breaking dungeon

The Endless Mine, the Inversion, the Labyrinth, the Descent.

**These are not data.** Forward-only traversal, the terminal exit and
`RoomSelector.validate`'s reachability guarantee are `LayoutGraphGenerator`
invariants. Each special ruleset is a generator variant with its own failure
modes.

**Pick exactly one to prove the pattern: the Endless Mine.** "Do not place a
terminal, keep extending" bends the invariants least. **Hold the rest until it
ships.**

⚠ No terminal means no closed loop (M2) and no way home by the normal route.
Design the exit before the generator.

---

## D3 — Data-driven affixes

Affixes are a Java enum and room roles are a Java `switch`. A pack author cannot
add either without compiling. This is **the real investment** in `VISION.md`
§6.1.

**Do it after five or six affixes exist in Java and the varying knobs are
known.** Data-driving a system with three examples produces a schema shaped like
those three examples.

---

## D4 — Multi-cell footprints

`DungeonRoomMeta` parses `footprint`, but `LayoutGraphGenerator` is **1×1 only**
(`maxGridSpan: 12`, self-avoiding walk, 5–8 rooms, 0–2 spurs). The field is
accepted and ignored for anything else.

Document `[1,1]` as the only supported value (M0 does this) so nobody authors
against a promise the generator does not keep.

---

## D5 — Room size as progression, and station unlocks

Both are downstream of M2 (the room must exist and persist) and M6 (stations must
be the economy before unlocking them means anything).

Station unlocks are the more interesting half: §3.7.4 makes them the *only* route
to chains a sealed world cannot reach, which is what stops them feeling
arbitrary.

---

## D6 — Lava as a second faucet

Molten is currently the only source (M4). If that proves too narrow, widen it —
but **only after M6 ships and shows whether it actually pinches.** Adding a
second faucet pre-emptively removes the reason anyone opts into Molten.

---

## Promotion checklist

Before moving anything out of this file:

- [ ] The reason it was deferred no longer applies, and that is written down
- [ ] It has its own `plans/M<n>-*.md`
- [ ] `../ROADMAP.md` and `../PROGRESS.md` both updated
- [ ] Nothing currently in flight depends on the shape it is about to change
