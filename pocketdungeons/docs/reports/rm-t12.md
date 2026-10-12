# T12 report: Restless widened, new Blackout affix

Branch `feature/rm-t12-restless-blackout` (merged `feature/act-1-2-roadmap` first). Design: `docs/affix-workshop-2026-10-11.md` rows 5 and 11.

## Part A, Restless
- `RestlessRules` (pure) now answers `riseType(theme, fallenType)`: undead rise as themselves; any other mob rises as the theme's stand-in (`THEME_RISE`, a Java map: ossuary skeleton, frostworks stray, drowned_vault and prismarine drowned, basalt_foundry and wither_keep wither skeleton, blackstone zombified piglin, infestation silverfish, rootworks bogged, ender_archive endermite), else the kind's stand-in (`KIND_RISE`: wolf skeleton, spider and cave spider cave spider, piglin family zombified piglin, and so on), else a zombie. The tables are pure data in a Java map so the pure test reads them; moving them to JSON later is a drop-in.
- Never rise (`NEVER_RISE`): wither, ender dragon, warden, elder guardian, giant, villager, trader, golems. `Restless.exempt` also keeps out any mob tagged finale, brood wave, ancient warden, unscaled boss, boss, capstone wither, Steve/Alex/add, whelp, and tamed pets. The `risen` tag still stops a second rise; fire still keeps them down.
- Every risen type is a vanilla registered mob (checked by the pure test and by a gametest against the entity registry); none needs a client mod.
- `undead_rise_chance` and `undead_rise_chance_ominous` JSON names unchanged. Blurb: "Restless: any fallen mob may get up once. Burn them."
- Minimal edits outside the owned files: `BossContent.BOSS_TAG`, `CapstoneFights.WAVE_TAG` and `WARDEN_TAG` went from private to package-private so `Restless` can name them.

## Part B, Blackout
- New closed operation `dark_cells` (fraction 0 to 1, bounded in `AffixEffects.withDarkCells`, parsed in `AffixMeta`), last in the frozen order doc. A value of 0 means no operation, so a file with only `dark_cells: 0` is rejected as bending nothing.
- `dungeon_affix/blackout.json`: label Blackout, short name Blkt, order 11 (after Restless at 10), min_level 0, weight 1, `dark_cells` 0.3. `AffixIds.BLACKOUT` (also in the legacy bare name bridge and `BUILT_IN_ORDER`), colour dark gray in `Keystone`.
- `DungeonLight.blackoutCells` picks the cells: sort (PD-20), exclude entrance, terminal, `requiresLight` rooms and the lever Ordeal rooms (`LEVER_ORDEAL_CONTENT`: rising_lava, collapsing_bridge, hold_the_plate, thicket, ice_run; read from the plan because those Ordeals arm after the stamp loop), shuffle by the plan seed, take the rounded fraction (at least one). `DungeonLight.withBlackout` makes a picked cell `dark`, so the existing removal logic strips every light. `LayoutStamper.computeBlackoutCells` runs it with the strongest `dark_cells` among the active affixes; `NodeStamper.applyCell` has an overload taking the flag. No add-affix skill existed on the base branch; the pattern followed was Voided and Restless.

## Tests
- Pure: `RestlessRulesTest` extended (themes, exemptions, vanilla types); new `BlackoutTest` (manifest bounds, shipped files, determinism under reordering and `Set.copyOf`, entrance, terminal and lit rooms excluded, `withBlackout`), wired in `build.gradle.kts`. `AffixMathTest` and the coverage gate still pass.
- Gametests: new `BlackoutGameTest` (2 methods, registered in `fabric.mod.json`): a blacked out stamped room has no lights left, a requiresLight room keeps all of them, the manifest carries Blackout, every risen type is registered.
- `tools/ship.sh --quick`: SUITE GREEN, gametests 248 before, 250 after ("All 250 required tests passed").

## Only a live server can confirm
See `docs/playtests/live-checks/rm-t12.md`: the look of a blacked out floor, how many rooms go dark at 0.3 (tune `dark_cells`), the rise feel with wolves and spiders, and that a risen mob is dressed like its floor. The rise itself (needs the dungeon dimension) is not covered by a gametest.
