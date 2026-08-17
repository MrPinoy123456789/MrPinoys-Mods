# MrPinoy's Rehome

Villagers follow you home and move into the house you built for them.

> **Status:** implemented and build-verified 2026-08-16, **not play-verified.**
> See [SPEC.md](SPEC.md) for the full design.

- Minecraft **26.2**, Fabric Loader 0.19.3+, Fabric API 0.156.0+26.2, **JDK 25**
- `"environment": "server"` — **vanilla clients install nothing**
- **Zero Mixins**, zero compile-time dependencies on any other mod

## How it works

1. **Gift them.** Right-click an adult villager with bread, an emerald, or something profession-flavoured (wheat for a farmer, paper for a librarian, a poppy for anyone). They don't always take it — about 60% of the time — but the item's spent either way. If they do, you'll see hearts and hear the villager's "yes" sound.
2. **Walk home.** They follow like a tamed animal — close by they idle, farther out they path toward you, and if you get too far or change dimension, they teleport in. They'll go through portals with you.
3. **Say stay.** Stand next to a bed and right-click the villager with an emerald. They stop following, vanilla claims the nearest free bed on its own, and Rehome just reports what happened — hearts and "made itself at home," or "doesn't see anywhere to sleep here" if there's no bed in range.
4. **Change your mind.** Right-click with an emerald again to pick them back up and walk them somewhere else.

A normal right-click, with no gift and no emerald, always opens trading — Rehome never gets in the way of that.

## What Rehome doesn't do

No happiness, no settlements, no prices, no dialogs, no deeds. The villager is never re-created — trades, levels, and gossip are whatever vanilla would have given them anyway, because Rehome never touches that system. There's no room requirement either: if vanilla is willing to let them claim a bed, Rehome is happy.

## The one warning

A villager sleeping under a slab or trapdoor can suffocate — vanilla counts those as blocking, even though they don't look it. Rehome checks the two blocks above a newly claimed bed and warns you if it's a problem, with smoke and a chat line. It's a warning, not a refusal.

## A few cottages accidentally make a village

Any subchunk with a claimed bed, bell, or job site counts toward a vanilla village. A handful of houses won't spawn iron golems (that takes 10 villagers and 20 beds), but it *will* stop patrols from spawning near your base, start spawning cats, and — if you walk in carrying Bad Omen — make your cosy hamlet a valid raid target. Not a bug. Just something worth knowing before it happens to you.

## Config

`config/rehome.json` — gift items and which professions accept them, gift acceptance chance, follow distances, and how long to watch for a bed claim after "stay." See [SPEC.md §10](SPEC.md#10-config) for the defaults.
