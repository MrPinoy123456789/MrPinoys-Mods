# Known issues and traps

- **Gametests that are not registered never run.** Add every new gametest class to
  `src/gametest/resources/fabric.mod.json` and check that the passed count rises.
- **Pure tests need two registrations** in `build.gradle.kts`: the task list and `tasks.test`'s `dependsOn`.
- **Screens are transient.** Display entities in a room are re-summoned on every stamp and swept on teardown and on
  `RoomStore.capture`. Anything you place must be re-placed by the same paths that re-place today's history board,
  and must never bake into a captured room. Wall blocks you change must be restored by
  `StagingFurniture.clearFurniture` (capture hygiene, trap 17 in `docs/DISCOVERIES.md`).
- **Text display rules** (`DungeonScreen` javadoc): `see_through` false, forced brightness `{block:15, sky:15}`, the
  `text` tag encoded via `ComponentSerialization.CODEC` (use `DungeonScreen.encodeText`), `billboard: fixed` with a
  yaw from `yawFor(wall)` for wall screens. Text renders at 0.025 blocks per pixel per unit of scale.
- **Mirroring.** SOUTH and WEST walls mirror `along` for the viewer (`RoomGeometry.viewerAlong`,
  `RoomGeometry.mirrorsAlong`). The terminal is not symmetric, so convert every column. Test on at least two
  different selector walls.
- **Interaction entities have a square footprint.** `width` applies to x and z. Use rows of 1.0 boxes for wide buttons.
- **`UseEntityCallback` fires per hand** and on both sides. Main hand, server side only, and a short cooldown.
- **The room owner field** is `record.owner`; members are `record.members` (keyed by UUID, present members only).
  A leader in reconnect grace is not in `record.members`.
- **Player-facing words are settled.** Use compass, scrap, haul, lives, charts, party leader. Never "keystone",
  "omen", "interval" or "node" in text a player sees.
- **No soft locks.** A `require_item` glyph must never stand between a party and finishing a floor (PD-170).
- **Owner decisions are not yours.** If the spec seems wrong, build it as written and say so in the report, or stop
  and ask; do not redesign.
