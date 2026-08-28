# M22 - Sound pass: Chime.java, audio feedback for all interactions - Handoff

> Paste this whole file into a fresh chat to start work on this milestone.

## Read before writing anything

Read ONLY the named sections. Do not read whole reference files.

1. `docs/reference/ROOM_UX_PLAN.md`'s `## M22: Sound pass` section ONLY:
   the authoritative scope (goal, dependencies, touch points, done-when).
2. `docs/DISCOVERIES.md`: verified 26.2 API findings.
3. `CONVENTIONS.md`: mod-specific rules (one-mixin budget, status location,
   codec migration). Read once if you haven't this session.

## Dependencies

Grep for `RitualListener` in `src/main/java/`. If the lever handler and
lodestone menu exist, M19 and M21 are in place.

- M19 (physical door selection): door-selection cues fire on M19's
  interactions.
- M21 (UX consolidation): menu cues fire on M21's interactions.

If M19 or M21 has not landed, ship the run-lifecycle and room cues only.
Leave `// M22: add when M19 lands` comments at the door-selection call sites.

## Goal

A `Chime.java` class with one static method per event, each sending a
`ClientboundSoundPacket` to the player's connection. All vanilla
`SoundEvents`, no custom sound files, server-side only.

## Implementation plan

Each step is one commit. Run `build_mod` after each.

### Step 1: Write `Chime.java`

1. Use `read_mod_reference` to pull `Chime.java` from `spiritwolves` or
   `wondrous`. Both follow the same pattern: one static method per event,
   each building a `ClientboundSoundPacket` with a vanilla `SoundEvent`,
   `SoundSource`, volume, pitch, and position, sent via
   `player.connection.send()`.
2. Verify `ClientboundSoundPacket`'s constructor against the 26.2 jar (the
   system prompt covers jar verification). Expected signature:
   `Holder<SoundEvent>, SoundSource, double x, double y, double z, float
   volume, float pitch, long seed`.
3. Verify these `SoundEvents` constants exist in 26.2: `NOTE_BLOCK_BELL`,
   `NOTE_BLOCK_BASS`, `NOTE_BLOCK_CHIME`, `NOTE_BLOCK_HAT`,
   `NOTE_BLOCK_DIDGERIDOO`, `RESPAWN_ANCHOR_CHARGE`, `ENDERMAN_TELEPORT`,
   `PISTON_EXTEND`, `STONE_PLACE`. If any is renamed or removed, pick the
   closest vanilla equivalent and note it in the completion report.
4. Create `src/main/java/pocketdungeons/Chime.java`:
   - `public final class Chime` with a private constructor.
   - `private static void play(ServerPlayer, Holder<SoundEvent>, float
     volume, float pitch)` helper: builds `ClientboundSoundPacket` with
     `SoundSource.RECORDS`, position `player.getX/Y/Z()`, seed
     `player.getRandom().nextLong()`, sends via `player.connection.send()`.
   - One `public static void <cue>(ServerPlayer)` method per event below,
     each a one-line call to `play` with hardcoded volume and pitch.

### Step 2: Door-selection cues (only if M19 landed)

1. `doorSelected(ServerPlayer)`: `NOTE_BLOCK_BELL`, vol 0.3, pitch 1.0.
   Call at end of `RitualListener`'s `selectDoor`, after bulb toggle and
   screen update succeed.
2. `noSelection(ServerPlayer)`: `NOTE_BLOCK_BASS`, vol 0.4, pitch 0.6.
   Call in lever handler when `selectedStep == 0`.
3. `runStarts(ServerPlayer)`: `RESPAWN_ANCHOR_CHARGE`, vol 0.5, pitch 1.0.
   Call in lever handler after `RunLifecycle.chooseOffer` returns true.

### Step 3: Run-lifecycle cues (ship regardless of M19/M21)

1. `runComplete(ServerPlayer)`: `NOTE_BLOCK_BELL` then `NOTE_BLOCK_CHIME`,
   rising. Two `play` calls. Call at end of `RunLifecycle.completeRun`
   (line 709), after payout and offer banking succeed.
2. `runTimedOut(ServerPlayer)`: `NOTE_BLOCK_DIDGERIDOO`, vol 0.5, pitch 0.8.
   Call at end of `RunLifecycle.expireTimedOut` (line 1041).
