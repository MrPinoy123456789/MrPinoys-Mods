# T10: affix quick changes

Branch `feature/rm-t10-quick-affixes` (off `feature/act-1-2-roadmap`). One commit per change.

1. Explosive: `MINE_FUSE_TICKS` 20 to 25 in `RoomContent`. No other test or doc hard-coded 20.
2. Silenced: `PressureSources.silencedUse` sends "Silenced: n/3" on the action bar to the player who used the item (text from `OmenBarText.silencedCount`). The every-third-use rule is untouched; the third use shows 3/3 as the wave fires.
3. Cooked: the floor clear title gains a second line, "Cooked: ominous loot", only when the floor's affixes hold `ominous` (`OmenBarText.clearedLines`, `RunLifecycle.announceFloorCleared`).
4. Restless: `min_level` 3 to 5. Nothing in `AffixManifest`, `AffixMath` or tests depended on 3 (`AffixMath` compares `level >= minLevel` generically).
5. Voided: `LayoutStamper.computeVoidedCells` now skips puzzle and ordeal cells.

## What the Cooked bonus is (read from code)

Cooked has one effect in its JSON, `"ominous": true`. In code that does three things: the stamper writes the ominous trial spawner and vault blockstates (spawners bite harder); the player gets Trial Omen; and the floor's reward barrel (and any finish vault rolls) is drawn from the `tier_N_ominous` loot tables instead of `tier_N` (`TrialContent.rollBarrelLoot` via `LootTables.tierTable(tier, ominous)`; `RunLifecycle` passes `affixes.contains(OMINOUS)`). The ominous tables have the same roll counts but a different item mix. There is no extra scrap and no extra rolls. So the line says "Cooked: ominous loot", which is exactly what happens; it does not claim more loot. Note the run start chat line says "the payout is worth more", which I did not verify against the tables; the owner may want to compare tier tables.

## Voided exclusion: how a puzzle or ordeal is detected

The role operation alone cannot tell (roles only have operations TRIAL_ENCOUNTER, TOOL_CACHE, NONE, and every puzzle is a `corridor` role). The plan already holds the placed room for each cell when Voided is computed, so `isPlainTraversal` reads the room's manifest entry. A cell is excluded when its room is gated (`access: gated`), has a `pressure` source, or has role operation NONE with a `content` spec (Ropewalk, Bazaar, Sump, cow pens and so on). Plain halls and ungated encounter or loot rooms stay eligible. Entrance and terminal were already excluded.

Determinism (PD-20): cells are still sorted and the rng draw is taken for every non-entrance, non-terminal cell before the eligibility test, so an exclusion removes only its own cell and no other cell's outcome changes. Covered by `AffixQuickChangesTest.voidedExclusion`.

Side effect: `StampDeterminismGameTest.STAMP_FINGERPRINT` is re-pinned (935166682 to -133370662). Its second variant stamps seed 7 with Voided, which now keeps a puzzle or ordeal cell whole. I did not separately prove that this is the only cause; no other change here touches stamped blocks.

## Tests

New pure test `AffixQuickChangesTest` (wired in `build.gradle.kts`): fuse, silenced text and wiring, Cooked lines, Restless level, Voided exclusion and eligibility rule.
Gametests: 248 before, 248 after (no gametest added). `tools/ship.sh --quick`: SUITE GREEN.

## Live checks

`docs/playtests/live-checks/rm-t10.md`. Only a live server confirms the action bar line, the title beat, and the Voided hole placement.
