# rm-t04: phased finales, Act 2

Branch `feature/rm-t04-phased-finales-act2`, off `feature/backlog-2026-10-19`, merged with the BossFight clamp (eff72e83). BossFight itself is untouched; no spine change was needed.

## Mapping onto the spine

| Dungeon | Boss | Phase 1 | Phase 2 (2/3) | Phase 3 (1/3) |
|---|---|---|---|---|
| Cow Pits | Specimen 0 (only if woken) | Specimen 0 charges alone, herd grazes | the herd charges (anger goals added once per cow) | every herd cow scales to `CowPitsFinale.HERD_SCALE` (1.5) |
| Deepslate | The Deep Sentry | meter handed over but not counted | meter counts noise (clean start at the stagger's end) | a full meter answers: tell, dark, whelp. A meter filled in phase 2 is held and answers (tell first) as phase 3 begins |
| Frostworks | "Jack Frost" (the plan's Frost Warden; the dungeon JSON name is kept) | no cold | braziers scanned, wind tell, freeze ticks away from fire | warm radius shrinks to 50 percent of `frostBrazierRadius`, minimum 2 blocks, never above the knob (`FrostFinaleRules.warmRadius`) |

Departures from the plan table, where the code differs:
- None of the three finales used `onHalfDead`; the old triggers moved to phase bounds as listed. Deepslate's meter and Frostworks' cold used to start at wave spawn.
- Cow Pits used to anger the whole herd at wake. Now only Specimen 0 charges at first. If Specimen 0 dies early the rest of the herd turns at once, so no placid cow must be hunted and the floor still finishes. An unfed Cow Pits is unchanged (no fight, pad open).
- "Herd scales" had no mechanic in the code; `HERD_SCALE` is a named constant, no new knob and no new mob.
- A floor with no Silent Deep room, or a Big Freeze with no lit brazier, still gets phases (titles, stagger) with no mechanic, as before.
- Pad gate unchanged: the finale still ends when every counted mob is dead.

## Fairness
Stagger (`bossPhaseStaggerSeconds`, default 3 s) is the tell before each mechanic arms. Deepslate keeps its 60 tick tell before the whelp; Frostworks keeps its 60 tick wind tell before the cold; cleanup stays in `onWon` and `onFloorEnded`. Nothing is placed in the world.

## Tests
- New: `CowPitsPhasesGameTest` (3), `DeepslatePhasesGameTest` (1), `FrostPhasesGameTest` (1), gametest slots 10100 to 10120. `FrostFinaleRulesTest` gained `warmRadius` checks.
- Changed, nothing weakened: `DeepslateFinaleGameTest` and `FrostFinaleGameTest` now give the wave an elite and walk to the phase their mechanic needs (`toPhase`); their `Run` rigs are package visible for reuse.
- Pad checks in the Deepslate and Frostworks phase tests assert `done` and `finaleWon`, because their fake dungeon ids have no finale for `padRefusal`; Cow Pits uses the real id and asserts `padRefusal`.

## Live-only
Fragment `docs/playtests/live-checks/rm-t04.md`: titles and stagger feel, the herd scale looks, Deepslate noise rates in phase 2, Frostworks shrunk radius readability.

## Counts
See the coordinator's reply (full suite run once at the end).
