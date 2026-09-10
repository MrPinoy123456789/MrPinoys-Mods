# Astra live verification prompt

> Paste this whole file into a GPT-6 Astra session to run the live
> computer-use verification pass. It covers only the items that genuinely
> need a live Minecraft client: GUI interaction, player positioning,
> two-player behaviour, and visual geometry. Everything else has been
> verified headlessly by Devin (see `docs/DISCOVERIES.md` items 22, 24,
> 25, 27, 28, 29, 30, 31, 32, 33: all RESOLVED).

## What you are testing

Pocket Dungeons is a server-side Fabric mod for Minecraft 1.21.4 (mapped
version 26.2). It adds a run-based dungeon game mode: each player owns a
persistent room, three doors offer dungeon runs, players spend keystones,
progress through procedural rooms without backtracking, and return to their
physically relocated room. The mod is server-side only: no client assets,
no client dependency, no second mixin beyond `CustomClickMixin`.

Repository root: `A:\MrPinoys Mods\pocketdungeons`
Workspace root: `A:\MrPinoys Mods`

## Environment setup

You will use computer use to operate the local terminal. The dev server
is started with:

```
cd "A:\MrPinoys Mods\pocketdungeons" ; .\gradlew.bat runServer --offline
```

This launches a dedicated server on localhost. The server console accepts
Minecraft commands. You will need a Minecraft client connected to
localhost to perform the visual and interactive checks. If two players
are needed, connect a second client.

Key facts:
- Mod id: `pocketdungeons`
- Entry command: `/dungeon`
- Dimension: `pocketdungeons:void`
- The server console can run admin commands directly (no `/` prefix
  needed in the console, but the client needs it).
