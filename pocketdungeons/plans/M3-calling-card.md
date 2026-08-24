# M3 — The calling card

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` §2.1, §3.1.1 · Status: `../PROGRESS.md`

**Goal:** other people can stand in your room.

**Blocked on:** M2, completely. **Blocks:** nothing — but it is the reason the
rest exists. **Blocks D7 (the elevator, `plans/M8-deferred.md`)** if that
ever gets promoted: `../DIALOGS_SPEC.md` §7 requires the elevator to call
whatever single visit/join method this milestone builds, rather than
inventing a second one — build that method so it has exactly one caller
today and can gain a second later without changing shape.

> Skyblock was a challenge map for a year and became a *mode* the moment it went
> multiplayer. Hypixel's version won by adding a public layer beside the private
> one. This milestone is that step. It is small only because M2 was not.

---

## T3.1 — Mint a calling card

**The keystone is a recovery compass. A calling card is a plain compass.**

Mirror `Keystone.mint` exactly — `CustomData.update(DataComponents.CUSTOM_DATA,
stack, tag -> tag.put(ROOT, ...))` under the mod's root tag, plus `CUSTOM_NAME`.

⚠ **Store an owner UUID, not a position.** A room is a blob stamped into whatever
slot is free, so a `GlobalPos` is stale the moment the room moves.

**Optional cosmetic:** attach `DataComponents.LODESTONE_TRACKER` with
`tracked: false` for the glint and the vanilla item name. Verified present in
26.2 as `LodestoneTracker(Optional<GlobalPos>, boolean)`. It is decoration; the
address is the custom data.

**Minting:** the owner mints their own, from their own room. Cards are given away
by hand — there is no directory, no list, no browse. A room's audience is exactly
the set of people its owner handed a compass to, which is the folklore dynamic
`VISION.md` §5.4 wants and it arrives free.

---

## T3.2 — Using a card

**On the lodestone**, the block the mod already owns for the ritual. The three
doors stay runs — the §1 hook is not up for renegotiation.

`RitualListener.onUseBlock` already: gates to `MAIN_HAND`, checks
`Blocks.LODESTONE`, honours `isShiftKeyDown()` as the escape hatch, and does a
**positive** test (`Keystone.isKeystone`) so a foreign item PASSes cleanly to
other mods' handlers. Add the calling-card branch with the same discipline —
positive test, PASS on anything else. A kamutotems sigil must still fall through
untouched.

---

## T3.3 — One shared visit instance per owner

| Situation | Behaviour |
|---|---|
| Owner is home | Route the visitor to the **owner's live room** |
| Owner is away, nobody visiting | Stamp the blob into a free slot, refcount = 1 |
| Owner is away, someone visiting | Join the existing visit instance, refcount++ |
| Last visitor leaves | Release the slot and the force-load tickets |

Two people holding cards to the same room must end up **in the same room**. That
is the entire point; two private copies is the failure mode.

Reuses `allocateSlot` (linear scan from 0 over the `usedSlots` TreeSet),
force-loading, and the eager-teardown path exactly as they already work.

⚠ **Write-back is the sharp edge.** If the owner is away and a visit instance is
live, the room in the world is a *copy*. Decide and enforce: **the visit instance
is read-only and never writes back.** Only the owner's own occupancy persists
changes. Otherwise two visitors and an owner logging in concurrently is a
last-writer-wins race over somebody's house.

---

## T3.4 — Visitor permissions

Nothing new. A visitor is "not on the whitelist" and M2's mask already covers it:
no breaking, no containers, stations open, ender chest open.

---

## Done when

- [ ] Two players holding cards to the same room stand in it **together**
- [ ] Neither can break a block or open the owner's chest
- [ ] Both can use the stations and the ender chest
- [ ] A card handed to a third player works without the owner online
- [ ] Visiting while the owner is home puts the visitor in the owner's live room,
      not a copy
- [ ] Nothing a visitor does survives their visit
- [ ] A non-card item used on a lodestone still reaches other mods' handlers
- [ ] `./gradlew build` green
