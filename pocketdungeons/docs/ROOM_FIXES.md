# Room Fixes

Rooms that were audited against live in-game behavior and fixed. Each entry
records what was wrong, what changed, and when.

## Gallery (MechanismSpecs.java)

**Symptom:** The room had an iron door, chest gate, hopper column, and hopper
run. Arrows stick to target blocks in vanilla and never reach the hoppers, so
the chest never fills and the door never opens from shooting. The player can
walk up to the targets and recover arrows by hand (no lava), making the entire
hopper/chest/door mechanism pointless.

**Fix:** Removed the iron door, chest gate, hoppers, and hopper run. The room
is now an open shooting gallery: three targets with lamps for feedback, two
dirt blocks for `provides: blocks`. Changed `access` from `gated` to `open`
in `gallery.json`. Removed `gallery` from the `ITEM_ANY` lock registration.

**Date:** 2026-09-04

## Collapsing Bridge (PressureSpecs.java)

**Symptom:** The room had sticky pistons, observers, and repeaters, but the
circuit had no power source and observers cannot detect players (they detect
block state changes, not entities). The pistons never moved. The pistons and
planks were also too close together, with no gap when collapsed.

**Fix:** Replaced the broken redstone with a code-driven handler
(`CollapsingBridgeHandler.java`) that checks player position each tick and
retracts/re-extends piston segments on a timer. Redesigned the layout to
`PWSSWP` per segment with a two-block gap when collapsed. Added pillars from
floor to ceiling around each piston to prevent the player from standing on
pistons or retracted planks.

**Date:** 2026-09-04

## Rising Lava (PressureSpecs.java)

**Symptom:** The room had 4 dispensers in the south wall loaded with lava
buckets, a hopper clock, comparator, and redstone dust. The redstone stopped
at z=6, 9 blocks short of the dispensers at z=15, so the dispensers never
received power. The dispensers also faced into z=14, behind the kerb at z=9,
so lava would never reach the channel at z=7..8 even if they fired. The hopper
clock was loaded with 160 items (32 per slot), giving a cycle of over 60
seconds. The player could walk through with no hazard at all. A second attempt
moved the dispensers into the kerb and ran redstone along the kerb top, but
the comparator could not read the hopper (wrong orientation) and the lava
coverage was incomplete.

**Fix:** Replaced the entire redstone mechanism with a code-driven handler
(`RisingLavaHandler.java`). The room is now a trash-compactor corridor: a
deepslate trench floor with a lever at the exit. While players are present,
lava spreads inward from both side walls toward the center on a 1-second
interval (7 seconds to full coverage). Pulling the lever drains all lava and
marks the room solved. When no players remain, the lava recedes from the
center outward on a 0.5-second interval until the room is dry. Lava is placed
as static source blocks at y=1 with no neighbor updates, so it does not flow
or leak through doorways. Removed all dispensers, hoppers, comparators,
repeaters, redstone wire, kerbs, and the dry margin.

**Date:** 2026-09-04
