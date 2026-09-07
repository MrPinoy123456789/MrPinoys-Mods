# M67 - Close the live pass and teach only the verbs - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

- `docs/reference/LIVE_TEST_PASS.md`, M62 disposition index and linked current checks only. Do not re-read the whole file.
- `docs/DIALOGS_SPEC.md`, governing principle, movement and safety rules, exit rules.
- `docs/reference/VISION.md` sections 2.2 and 4. The verbs a first-time player needs.
- `docs/ROOM_FIXES.md`, fixed room behaviours. Reference for what live checks should now confirm.

## Dependencies

```bash
grep -rn "RunSession\|RunRecipePlan" pocketdungeons/src/main/java/pocketdungeons/RunLifecycle.java
grep -rn "roomRelocated" pocketdungeons/src/main/java/pocketdungeons/
grep -rn "FloorLoopGameTest\|CubeRecipeGameTest\|CustodyGameTest\|SituationGameTest" pocketdungeons/src/gametest/
```

First confirms M65 and M66 state machines are in the lifecycle. Second confirms no reveal-path `roomRelocated` call remains. Third confirms all first-round test registrations. If any absent, stop.

## Goal

Retire the accumulated client-verification debt with named human evidence, then make first play understandable without a manual.

## Implementation plan

Run `build_mod` after each step. This milestone is a human gate; route behavioural defects back to their owning milestones, do not fix them here.

1. **Recruit testers.** Owner recruits a vanilla-client tester, a second player for permissions and custody, and a representative six-player session. Run every applicable indexed check; superseded checks point to current replacements. Include Slime Pit drop and climb, bag lock and full reset, all recipe effects, interrupted final return, public visiting at every floor phase, station rights, shell swap, Pocket2, survival restoration and stripped-overworld progression.
2. **Observe before coaching.** Watch fresh players before explaining anything: bag chest, door window, lever, depleted tools, safe door, placing one earned object. Update `DungeonScreen` and existing task prompts only where a missing verb stopped play. Never expose the route solution or room trick. Correct only UI and audio issues here.
3. **Chime audit.** Audit each `Chime` call for recipient, duplicate playback and competition with vanilla hazard sounds. Resolve Q5 using observed omen understanding: first improve environmental feedback; if inadequate, trial one bounded vanilla ambient cue on escalation, no numeric HUD. A sound-off player must still have a visible environmental signal.
4. **Close the pass.** Append test date, artifact identity, config, seed, participants, expectation and actual result for each disposition. Correct obsolete save-path and command instructions through appendices. Close only when no applicable unverified row remains.

## Constraints

- No fake player evidence presented as a human click.
- No sound for room movement.
- Do not coerce a "passed" result because content is code-complete.
- Conditional cross-mod checks must name the supported deployment scope or remain release blockers for that scope.
- No lifecycle fixes hidden in a sound pass. Route defects back to owning milestones.

## Verification

`build_mod` with tasks `lodestoneMenuTest`, `lobbyBrowserTest`, `taskTrackerTest`, `runGameTest`, `dungeonIntegrationTest`, then `build`. The client index is the acceptance gate. Done when first-time verbs work and every historical and current live obligation has an honest disposition.

## Completion

- Append closure summary and Q5 outcome to `docs/reference/LIVE_TEST_PASS.md`. Do not re-read the whole file.
- Append architectural summary to `plans/COMPLETED-MILESTONES.md`. Do not re-read the whole file.
- Rename this file `M67-handoff-completed.md`.
- Round II waits for this gate, not just the build.
