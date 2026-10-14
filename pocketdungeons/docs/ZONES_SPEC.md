# Zones spec: Endless Mine and Woodland Mansion

Zones are places that play by different floor rules while sharing the core loop (Astrolabe Room, doors, floors, haul, lives, banking). The per-theme rules hook is `ZoneRules` (`docs/INTEGRATION.md` section 1.6, schema `docs/schema/dungeon_theme.schema.json`).

## 1. Zone principles

1. **Vanilla only, server-side only.** Vanilla blocks, mobs, items, sounds, boss bars and text displays.
2. **Scarcity holds.** Blocks, tools and durability remain finite. A zone may change what is scarce, never make things free.
3. **One lifecycle.** Zones customize floors, rules and rewards through `ZoneRules`, `EndlessMineRules`, `MineGuardians` and `FloorObjectives`, never by forking `RunLifecycle`.
4. **Solvability.** Every floor's spine is solvable without kit tools; kits and mined blocks open bonus spurs and faster routes.

## 2. Endless Mine (built)

See `docs/DECISIONS.md` ("2026-10-11: Endless Mine, one per act") for the full numbers and rules:

- **One mine per act** (`kind: "endless"`, `hall: "special"`): Act 1 `endless_mine` (opens at compass 3), Act 2 `endless_mine_deep` (the Deep Mine, opens at compass 6 in Act 2). Later acts add a mine purely in data (`dungeon/*.json`, `dungeon_theme/*.json`, `dungeon_adventure/*.json`, `loot_table/endless_mine/act<N>_a/b/c.json`).
- **Guardian every 3rd floor (3, 6, 9, ...):** The terminal cell holds a guardian (`MineGuardians`) that gates the exit pad. Act 1 rotates the Princess (silverfish + ripening bricks), the Packmaster (pillager + angry wolves), and the Coffins; Act 2 rotates the Deep Sentry (sculk Heard meter + whelp), the Foreman (sweeping furnace grates + water vat), and the Frostbite (cold + campfires). Past floor 9 the three repeat with rising hardness (`1 + 0.25 (n - 1) + 0.5 lap`).
- **Loot tiers A, B, C:** Guardian 1 pays tier A, guardian 2 pays tier B, guardian 3 and every guardian after pays tier C into the reward barrel. Ordinary floors between guardians pay the standard tier table.
- **Act gate:** Clearing floor 3 (the first guardian) of an act's mine counts toward unlocking the next act (`ActProgress`, `MineDepth`).
- **Hidden ore:** Walls hide breakable ore pockets (`hiddenOre` with `deepBlocks` and `deepEvery`), getting richer with depth.

## 3. Woodland Mansion (unbuilt design)

A siege scenario on a fixed wing topology with randomized room fill:

- **Structure:** Grand hall hub + three wings (West, North, East), each ending in a wing lord chamber, plus a Throne room sealed by three seals (`Locks` of kind `ITEM_KEY`).
- **Wing lords:** The Summoner (evoker, vex waves and fang lines), The Champion (vindicator, shield-breaking axe and berserk phase), The Beastmaster (ravager with pillager rider, crumbling pillars), and rare variant The Illusionist.
- **Final boss:** The Arch-Illager (three phases: Court, Guard, Beast).
- **Mechanics:** Alarm bells (first illager target rings the bell for reinforcements), breakable dark oak secret panels, and a caged allay side objective.
- **Rewards:** Trophy faucet (lord banners/heads, vex and ward trims, dark oak mansion room shell, emeralds). Vanilla evoker totems of undying are suppressed.

## 4. Economic roles

| Zone | Role | Pays | Costs hardest |
|---|---|---|---|
| Story and objective dungeons | Gear and compass faucet | scrap haul, promised gear, vault rolls, emeralds, diary pages | lives, weapon and armour durability |
| Resource dungeons (Cow Pits, Lush Caves) | Restock faucet | food, leather, milk, wood, clay, sand, scrap | tool durability |
| Endless Mine (per act) | Ore and materials faucet | coal, copper, iron, gold, lapis, redstone, diamonds (tiers A/B/C) | pickaxe durability, blocks, torches |
| Woodland Mansion (planned) | Trophy faucet | trophies, rare trims, room shells, emeralds | lives, consumables |
