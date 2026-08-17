# Rehome — Design Spec v0.4

Server-side-only Fabric mod. Minecraft 1.21.x (built against this suite's 26.2 branch).

Villagers follow you home and move into the house you built for them.

Premise: you build a little house. You find a villager out in the world, offer them something, and they follow you home. You stand by the bed and tell them to stay. They move in and live there.

The mod does one thing: temporarily override a villager's behaviour so they'll walk with you, then hand them back to vanilla in the right place.

Identity strings: mod id `rehome`, package `rehome` (flat, matching the suite's convention — every other mod uses its bare mod id as the root package rather than a reverse-domain prefix), config `config/rehome.json`.

## 1. Design history

Four earlier drafts are worth summarising, because the deletions are the design.

- v0.1 — Terraria housing. Claim a bed by command, validate a room, teleport villagers in.
- v0.2 — Necesse-style. Paid recruitment via vanilla dialogs, villagers carried home as deed items backed by server-side custody records.
- v0.3 — the wolf realisation. Deeds deleted; villagers simply follow you, like a tamed animal.
- v0.4 — vanilla's own rules. Custom room requirements deleted; the mod stopped having opinions about architecture.

What survived: gift, follow, release. Everything else was solving problems the mod had invented for itself.

## 2. Scope

In: befriending, following, waiting, release-into-vanilla, move-in confirmation, one safety warning.

Out: room requirements, happiness, settlements, prices, dialogs, deeds, persistence layers, admin commands, and any change to trading. The villager entity is never serialized, replaced, or respawned — trades, levels, discounts, and gossip are untouched because they're never handled.

The mod never writes brain memory except to erase HOME on release. It clears memory once on release, then reads. That eliminates the iron-farm and breeding risk entirely.

## 3. Server-side-only

Vanilla clients join and see nothing broken. No registered blocks, items, entities, screens, or channels.

| Need | Approach |
|---|---|
| "They like you now" | `minecraft:heart` particles + `entity.villager.yes` — taming's existing vocabulary. |
| "They're following you" | Nothing. The behaviour is the feedback. |
| "They've settled in" | Hearts at the bed + `entity.villager.celebrate` + a chat line. |
| Naming them | Vanilla name tags already work. Nothing to build. |
| Config | `config/rehome.json`. |

`fabric.mod.json` → `"environment": "server"`. Fabric API server-side only; clients need nothing.

## 4. Befriending

`UseEntityCallback` on an adult `Villager`.

```
if holding a gift item AND villager has no follower:
    cancel interaction
    consume one item
    befriend
else:
    fall through to vanilla   // trading is never obstructed
```

A normal right-click always trades. Wandering traders and babies excluded.

Gifts. A small config list, profession-flavoured where it's obvious — bread or an emerald for anyone, wheat for a farmer, paper for a librarian, a poppy for anyone at all. Not a price; a gesture.

Not every gift lands. `giftAcceptChance` (default 0.6) gives you the "hrmm" and a puff of smoke sometimes, and the item is still consumed. Cheap to build, and it makes acceptance feel earned.

On success: hearts, `entity.villager.yes`, they turn to face you, and `setPersistenceRequired()`.

State: an entity attachment on the villager — `follower: UUID`. Persists and unloads with the entity. No world save data, no registry, no index.

## 5. Following

Wolf rules, deliberately.

| Distance from follower | Behaviour |
|---|---|
| ≤ 6 | idle, wander a little |
| 6–24 | path toward the player |
| > 24, or player changed dimension | teleport to a safe block near the player |

Portals: teleport through with the player, like a leashed animal. This is what removes the boat.

They can die on the way home. Keep it. Zombies will target them, and that's correct — it's the source of every good story this mod will produce.

### 5.1 Suppressing vanilla home-seeking

While `follower` is set, the villager must not move itself in. Vanilla bed-claiming is automatic and constant, so a villager walking past your bedroom will claim your bed, or the empty cottage you were saving for them.

This does not need a tick loop. It needs one call at release (§6): clear `MemoryModuleType.HOME`, release the POI ticket, and let vanilla re-acquire from where they're now standing. Self-correcting — if they grabbed something en route, "stay" un-grabs it.

If playtesting shows mid-journey claiming causes visible weirdness (detours to distant beds, anger particles), the fallback is to clear HOME on the interval the follow behaviour already runs. Start without it.

## 6. Release — "stay"

Right-click with an emerald. This is the whole move-in mechanic. (Resolved from the original sneak-right-click-empty-hand proposal in §14 — sneaking while walking a follower around was too easy to trigger by accident; holding an emerald is deliberate.)

1. Clear `follower`'s waiting flag from false to true (the villager stops pathing).
2. Clear `MemoryModuleType.HOME` and release any POI ticket.
3. Hand back to vanilla. It searches its 48-block sphere for the nearest unclaimed, pathable bed — which is the one you're deliberately standing next to.
4. Poll the villager's HOME memory for `claimWatchTicks` (default 200).

| Outcome | Response |
|---|---|
| HOME appears | Hearts at the bed, `entity.villager.celebrate`, "Gerald has made himself at home." |
| Nothing after 10s | "Gerald doesn't see anywhere to sleep here." |

The mod reads, never writes (beyond the HOME erase above). This is the room check the earlier drafts tried to build, except vanilla runs it and Rehome only reports the result. Read-only means no interference with iron farms, breeding, or POI accounting.

Right-click with an emerald again to resume following. This is also how you move someone to a different house — pick them up, walk them over, put them down.

Bed-claiming produces no vanilla feedback of its own (job sites emit green particles; beds don't), which is the entire reason for the poll-and-report.

### 6.1 Ambiguity

Vanilla takes the nearest reachable unclaimed bed by path distance. Standing next to the one you want is right almost always, but two adjacent empty cottages give no guarantee.

Accept this. "He moved into the other cottage" is a funny outcome, not a broken one, and the fix — walk him over, say stay again — is the mechanic itself.

## 7. The one warning

A villager that sleeps in an obstructed bed suffocates and dies. It's the only vanilla failure severe enough to justify speaking up, and it's easy to hit: slabs and trapdoors count as full blocks for this, which is exactly what someone uses to build a nice low-ceilinged bedroom.

After a successful claim, check the two blocks above the bed. If obstructed:

> "Gerald eyes the ceiling warily. There's not much room to stretch out."

Smoke particles on the offending block. A warning, not a refusal — the player can ignore it, and some will, and then Gerald dies, and that's Minecraft.

## 8. Emergent consequence, worth documenting

Any subchunk containing a claimed bed, bell, or job site counts as a village center, so a few cottages turn a player's base into a mechanical village. Consequences: patrols stop spawning nearby, cats spawn, and raids can target the base if the player walks in with Bad Omen. Iron golems need 20 beds and 10 villagers, so a handful of cottages won't summon them — but a long-running save will grow into one.

This isn't a bug and shouldn't be prevented. It belongs in the README (see [README.md](README.md)), because a player whose cosy hamlet gets raided deserves to have seen it coming.

## 9. Edge cases

| Case | Handling |
|---|---|
| Bed broken after move-in | Vanilla. They lose the POI and find another bed or wander. Mod is not involved. |
| Villager dies | Nothing to clean up. |
| Zombified / cured | Vanilla. Cured villagers keep their bed if it's still theirs. |
| Follower logs off | Villager stands where it is, keeps the attachment. Resumes on login if loaded. |
| Two players gift the same villager | First wins; second gets the "hrmm". |
| Follows into the Nether | Allowed. They'll find no bed; releasing there just reports no home. |
| Befriending in a raided village | Allowed. Reputation isn't consulted. |
| Villager claims a bed mid-journey | Cleared at release. See §5.1. |

## 10. Config

```json
{
  "gifts": {
    "minecraft:bread": ["*"],
    "minecraft:emerald": ["*"],
    "minecraft:poppy": ["*"],
    "minecraft:wheat": ["farmer"],
    "minecraft:paper": ["librarian"]
  },
  "giftAcceptChance": 0.6,
  "followStartDistance": 6,
  "followTeleportDistance": 24,
  "claimWatchTicks": 200
}
```

No `maxFollowers` — resolved in §14: unlimited, same as wolves. A chaotic procession is the player's own doing, and that's funny, not a bug.

## 11. Package layout

```
rehome
├── RehomeMod.java             // entrypoint
├── RehomeConfig.java
├── FollowerAttachment.java    // the attachment types + accessors: follower UUID, waiting flag
├── GiftHandler.java           // UseEntityCallback: gift + emerald stay/resume toggle
├── FollowBehavior.java        // tick-driven follow, wolf rules
├── ClaimWatcher.java          // release, then poll HOME + headroom check
└── Feedback.java              // sounds, particles, chat lines
```

Flat package, matching the rest of the suite's convention (`spiritwolves`, `tpasserver`) rather than the `befriend/follow/settle/util` subpackages sketched in early drafts of this spec — seven files don't need subdirectories.

## 12. Build order

1. Gift → hearts → attachment. An afternoon, and it's the entire emotional premise. Build it first because it's the thing you show someone.
2. Following. The bulk of the real work. Expect to fight the villager brain; a ticked fallback is a legitimate answer if the goal approach gets ugly. (Built as a ticked fallback from the start — matches the rest of this suite's convention of driving entity behaviour from `ServerTickEvents` rather than custom `Goal` subclasses.)
3. Stay / release + claim watcher. Then watch a full day cycle and confirm they sleep in the right bed.
4. Headroom warning, name tags, polish.

Steps 1 and 2 are the mod. If following feels good, the rest is an evening.

## 13. Test checklist

- [ ] Bare right-click on a villager opens trades, unchanged.
- [ ] Right-click with bread befriends; hearts; item consumed.
- [ ] Villager keeps up over 500 blocks of varied terrain without getting stuck.
- [ ] Follows through a Nether portal.
- [ ] Walk a follower directly past your own occupied bed and an empty bed — confirm what they claim, and confirm "stay" clears it.
- [ ] Say "stay" (right-click with an emerald) next to a bed in a 4×4 cabin → HOME appears → confirmation message → they sleep in it that night. This is the acceptance test for the whole mod.
- [ ] Say "stay" in an open field with no bed → "nowhere to sleep" message, no crash.
- [ ] Slab placed directly above the bed → warning fires.
- [ ] Two adjacent cottages → document what actually happens; adjust §6.1 if it's worse than expected.
- [ ] Iron farm mechanics function normally nearby.
- [ ] Name tag renames them; the name appears in mod messages.

## 14. Open questions — resolved

- **Should "stay" be sneak-right-click empty-handed?** No. Right-clicking with an emerald toggles the villager's state instead — deliberate (you have to be holding the right thing) without colliding with anything vanilla does with villagers.
- **`maxFollowers`?** No cap, same as wolves. If a procession of eight villagers is chaotic, that's funny and it's the player's fault.
- **Should gifts be profession-specific at all?** Profession-based, with bread and emeralds as the universal fallback (including for villagers with no profession — nitwits, unemployed villagers).