- A stale `session.lock` in `run\world\` may block startup. If the server
  fails to boot with a lock error, delete `run\world\session.lock` and
  retry.

## What has already been verified (do not re-test)

All API surfaces in `docs/DISCOVERIES.md` items 22, 24, 25, 26, 27, 28,
29, 30, 31, 32, 33 are RESOLVED. The 26.2 jar API sweep confirmed every
method signature and behavior the mod relies on. The Fabric API source
confirmed the reload listener runs on the server thread. The
`JarFileSystemWalkTest` confirmed the packaged jar filesystem walk works.

Do not re-verify these. Focus only on the live items below.

## Test scenarios

There are two categories: the two remaining DISCOVERIES items (20 and 21)
that need live multi-player or restart behavior, and the filtered
LIVE_TEST_PASS items that need a real client.

### Scenario 1: Server restart reconnect (DISCOVERIES item 20)

What it tests: `RunSession.derivePhase` is correct headlessly, but the
end-to-end wiring (server restart, in-memory record lost, reconnect) has
not been live-tested.

Steps:
1. Start the dev server. Connect a client.
2. Enter a dungeon with `/dungeon`. Choose a door. Get into an active
   floor (walk through the entrance).
3. Note your keystone level and current phase (you can check with
   `/dungeon admin list` in the console).
4. Kill the server process (not a clean `stop`; use the terminal to
   force-kill the Java process). This simulates a crash.
5. Restart the server with the same command.
6. Reconnect the client.
7. Run `/dungeon` again.

Expected outcomes:
- The player rejoins their existing instance, not a fresh one.
- The phase is reconstructed correctly: if the player was in an active
  floor, they return to the floor, not the lobby. If the record shows
  `awaitingDoorChoice=true` and `floorIndex=0`, the phase is HOME, not
  FLOOR_CLEARED, so the player cannot re-enter the settlement path.
- No keystone duplication or loss.
- The dungeon cells are still loaded (the forceload tickets persisted).

Record: the phase before the kill, the phase after reconnect, whether
the player returned to the correct location, and whether the keystone
level was unchanged.

### Scenario 2: Two-player disconnect during homecoming (DISCOVERIES item 21)

What it tests: `completeHomecomingCleanup` treats a disconnected member
as "crossed" (does not block cleanup). The M63 recovery path handles
their return. This has not been live-tested with a real disconnect
during the crossing window.

Steps:
1. Start the dev server. Connect two clients (Player A and Player B).
2. Both players enter the same dungeon (Player A hosts, Player B joins
  the party).
3. Complete the dungeon: reach the exit room, stand on the lodestone
  pad.
4. The silent homecoming begins: the saved room is stamped behind the
  final staging door, the door opens, and the party walks through.
5. During the crossing window (after the door opens, before all members
  have crossed), Player B disconnects.
6. Player A continues walking through.
7. Player B reconnects.

Expected outcomes:
- Player A completes the crossing. The old floor cells are released
  (cleanup is not blocked by Player B's disconnect).
- Player B reconnects and is placed correctly: either in the safe room
  (if the cleanup completed) or at the recovery point (if it did not).
- No duplication of the room or the floor.
- No stranded chunks (the old floor's forceload tickets are released).

Record: whether cleanup completed with Player B disconnected, where
Player B landed on reconnect, and whether any chunks or entities were
orphaned.

### Scenario 3: M65 silent homecoming visual checks

These are from `docs/reference/LIVE_TEST_PASS.md` section "M65
supersession: old live clock and homecoming checks" under "New live
checks (M65)". They are live-only and cannot be verified headlessly.

Steps:
1. Complete a dungeon run (reach the exit, stand on the pad).
2. Walk through the final homecoming.

Expected outcomes (verify each):
- The party walks through the staging door into the room. Zero
  teleport.
- The room contains recognizable furnishing (the saved room's
  furniture is present, not a seed reconstruction).
- All party members cross physically.
- Zero sound cue at the reveal (no `Chime.roomRelocated`).
- Zero explanation at the reveal (no chat message or UI text
  announcing the room move).

Record: each of the five checks as pass or fail, with a note.

### Scenario 4: M66 recipe checks (live-only subset)

These are from `docs/reference/LIVE_TEST_PASS.md` section "Recipe checks
(M66)" under "Live-only (blocked, unverified)". The headless tests
already cover the escrow read/write and the match logic; these are the
GUI and positioning checks that need a real client.

Steps (one per recipe):
1. Hold a keystone in the main hand and the catalyst in the off-hand.
2. Right-click the Cube.
3. Verify the confirmation message appears.
4. Preview a door. Verify the preview reflects the recipe effect.
5. Cancel the preview. Verify the escrowed catalyst is returned.
6. Commit the preview. Verify the dungeon stamps with the recipe
  effect.

Recipes to test (catalyst, recipe, minimum keystone level):
- String, BOUNDED_SUPPLY, level 1
- Amethyst shard, PATH_EXTENSION, level 1
- Compass, COMPASS, level 1
- Ominous bottle, OMINOUS, level 1
- Bone, FERAL, level 1
- Wool, DEEP_DARK, level 10 (refuses below 10; verify the refusal
  does not consume the catalyst)

Record: each recipe as pass or fail, with the confirmation message
text and whether the catalyst was consumed/returned correctly.

### Scenario 5: M64 physical room checks

These are from `docs/reference/LIVE_TEST_PASS.md` section 38. They
verify the player's physical experience of rooms the selector chose,
not just that the selector chose them.

#### 5a: Spent optional tool still has exit

1. Enter a dungeon with a Frame Lock or similar item-gated room.
2. Place the key item in the chest. The door opens.
3. Remove the item from the chest. The door stays open.
4. Walk through the door and back. The door does not close.

#### 5b: Rising Lava

1. Enter a Rising Lava room. Lava spreads inward from the walls.
2. Pull the lever. Lava drains, room is marked solved.
3. Leave and re-enter the cell. Lava does not return.

#### 5c: Collapsing Bridge

1. Enter a Collapsing Bridge room. Sticky pistons hold planks across a
  gap.
2. Stand on the planks. After a short delay, pistons retract and
  planks drop.
3. Wait. Pistons re-extend and planks return.
4. Walk back across. Bridge holds long enough to cross, then
  collapses again.

#### 5d: Return path on a two-story room

1. Enter a two-story room (spanY > 1). A climbable route exists from
  lower to upper floor.
2. Fall to the lower floor. Climb back up using the route.
3. Check headroom. No block prevents a player hitbox from passing
  through.

Record: each sub-scenario as pass or fail, with a note on any
geometry issue.

### Scenario 6: M63 custody edge cases (live-only subset)

These are from `docs/reference/LIVE_TEST_PASS.md` section 37. They are
the custody checks that need a real client or a real process kill.

#### 6a: Gamble station money path

1. Find the gamble station. Buy one gamble with exactly the listed
  cost. Confirm emeralds are debited once, one item arrives, chat
  names what arrived.
2. Buy one with a full inventory. Confirm the item drops at your feet
  rather than vanishing, and emeralds were still debited exactly once.
3. Click the same trade repeatedly and quickly. Confirm one debit per
  item, never two debits for one item and never one debit for two.
4. Open the screen, have a second player take your emeralds, then
  click. Confirm the refusal spends nothing.

#### 6b: Real process kill between clear and saved-data write

1. Enter a dungeon with a recognizable survival inventory.
2. Kill the server process (not `stop`) within a second or two of
  entering.
3. Restart. Confirm the log carries `Repaired a lost stash record` if
  the write was lost, or nothing if it landed. Either is correct; what
  must not happen is the player logging in holding neither inventory.
4. Leave the dungeon. Confirm the survival inventory comes back
  exactly once, with components intact.
5. Repeat, killing during the exit instead. The leaving branch is
  deliberately not journalled, so the expected behavior is different:
  the stash flag is still set and the next tick retries the restore
  from a clean state. Confirm no duplication.

#### 6c: Orphan overflow at a real dungeon entrance

1. Arrange an orphan larger than 35 stacks (leave a dungeon carrying
  a full void inventory, twice, without re-entering in between).
2. Re-enter. Confirm the overflow lands on the floor of the safe room
  and is recoverable, rather than falling through a cell that is
  mid-clear.

Record: each sub-scenario as pass or fail, with a note.

## Cost guidance

These scenarios involve screenshots, clicks, waiting, and observation.
To minimize computer-use actions:
- Use the server console for admin commands (`/dungeon admin list`,
  `/dungeon admin plan`, etc.) rather than typing in the client chat.
- Take screenshots only when a visual check is needed (homecoming,
  room geometry, lava, bridge).
- Batch the recipe checks: test all six recipes in one session
  without restarting.
- The process-kill scenarios (1, 6b) need terminal control, not
  client screenshots. Use the terminal to kill the Java process and
  restart.

## Output

After running the scenarios, write a report to
`A:\MrPinoys Mods\pocketdungeons\docs\ASTRA_LIVE_RESULTS.md` with:

1. Each scenario as pass or fail.
2. For each failure: what happened, what was expected, and a
   screenshot reference if relevant.
3. Any new traps discovered (number them continuing from
   `docs/DISCOVERIES.md` item 33).
4. A summary of which DISCOVERIES items (20, 21) are now RESOLVED
   versus still UNVERIFIED.

Do not edit source files. Do not run Gradle or Git. Do not modify
`docs/DISCOVERIES.md` directly; note your findings in
`docs/ASTRA_LIVE_RESULTS.md` and the repository owner will update
DISCOVERIES.md after reviewing.

## Punctuation rule

This workspace forbids em dashes and double hyphens as punctuation in
all prose. Use colons, semicolons, commas, periods, or parentheses
instead. Single hyphens in compound words (server-side, two-story) are
fine. Command line flags (--offline) are fine. Apply this rule to
everything you write, including the results file.
