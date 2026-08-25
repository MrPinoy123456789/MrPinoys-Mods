# M0 — Entry fee and safety

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` §6.1 · Status: `../PROGRESS.md`

**Goal:** a third party can write a datapack against this mod without reading its
source, and the repo states its own licence.

**Blocked on:** nothing. **Blocks:** M1 iteration speed, all of §6.

---

## T0.1 — `RoomManifest` on `/reload`

`RoomManifest`'s own javadoc admits it: *"wiring it to fire automatically on
`/reload` is a later follow-up."* Today authors must run
`/dungeon admin manifest reload` by hand, which nobody discovers.

**Where:** `RoomManifest.load(MinecraftServer)` already does the whole job and is
idempotent — it rebuilds `current` from scratch and swaps the volatile reference.
Nothing needs restructuring; it needs a caller.

**Do:** register a Fabric resource-reload listener for the **server data** pack
type and call `RoomManifest.load(server)` from it. Keep the admin command — it
stays useful for reading `rejections()` back.

**Hazards:**
- `load` early-returns with a rejection if the overworld is not yet loaded. A
  reload listener can fire before level load on startup; keep that guard and let
  the existing startup call cover the first load.
- `current` is `volatile` and swapped atomically, but a run that is *mid-stamp*
  holds an `Entry` from the old manifest. That is fine — `LayoutStamper` reads
  `RoomManifest.current()` once at the top of `stamp`.

**Test:** edit a `dungeon_room/*.json` weight, `/reload`, confirm
`/dungeon admin manifest list` reflects it without a restart.

---

## T0.2 — `LICENSE`

`fabric.mod.json` claims MIT. There is no `LICENSE` file at the repo root. Add
the MIT text with the correct copyright holder.

**Note:** this is the suite root, not `pocketdungeons/` — confirm the intended
scope covers the other mods before writing it.

---

## T0.3 — Published `dungeon_room` schema

Everything below is already parsed by `DungeonRoomMeta.fromJson`. Publishing it
is documentation, not code.

| Field | Type | Default | Notes |
|---|---|---|---|
| `template` | string | **required** | Structure template id |
| `footprint` | `[x, z]` | `[1, 1]` | ⚠ **only `[1,1]` works today** — multi-cell is M8 |
| `roles` | string[] | **required, non-empty** | `entrance`, `exit`, `encounter`, `loot`, `corridor` |
| `weight` | int | `1` | Selection weight |
| `minDepth` | int | `0` | Earliest depth this room may appear |
| `maxPerDungeon` | int | `-1` | `-1` is unlimited |
| `processors` | string | none | Processor-list id — **M1** |

Document the **validation rules** too, because they are where authors will fail:
every door must be a `pocketdungeons:door` jigsaw, on a cell edge, facing that
edge, and **complete** — every canonical slot on an edge that has any door, or
the room is rejected with `partial door on <edge> wall`. Masks match **exactly,
not as a superset**; a room with a spare door punches a hole into the void.

---

## T0.4 — `INTEGRATION.md`

Follow `kamutotems/INTEGRATION.md`'s structure and its stranger rule. Cover the
five surfaces `VISION.md` §6 already lists as working today:

- `trial_spawner/tier_N/{normal,ominous}.json` — `spawn_potentials` takes any
  entity id with arbitrary NBT
- the `tier_1..3` chest loot tables
- `payoutCommand` — **arbitrary command execution on completion**; say so plainly
- `dungeon_room/*.json` from any namespace (`listResources` scans all)
- `keystoneItem` / `vaultKeyItem` re-pointing

State what is **not** extensible yet, so nobody wastes a weekend: affixes are a
Java enum, room roles are a Java `switch`, and `ritualKeyItem` is dead config
(referenced only in a comment — the real gate is `Keystone.isKeystone`).

---

## T0.5 — Owner check on selector doors

**Read the correction in `../ROADMAP.md` M0 before starting.** This is not the
security hole it was filed as; the spend path reads the clicking player's own
`DungeonLog`.

**The actual defect:** `Instances.selectorDoorStep` (`Instances.java:905`) gates
on `record.selectorRoom` but never on `record.owner`, so a party member standing
in someone else's selector room gets a door prompt.

**Do:** add `player.getUUID().equals(record.owner)` to the guard. Two lines.

**Do not** also gate `chooseOffer` on room presence — its javadoc explains why it
is deliberately location-free, and that behaviour is load-bearing for players who
disconnect mid-prompt.

---

## Done when

- [ ] A `dungeon_room` edit takes effect on `/reload` with no restart
- [ ] `LICENSE` exists at the repo root and matches `fabric.mod.json`
- [ ] `INTEGRATION.md` documents all five surfaces and names the three that are
      not extensible
- [ ] The schema table is published and lists the validation failures verbatim
- [ ] A guest in a host's selector room gets no prompt — **note (2026-08-24):**
      verified by code (the guard now checks `record.owner`) and covered by
      `./gradlew build`; the live two-client click check itself is deferred to
      the suite-wide multiplayer pass recorded in `../PROGRESS.md`, not run
      per-milestone
- [ ] `./gradlew build` green