3. `keystoneLevelUp(ServerPlayer)`: `NOTE_BLOCK_CHIME`, vol 0.4, pitch 0.8.
   Call in `Keystones.grantOffer` (line 98) after `DungeonLog.setKeystone`.
4. `keystoneDepleted(ServerPlayer)`: `NOTE_BLOCK_BASS`, two notes (pitch 0.8
   then 0.6). Call in `Keystones.returnTo` (line 57) when outcome is
   `TIMED_OUT` or `LATE` and keystone level drops.
5. `spawnerCleared(ServerPlayer)`: `NOTE_BLOCK_HAT`, vol 0.12, pitch 1.5.
   Call after the last spawner in a cell is cleared (find the detection in
   `Instances.onTick` or `RunLifecycle`).

### Step 4: Room and menu cues (only if M20/M21 landed)

1. `menuOpens(ServerPlayer)`: `NOTE_BLOCK_HAT`, vol 0.2, pitch 1.0. Call in
   `RitualListener.onUseBlock` after `DialogScreens.lodestoneMenu(...)`.
2. `roomListed(ServerPlayer)`: `NOTE_BLOCK_CHIME`, vol 0.3, pitch 1.0. Call
   in `DungeonLog.setPublicListed(uuid, true)`, sent to the host.
3. `roomUnlisted(ServerPlayer)`: `NOTE_BLOCK_HAT`, vol 0.2, pitch 1.0. Call
   in `DungeonLog.setPublicListed(uuid, false)`, sent to the host.
4. `visitorArrives(ServerPlayer owner)`: `NOTE_BLOCK_BELL`, two notes (pitch
   1.0 then 1.2). Call in `VisitService.createVisitInstance` (line 99).
   Exception: sent to the room owner, not the visitor.
5. `roomRelocated(ServerPlayer)`: `PISTON_EXTEND` or `STONE_PLACE`,
   positional. Call in `Instances.stampLobby` (line 420), sent to the owner.

### Step 5: Lobby-visiting cues (M20)

1. `lobbyOpens(ServerPlayer)`: `NOTE_BLOCK_HAT`, vol 0.2, pitch 1.0. Call
   after `DialogScreens.lobbyBrowser` is shown.
2. `visitStarts(ServerPlayer)`: `ENDERMAN_TELEPORT`, vol 0.3, pitch 1.0.
   Call in `VisitService.visit` (line 38) after admission. Sent to visitor.
3. `visitEnds(ServerPlayer)`: `ENDERMAN_TELEPORT`, vol 0.3, pitch 0.7. Call
   in `RunLifecycle.exit` (line 620). Sent to visitor.

### Step 6: Migrate existing `RESPAWN_ANCHOR_CHARGE` calls

If M19 landed: remove the two `level.playSound` calls in `RitualListener`
(lines 175, 199) and add `Chime.runStarts(player)` at the lever's commit
point. If M19 has not landed: leave them, add `Chime.runStarts` at the
`/dungeon choose` success path instead.

## Constraints

- No custom sound files. No `assets/` dir, no `.ogg`, no custom
  `SoundEvent` registrations. Vanilla `SoundEvents` via packet only.
- Do not send sounds to uninvolved players. Each cue goes to the acting
  player via `player.connection.send()`. The one exception is
  `visitorArrives`, sent to the room owner.
- Do not gate events on the chime. The chime is a side effect; a failed
  packet send is harmless. No try/catch.
- No config toggle for sounds in this milestone.

## Verification

- Run `build_mod` with default `build` after each step.
- Headless: `Chime.java` compiles, constructor signature matches the jar.
  No runtime test without a client.
- Live (deferred): every cue plays, heard only by the relevant player.
  Record in `docs/reference/LIVE_TEST_PASS.md`.
- Done when: every event in the implementation plan produces its cue.

## Completion

1. Append this milestone's architectural summary to
   `plans/COMPLETED-MILESTONES.md` (append only; do not re-read the file).
2. Append a numbered section to `docs/reference/LIVE_TEST_PASS.md` for the
   sound cues (all live-only).
3. Update `docs/reference/ROADMAP.md` if the milestone's order or scope
   changed.
4. Rename this file to `M22-handoff-completed.md` once the milestone is
   landed and its `COMPLETED-MILESTONES.md` entry exists.
