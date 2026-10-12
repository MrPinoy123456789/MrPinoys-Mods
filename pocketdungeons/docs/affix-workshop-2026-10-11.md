# Affix workshop 2026-10-11

Running ledger. Nothing here is applied until the owner says so. Status: agreed, applied, deferred.

Findings from the read (code, not decisions):
- `weight` is loaded but unused; `AffixMath.pick` is a plain shuffle.
- `incompatible` is empty on all ten affixes, and `pick` does not skip incompatible pairs despite the `AffixDefinition` javadoc.
- Effect numbers mostly live in `PocketDungeonsConfig` (for example `swarmingMobFactor` 1.5, `overclockedCooldownFactor`), not in the affix JSON. The JSON values may be inert.
- Feral is retired (`min_level` 999).

| # | Affix | Decision | Exact change | Status |
|---|---|---|---|---|
| 1 | Cooked (`ominous`) | Keep. Show the ominous bonus on the floor clear big text (title). | Java change, not data: add a line to the floor clear title naming the Cooked bonus. Needs a build task and a LIVE_CHECKS entry. | agreed |
| 2 | Swarming | Leave alone. | None. (Open nit: blurb says "more drops"; not verified.) | agreed |
| 3 | Overclocked | Pay for speed: a floor cleared under a target time pays extra scrap. Target shown on the floor title. Cooldown factor 0.4 stays. | Java change (no par timer exists today). Starting numbers, to tune: target 360 s, bonus +1 scrap. Basis: BALANCE.md solo clears run about 190 to 640 s (median near 400), one outlier at 1190 s. Fix blurb to name the target. | agreed (numbers open) |
| 4 | Loaded | Redesign as trapped chests: the guaranteed tool cache chest keeps its loot but opening it springs an ambush. Drop the dead `extra_trial_bodies` flag and the "more trial bodies" blurb. | Java change, build cost S to M (a use-block callback on the cache chest, no mixin; spawn a themed wave around the chest on first open, once). Data: keep `bonus_tool_pool`, remove `extra_trial_bodies`, rewrite blurb. Ambush size and mob list open. Must not soft-lock: ambush spawns outside the chest cell's doors and does not block the exit. | agreed (details open) |
| 5 | Restless | Widen to every mob, not just undead. Risen mob is theme-matched (for example wolves rise as bone hounds). Fire still keeps them down. Keep the 2 s groan and soul telegraph. | Java change, build cost M: `RestlessRules.shouldRise` currently gates on undead type; add a per-theme rise table. Data: keep `undead_rise_chance` 0.3 and 0.5 (rename later). `min_level` 3 is dead (first affix at compass 5); set to 5 when convenient. Check each rise type against vanilla limits. | agreed (details open) |
| 6 | Molten | Do nothing. (Held idea for Workshop 3: lava pockets with water making mineable obsidian.) | None. | agreed |
| 7 | Explosive | Extend the mine fuse by 25 percent. No other change. | `RoomContent.java:452` `MINE_FUSE_TICKS` 20 to 25 (hard-coded constant, not a knob). Add a LIVE_CHECKS line when applied. Blurb nit left alone for now ("the only TNT you will ever find"). | agreed |
| 8 | Silenced | Show the count: an action bar line "Silenced: n/3" after each consumable used. Rest unchanged. | Java change, build cost S: in `PressureSources.silencedUse` send an action bar message with the running count (the counter already exists). Player text per the player-text skill. Add a LIVE_CHECKS line. | agreed |
| 9 | Voided | Exclude puzzle and ordeal cells from Voided, so a voided cell is only ever a plain traversal cell. Keep the three shapes. | Java change, build cost S: in `LayoutStamper.computeVoidedCells` skip cells whose room role is a puzzle or ordeal operation (read the role the way `isHazardEligible` does). Add a LIVE_CHECKS entry: first compass 45 trip. | agreed |
| 10 | Incompatible pairs | None. Molten, Explosive and Voided can co-occur. (Note: `AffixMath.pick` ignores `incompatible` anyway, and `weight`; fix the javadoc or the pick when convenient.) | None. | agreed |
| 11 | New affix: Blackout | Add. Sight pressure: lights go out in scattered cells, reusing the `DungeonLight` dim and dark logic. Ravenous and Tethered not kept. | New file `dungeon_affix/blackout.json` plus Java for the effect (build cost S to M; a closed-set operation like `dark_cells` in `AffixEffects` and `AffixMeta`). Label, blurb, min compass, which cells and whether a light source counters it are open. Must never darken a cell that `requiresLight`. | agreed, details open |

## Workshop 1 complete

Nothing applied. Every row except 2 and 6 needs Java. Live checks to add when each is built: 1, 3, 4, 5, 7, 8, 9, 11.
