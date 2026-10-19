# rm-t7: one heavy thing (the relic)

Branch `feature/rm-t7-relic`, off `feature/campaign-endgame` (T0 to T6 merged). Spec: `docs/plans/2026-10-18-campaign-endgame-build-spec.md` T7.

## Built
- `RelicObjective` (new, public so the mixins can call `isRelic`): a `minecraft:heavy_core` named `Relic` (custom data key `pd_relic` holding the floor's id, stack size 1, glint) on a `chiseled_polished_blackstone` plinth in a branch cell (a path cell if the floor has no branch; never the entrance, the terminal or a room cell; no spot means a fallback spawner floor). The item entity has no despawn timer and cannot burn.
- Once a second (`FloorObjectives.tick`): Slowness I for each member holding it; if it is in no pack, on no ground and in no pot it goes back on the plinth ("The relic went back to its plinth."). A global once a second sweep removes any relic that is not the live relic of the floor the holder stands on (rescued, left, floor over, delivered, a restart).
- Pay: `FloorObjectives.bonusRolls(record, baseChests, lootPercent)` (called where `RunLifecycle.placeFloorRewards` prices the chests): the first member found holding it hands it in, once, and the chests rise by the difference between `lootPercent` and `lootPercent + relicLootPercent` (`FloorObjectiveRules.relicBonusRolls`, rounded like `Wager.scaledChests`).
- Never the gate: `ObjectiveState.gatesPad()` is false for the relic and `RunLifecycle.padGatesRefuse` asks `FloorObjectives.gatesPad`, so the spawner gate runs exactly as on a spawner floor; the bar keeps the spawner fraction.
- Rescue: `PackPots.moveToPot` takes a relic from the hotbar or offhand into the pot. A full pack gives the relic its last slot and the displaced stack takes the hand slot the relic left, so nothing is lost and the pot stays 27 slots (no format change). A pot's relic is skipped by `nonEmpty`, so settle, fail halving and orphan return never deliver or journal it (it is not a player item of value); `PackPots.holdsRelic` keeps the watch from duplicating it.
- Refusals: `RelicSlotMixin` (`Slot.mayPlace`, also `ShulkerBoxSlot`) allows only the player's own inventory slots; `RelicMenuMixin` (`AbstractContainerMenu.clicked`) says `The relic will not fit.`; `RunStorage.deposit` returns it as overflow with that line and `StorageContainer.canPlaceItem` refuses it; `BlockItemPlaceMixin` fails placement.
- Generator: `UnchartedGen.middleObjective(salt, chance)` rolls via `FloorObjectiveRules.relicRolls`; `Input` gained `relicChance` (5 argument constructor kept at 0.25) and `doors(...)` an overload; `UnchartedDoors` passes `relicFloorChance`. `DungeonDef.Node` accepts `relic` (finals still must be spawners).
- Knobs `relicFloorChance` 0.25 and `relicLootPercent` 100 (fixtures and `config/pocketdungeons.default.json` updated). The server file needs the keys only for non-default values.
- Title line: `Something heavy waits.`

## Deviations and notes
- Hot-file or unowned touches, all minimal: `UnchartedDoors` (one argument), `DungeonDef` (one word), `PackPots` (relic into the pot, `nonEmpty`, `holdsRelic`, `playerOf` package visible), `RunStorage` (two spots), `RunLifecycle` (the gate condition and the bonus call), `BlockItemPlaceMixin` (one early return), `UnchartedGenTest` (`testObjectiveHook` now pins the real roll instead of the old default).
- `ObjectiveState` is nested in `FloorObjectives` (no separate file); it gained `gatesPad()` and a `bonusRolls(record, base, lootPercent)` overload.
- A relic dropped in an unloaded chunk is not seen by the watch and could be put back on the plinth too (a second copy). Only the live floor's id pays and only once, so it is harmless.
- Any member holding it when the floor's rewards are priced counts as carrying it (not only the one on the pad).
- Slot refusals speak only through `RelicMenuMixin`; the hopper path has no message.

## Tests
| | before | after |
|---|---|---|
| pure tasks | 128 | 128 (`FloorObjectiveRulesTest` and `UnchartedGenTest` extended) |
| gametests (full suite) | 386 | 394 |

`RelicFloorGameTest` (8): plinth in a branch cell and never the gate; no room means a spawner floor; carry pays once and the pad was open throughout; Slowness I and a lost relic returns once; a rescue puts it in the pot from the hotbar with a full pack and loses nothing; a pot settle and an orphan return drop it and keep everything else; storage, chest slots, shift click, number key and block placement refuse it; the sweep removes stale, foreign and delivered copies. Full suite `--quick --no-dist`: `SUITE GREEN (All 394 required tests passed)`, once.

## Live only
Fragment `docs/playtests/live-checks/rm-t7.md` (L107): the feel of the plinth and the Slowness, the real chest and shulker refusals with the line, the pad pay in a real reward barrel, a real rescue with the relic in the hotbar and a second member taking it from the pot, the return to the plinth after a void or lava, and the pad opening with no relic.
