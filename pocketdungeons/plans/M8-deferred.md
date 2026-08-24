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

## D7 — The elevator (public room directory)

**The idea:** every room template carries an elevator in the same position —
two iron doors, right-click opens a selector, choose a room/party and go
there. Room and party are the same thing under the M2 hub model (a party
enters the leader's room and runs from there, per
`MYTHIC_PLUS_RECONCILIATION.md` §3.2), so the elevator is a lobby browser for
exactly that, not a new concept.

**Why it's a strong idea, not just a nice-to-have:** identical unmarked doors
in every room, never explained, is exactly the register `VISION.md` §4 wants
— it reads like the elevator in an SCP-3008-style anomalous hotel, and the mod
saying nothing about it is the same discipline that protects the closed loop.
It's also the more honest answer to a gap the calling-card design admits it
doesn't solve: cards spread by hand, which is folklore for people who already
know each other, but does nothing for a stranger with no card finding
anything. An opt-in public directory is the other half of that problem.

**It is not the same channel as the calling card, and must not be filed as
one.** `VISION.md` §3.1.1 states the card's address "cannot be searched,
listed or browsed" — deliberately, because that's what makes it word-of-mouth
rather than a phone book. The elevator *is* a phone book. Keep both: cards
stay the private, invited channel; the elevator is a second, separate,
**opt-in** public one. A room needs a new `listed: boolean` (default false)
that its owner sets themselves — nobody's private room should appear in a
stranger's directory just because a milestone shipped a directory.

**Real cost, not a compass-sized one:**

- **A selector needs a real menu.** Checked: this mod has never built a
  `MenuProvider` / `AbstractContainerMenu`. The existing "choose one of three"
  pattern (`Instances.chooseOffer`) is chat text plus
  `/dungeon choose <1|2|3>`, not a GUI. A server-authored container screen is
  still vanilla-client-safe — it doesn't touch the no-client-mod rule — but it
  is new machinery, not a reuse of anything that exists. Cheaper first pass:
  reuse the chat-list-plus-command pattern instead of a real screen, and treat
  a proper GUI as later polish rather than the first version of this feature.
- **A fixed anchor in every template is a new template-authoring constraint**,
  the same shape as the door-jigsaw requirement `RoomManifest` already
  enforces — every template, including every theme (M1) and every future one,
  now needs an elevator anchor or the room fails validation. Scope this
  alongside T1.x work, not after it, or every existing template needs a
  retrofit pass.
- **Scope the listing itself before building it:** does the directory show
  only persisted, opted-in rooms (cosmetic/social — "come see my place"), or
  also live parties currently recruiting for a run (matchmaking)? The
  framing ("room and party is effectively the same thing") suggests both
  belong in one list, which is coherent under the hub model but is genuinely
  two features — a static directory and live matchmaking have different
  staleness, different failure modes if a party fills or a room owner logs off
  mid-list, and different UI needs. Decide this explicitly before scoping a
  plan file; don't let it default to "both" by accident.

**Blocked on:** M2 (rooms must persist to have anything to list) and M3 (the
visit mechanism must exist as the thing this reuses for actually getting
there). **Not scheduled.** No promotion decision has been made; this is
recorded so the idea isn't lost, not because it's next.

---

## Promotion checklist

Before moving anything out of this file:

- [ ] The reason it was deferred no longer applies, and that is written down
- [ ] It has its own `plans/M<n>-*.md`
- [ ] `../ROADMAP.md` and `../PROGRESS.md` both updated
- [ ] Nothing currently in flight depends on the shape it is about to change
