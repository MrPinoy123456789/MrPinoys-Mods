# FURNITURE_DECISION (D3 verification record)

Date: 2026-10-29. Status: **approved by the owner 2026-10-29**. No furniture code exists; this record only
verifies the D3 proposal against `polymer-virtual-entity 0.17.5+26.2` (the resolved jar, inspected with `javap`).

## What was actually built (2026-10-29, supersedes the barrier design below)

The first build used a barrier block plus a record store and punch-to-break. The owner said furniture must behave like
any vanilla block, and that design could not (a barrier cannot be mined in survival, drops did not go through the
normal path, records had to be rebuilt by hand and deadlocked a chunk load once). It was replaced:

- Each furniture definition registers **one real block**, `FurnitureBlock` (a `SimplePolymerBlock` that also implements
  `BlockWithElementHolder`), in the block registry. It has a `FACING` property (cardinal, set from the player), the
  definition's hardness, wood sounds, and drops its placing item through the normal `getDrops` path.
- Clients see a vanilla stand-in (barrier when solid, structure void when walk-through). The visual is a display
  entity that Polymer creates from `createElementHolder` when the block is placed or its chunk loads, and removes when
  the block goes. Verified by gametest: placing attaches a holder, destroying removes it.
- Placement copies `BlockItem`: replaceable check, collision check, state for placement, placed sound, consumption.
- Persistence is ordinary chunk saving of the block state, so there is no record store, no queue and no repair command.
- Mining is driven on the server (`handleMiningOnServer` returns true) because the client sees the stand-in block.
  Whether that feels right in a client is the first thing to check by hand.
- No interaction entity: it would intercept left clicks and stop real mining. Click behaviour, when added, belongs on
  the block (`useWithoutItem`).
- Costs: one registry entry per piece, so new or changed pieces need a restart; pieces placed with the first (barrier)
  build stay as plain barrier blocks and must be removed by hand.

Everything below is the original proposal, kept for history.

## The proposal, verified

D3 proposed: display entities for visuals, an interaction entity for clicks, a barrier or light block where collision
is needed, a stable logical id in world persistence, entity UUIDs as caches only. Verified against the jar:

- **Visuals:** `ElementHolder` is a packet-only group of fake entities; `ItemDisplayElement`, `BlockDisplayElement`,
  `TextDisplayElement` and `DisplayElement` are added with `addElement` and pushed to clients with
  `startWatching(ServerPlayer)`. No real entity exists, so nothing serializes, nothing can leak into saves, and a
  vanilla client renders them as normal display entities. Verified: `ElementHolder.addElement`, `getEntityIds`,
  `sendPacket`.
- **Clicks:** `InteractionElement(VirtualElement.InteractionHandler)` with `setSize(w, h)` and `setResponse(true)`.
  The holder resolves the click with `getInteraction(entityId, player)`, so one furniture object can have several
  click zones. Verified on the jar.
- **World attachment:** three ready-made choices, all in `api.attachment`:
  `ChunkAttachment.of(holder, level, pos)` ties the holder to a chunk and is destroyed with it;
  `BlockBoundAttachment.of(holder, level, pos, state)` ties it to a blockpos plus expected blockstate and dies when
  the block changes (broken furniture removes itself), with `BlockBoundAttachment.get(level, pos)` for lookup;
  `IdentifiedUniqueEntityAttachment.of(id, holder, entity)` binds to a real entity with a registry-visible id.
- **Collision:** virtual entities have none. Solid furniture needs a real block (the D3 barrier or `minecraft:light`
  for walk-through-but-glowing); `BlockBoundAttachment` then gives block-broken cleanup for free.
- **Persistence:** none built in. Virtual entities are runtime packets; a crash or restart leaves nothing. Foundry
  must keep the logical record (`furniture id`, definition id, `BlockPos`, rotation, extra state) in a level
  `SavedData`/`PersistentStateContainer` store; entity ids and `ElementHolder`s are caches rebuilt on demand.
- **Chunk unload:** chunk-bound attachments stop existing for unloaded chunks, which is what we want; when the chunk
  loads again (Fabric `ServerChunkEvents.CHUNK_LOAD`) the store's record for that chunk respawns the holder.
- **Duplicate prevention:** two layers, both cheap: the persistent store is the single source of truth (one record
  per furniture id), and respawn code checks `BlockBoundAttachment.get(level, pos)` before creating so a double
  chunk-load event cannot stack two holders on one spot.
- **Crash/restart:** on boot the store is intact but no holders exist; holders materialize lazily per chunk load, so
  a server that restarts shows furniture as chunks are visited, at no startup cost.

## What this means for the definition format

A furniture definition would name: id, display (item or block model id + offset/scale/rotation), interaction box
size, solid or not, and optional mechanic ids to run on click. Nothing here needs a Mixin; D6 is unaffected.


## Owner decisions (2026-10-29): approved, furniture code may now be written

1. **Block-bound.** Furniture behaves like a vanilla block: placed at a block position, removed when broken, no
   free-floating placement. Use `BlockBoundAttachment` for lifecycle and cleanup.
2. **Solid by default.** Each furniture piece occupies a real block with collision. A definition may opt out
   (walk-through, light only) but the default is solid.
3. **Rotation like vanilla.** A placed piece faces one of the four cardinal directions, as most blocks do. Special
   cases (paintings, armor stands, stairs and similar) keep whatever vanilla already does; Foundry adds no new
   rotation or interaction behaviour beyond that unless the owner asks for it explicitly. No arbitrary yaw.

Consequences for the build: records keyed by block position and facing in the level `SavedData` store, one record per
block; a definition's rotation field is a cardinal facing, not an angle; any "special movement" request is out of
scope until the owner names it. Still true: no Mixins (D6), and no furniture-specific Java per piece.

## Open questions for the owner (answered above)

1. Is furniture always bound to a block position (chair, table, lamp), or should free-floating placements
   (a gem lying on the floor at any angle) be in scope? Free-floating changes the model: records keyed by id plus
   `Vec3`, `ChunkAttachment` instead of `BlockBoundAttachment`, and no block-break cleanup.
2. Solid by default or not? Each solid furniture occupies a real block; barrier collision also blocks pistons and
   water flow.
3. Rotation: fixed to the 4 block directions, or arbitrary yaw on the display transform?

## Recommendation

Build the block-bound variant first: `ItemDisplayElement` + `InteractionElement` under one `ElementHolder`, a
`BlockBoundAttachment` for lifecycle and cleanup, a barrier underneath only when the definition asks for collision,
and a per-level `SavedData` store of logical records. Free-floating furniture is a second, later type if the owner
wants it.

**Approved by the owner on 2026-10-29 (see the decisions above). Furniture code may now be written.**
