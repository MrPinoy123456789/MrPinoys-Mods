# MrPinoy's Short Villagers

Shrinks villager collision height just enough that they can walk through carpeted doorways and under 2-block-high ceilings without getting stuck.

> **Status:** implemented and build-verified 2026-08-30, **not play-verified.**

- Minecraft **26.2**, Fabric Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"`: **vanilla clients install nothing**
- One Mixin, zero compile-time dependencies on any other mod

## The problem it addresses

Vanilla villagers are 1.95 blocks tall. A carpet adds 0.0625 to their feet, so villager plus carpet is 2.0125, which does not fit in a 2-block-high space. But the pathfinder's vertical clearance check is a hardcoded "is there 2 blocks of open space" test, not a check against the entity's actual height, so it routes the villager through carpeted 2-high doorways anyway. The villager tries to execute the path, its hitbox does not fit, and it bounces at the edge forever (Mojira MC-97799, open since 2016).

This is the one class of villager-stuck bug that a watchdog cannot rescue, because the obstruction is real. The hitbox genuinely does not fit. The only fix is to shrink the hitbox.

## How it works

A single mixin into `Entity.getDimensions(Pose)` intercepts the return value when the entity is a villager and replaces the `EntityDimensions` with one whose height is `targetHeight` (default 1.9, down from vanilla 1.95). Eye height is scaled proportionally so the villager's gaze point stays at the same relative position. Attachments are recomputed for the new height. The render model is untouched; only the collision and pathfinding hitbox changes.

At 1.9 tall, villager plus carpet is 1.9625, comfortably under 2.0. The pathfinder was already routing through 2-high spaces; now the villager can actually execute those routes.

## What this mod does not do

It does not fix the rest of the MC-96319 family. Villagers still get stuck on flower pots, lanterns, bamboo, trapdoor corners, slab staircases, and all the other thin blocks the pathfinder routes onto but the villager cannot stand on. Those are pathfinder logic bugs, not hitbox height bugs, and shrinking the villager does not help. For those, see [Unstick](../unstick/README.md), a companion watchdog mod that nudges villagers past thin-block obstructions.

## Config

`config/shortvillagers.json`:

| key | default | meaning |
|---|---|---|
| `targetHeight` | `1.9` | villager collision height in blocks. Must be under ~1.9375 to clear carpet under a 2-high ceiling. 1.8 (the pre-1.9 vanilla value) gives maximum margin. |

Missing file -> defaults written. Fails to parse -> defaults in memory, file untouched.

## Trade-offs

- **Smaller click and projectile target.** At 1.9 vs 1.95 the difference is barely noticeable. At 1.8 it is more pronounced.
- **Suffocation checks use eye height.** A shorter villager's eye is lower, making it slightly less likely to suffocate under slabs or trapdoors above a bed. This is a benefit, not a cost, and makes Rehome's headroom warning slightly less critical (though it should still be kept for safety).
- **Other mods that assume vanilla villager height** could behave slightly off. This is rare in practice.
- **This is a mixin mod.** It cannot share Rehome's or Unstick's "zero mixins" claim. That is fine for a separate companion mod.
