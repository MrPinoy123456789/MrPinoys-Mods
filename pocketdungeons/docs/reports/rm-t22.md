# T22 report: Kennels handler

Branch `feature/rm-t22-kennels-handler` (merged `feature/act-1-2-roadmap` twice, the second time with T11 and T12 in). Design: `docs/boss-workshop-2026-10-11.md` row 4 and decision 4b.

## What changed
- `KennelsFinaleRules` (pure): the phase machine WAITING, WINDUP, EMPOWERED, SPENT. Windup starts when the Alpha is at or under half health and the handler lives; a handler already dead at that moment means nothing ever happens; a handler dead during the windup (including the tick it would end) cancels; dead after the empower shrinks once. Named constants: `TRIGGER_HEALTH_FRACTION` 0.5, `WINDUP_TICKS` 100, `PACK_SCALE` 1.4, `PACK_HEALTH_BONUS_PERCENT` 0 (off), `ANGER_REFRESH_TICKS` 20.
- `KennelsFinale` (the one script class), registered for `pocketdungeons:kennels` by one line in `FinaleScripts.registerAll()`. `FinaleWave`, `HostileWolves` and wolf spawning are untouched.
  - Windup: `Raider.setCelebrating(true)` on the handler (re-asserted each tick), `SoundEvents.RAID_HORN`, action bar "Kill the handler!".
  - Empower: `Attributes.SCALE` times 1.4 on every living wolf of the wave (the Alpha included), persistent anger and target at the player it hunts or the nearest member (refreshed every second so the red eyes hold), glowing tag plus a scoreboard team `pd_pack_<slot>` coloured red. Optional max health bonus is coded but off.
  - Shrink: scale (and max health if the bonus were on) restored from the stored originals, glow off, wolves leave the team, team removed. Anger is left alone so the pack still fights.
  - Team cleanup: on shrink, `onWon`, `onFloorEnded` (floor end and teardown both reach it), and a server stop hook (registered lazily on first team, so pure tests never load Fabric events).
- Handler name: "The Handler" (red, visible), set by the script in `onWaveSpawned` on the first pillager. `kennels.json` is unchanged: `DungeonDef.FinaleMob` has no name field and adding one would touch the parser. It is a finale-tagged mob, and `Restless` already skips `FinaleWave.TAG`, so it cannot rise (a pure source scan guards that). With a party of two the data wave holds two pillagers; only the first is the handler.
- Knobs are named constants, not `pocketdungeons.json` keys: a new config key means regenerating every golden config fixture. Promote when tuned.

## Tests
- Pure: new `KennelsFinaleRulesTest` (wired as `kennelsFinaleRulesTest`): trigger line, dead Alpha, dead handler at half, windup hold and end, cancel (also on the end tick), shrink once, full ordered run, the constants, source scans. `FinaleScriptsTest` still passes with the real registration.
- Gametest: new `KennelsFinaleGameTest` (5 methods, listed in `fabric.mod.json`): registered for the Kennels and handler named and tagged; windup then growth (scale 1.4, glow, anger, team) and team gone after `floorEnded`; cancel; shrink; dead handler at half does nothing. It drives `FinaleWave.step` with a wrapper that spawns the elite as part of the wave.
- `tools/ship.sh --quick`: SUITE GREEN, "All 261 required tests passed" after the final merge of the integration branch. Before: 256 (261 minus this task's 5 gametests; not measured separately because the base moved while the run queued). Pure tests: one new class, all green.

## Only a live server can confirm
See `docs/playtests/live-checks/rm-t22.md`: whether the arm-up pose and the horn read, whether the red outline reads (the team colour only shows on a glowing entity), whether 1.4 scaled wolves path through the den, and the true feel of a 5 second windup.
