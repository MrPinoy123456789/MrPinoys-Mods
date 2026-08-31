# MrPinoy's Unstick

Quietly rescues villagers that vanilla's pathfinder has wedged onto thin blocks.

> **Status:** implemented and build-verified 2026-08-30, **not play-verified.**

- Minecraft **26.2**, Fabric Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"`: **vanilla clients install nothing**
- **Zero Mixins**, zero compile-time dependencies on any other mod

## The problem it addresses

Vanilla's mob pathfinder does not treat a long list of thin blocks as obstructions, so it routes villagers onto them and the villager then cannot move (Mojira MC-96319, open since 2017). The known culprits include flower pots, bamboo, lanterns, end rods, chains, lightning rods, pointed dripstone, sea pickles on land, cocoa beans, campfires, and the carpet-under-a-low-ceiling case (MC-97799, where a 1.95-tall villager plus a carpet no longer fits in a 2-block-high space). A villager that hits one of these bounces or stands in place indefinitely, often failing to reach its bed or workstation and eventually losing its job.

Mojang has not fixed this in years, and the "correct" behaviour is genuinely ambiguous: should a villager path around a flower pot, or step over it? Unstick does not try to answer that. It leaves routing exactly as vanilla wrote it and only acts once a villager has been stuck for long enough that vanilla is clearly not going to recover on its own.

## How it works

Every 10 ticks, Unstick scans loaded villagers. For each one that is actively navigating, it measures net horizontal displacement from the start of a rolling window. If the villager covers at least `moveThreshold` blocks (default 0.5), the window re-anchors there and the stuck counter resets; it is making progress. If it does not, the counter accrues. Once it has been navigating with no net progress for `stuckTicks` (default 100, i.e. 5 seconds), the villager is wedged:

1. Unstick computes the direction toward the villager's path target (or, if that is unavailable, the direction it is facing).
2. It tries to teleport the villager `rescueDistance` blocks (default 2.0) in that direction, but only if the landing spot is collision-clear. If the full distance is blocked, it retries at half distance. If even that is blocked, it leaves the villager where it is rather than shoving it into a wall.
3. It drops the current path so vanilla re-paths from the new position.
4. A `cooldownTicks` (default 60) window stops the villager being re-nudged before vanilla has had a chance to re-path.

States where "not moving" is correct behaviour are skipped: passengers, vehicles, sleeping, swimming, and villagers with no active navigation. This means a villager idling or waiting for a door never trips the rescue.

## What Unstick does not do

It does not change where villagers try to go. A villager still wants its bed, its workstation, or whatever vanilla's brain decided; Unstick only gets it past the block it could not step over. It does not prevent the brief wiggle that precedes a rescue (the 5-second window is deliberate, to avoid false positives on legitimate pauses), it only ends it. It does not touch the pathfinder's node evaluator, so it cannot introduce the "villager refuses to enter a decorated room" failure mode that a routing fix would risk.

## Config

`config/unstick.json`:

| key | default | meaning |
|---|---|---|
| `stuckTicks` | `100` | ticks of active navigation with no net progress before rescue (100 = 5s) |
| `moveThreshold` | `0.5` | net horizontal blocks over the window that count as progress and reset the counter |
| `rescueDistance` | `2.0` | teleport nudge toward the path target on rescue |
| `cooldownTicks` | `60` | ticks to wait after a rescue before rescuing again (60 = 3s) |

Missing file → defaults written. Fails to parse → defaults in memory, file untouched.
