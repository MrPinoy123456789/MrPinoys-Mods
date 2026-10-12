# RM-T21: Ossuary coffins finale

Branch `feature/rm-t21-ossuary-coffins` (base `feature/act-1-2-roadmap`, merged three times, last after T34, T24, T40, T22).

## What changed

- **Room.** `last_rest` (Ossuary final room) now stamps six sealed coffins (`FinalFloorEarlySpecs.coffin`): 1 wide, 3 long, 2 high, polished blackstone body and lid, chiseled polished blackstone cap at the head. Direct blocks, so no theme re-skins them. Both door lanes (z 7 to 8) and the four spawn points stay clear; the exit pad is in another cell. `last_rest.nbt` was regenerated through the throwaway gametest (removed again).
- **Script.** `OssuaryFinale` (one class, one line in `FinaleScripts.registerAll()`, `FinaleWave` untouched). `spawnWave` finds the coffins by scanning the floor's cells for the block pattern (cap, then two body and lid pairs in one compass direction), never by coordinates, so any rotation works. The wave is sized by `FinaleRules.counts` from the dungeon's data finale (5 skeletons, 2 zombies, +50 percent per extra member), trimmed to 24, dealt as evenly as it divides over the coffins (a solo player: 7 mobs in 6 coffins), mixed so zombies are spread. If no coffin is found it returns false and the data wave runs (the fallback count stays in `ossuary.json`).
- **How the pad stays shut.** All occupants are spawned up front, dormant (no AI, invulnerable, invisible, silent, no gravity) inside the body block, through `ctx.addMob`. They count toward `alive`, so the finale cannot win while a coffin is sealed, and they carry `FinaleWave.TAG`, which `Restless.NEVER_RISE_TAGS` already lists (checked, and asserted in the gametest).
- **Opening.** A sealed coffin cracks when a member is within 4 blocks, or when 300 ticks (15 s) have passed since the last one cracked, nearest coffin to a member first. That is the skip rule: a coffin the party passed always opens behind them. Two never crack within 60 ticks (3 s) of each other and no coffin starts while it would take awake plus cracking mobs past 6 (an empty hall always allows one, so an oversize coffin still opens). The tell is 50 ticks (2.5 s) of stone crack sounds climbing in pitch and block dust and smoke off the lid; then the lid breaks, the occupants wake, stand on the coffin and turn on the nearest member. A lid a player mines wakes its occupants at once so nothing sleeps stuck.
- **Cleanup.** `onFloorEnded` puts back any lid that was broken; the dormant and risen mobs are discarded by `FinaleWave` as always. Only the lids are placed state.
- **Knobs.** Named constants in the pure `OssuaryRules`: `MAX_COFFINS` 8, `COFFIN_WAVE_CAP` 24, `AWAKE_CAP` 6, `WAKE_RADIUS` 4.0, `SKIP_DELAY_TICKS` 300, `MIN_GAP_TICKS` 60, `CRACK_TICKS` 50. Deliberately not `PocketDungeonsConfig` keys (no on-disk format or golden fixture change); promote when tuned twice.

## Tests

- Pure `OssuaryRulesTest` (new, `ossuaryRulesTest` task, wired into the aggregate): wave sizes by party and the cap, coffins used, dealing, interleaving, the wake rule, choosing, the gap and awake cap, the tell length.
- Gametest `OssuaryFinaleGameTest` (new, listed in `fabric.mod.json`, 4 methods, slots 9961 to 9964 because other finale tests use 9981 to 9984 and share the finale state map): dormant occupants counted and tagged; the tell with the lid still shut; only the first coffin opens; the skip rule; a mined lid; floor end restoring the lid; no coffins falls back to the data wave.
- `StampDeterminismGameTest` fingerprint re-pinned (the room changed, so the stamped blocks changed): see the run notes below. This file is a collision hotspot for every task that changes a stamped room or the stamper; the integrator should re-measure after the last merge.
- Counts: see the final line of the summary reply.

## Only a live server can confirm

- How the room reads: coffin placement, scale, whether the polished blackstone caps look right against bone block walls in dim light.
- That the tell is noticed at gameplay speed (sound volume, dust visibility) and the 3 second gap and 15 second skip feel right with real players.
- That a dormant mob inside a block is truly unseen and unhittable (arrows, sweeping attacks, spectators) and that a skeleton or zombie rising on a coffin top does not clip its lid cell.
- Zombie sunlight and skeleton behaviour in the real dimension; mobs from a two-occupant coffin pushing each other.
- Party sizes above two (24 cap) and the 4 block radius in a crowded hall.

## Post-merge fix (wake step) and counts
- Cause of the mid-tell failure (noAi true, invisible false, invulnerable true): the bare invisible flag is recomputed from active effects every tick (LivingEntity.tickEffects), so with no invisibility effect it was cleared one tick after spawn. The test was right: a sleeper stays invisible until its lid breaks.
- Fix in OssuaryFinale: dormant mobs wear an infinite hidden INVISIBILITY effect; open removes it with the other wake steps. Assertions unchanged.
- Before: OssuaryFinaleGameTest 3 of 4 passed (mid tell failed). After: 4 of 4 passed. StampDeterminismGameTest passes with the existing pin (fingerprint -749095718), no re-pin.
- tools/ship.sh --quick not green here: endlessMineRulesTest, roomShellTest and runGameTest each aborted when the JVM could not commit memory (Windows paging file, errno 1455, under 1.5 GB free virtual memory with other sessions running). Environment crashes, not assertion failures; needs a rerun when memory is free.
