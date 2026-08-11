# Hand-off: build the `bounties` mod from scratch

## Context

This is a **new mod**, not an edit to an existing one. It's part of a set of
five/six sibling Fabric 26.2 / JDK 25 server-side Minecraft mods living as
sibling directories: `dailyquests`, `quizengine`, `wondrous`, `cobbleeconomy`,
`ballot`. Read at least `@/a:/dailyquests/README.md` and
`@/a:/quizengine/README.md` before starting — they establish the conventions
this new mod must follow:

- **JDK 25**, Fabric Loader/API versions pinned in `gradle.properties`, sourced
  from the FabricMC example mod's 26.2 branch. Copy an existing mod's
  `gradle.properties` and `settings.gradle.kts` as your starting point rather
  than writing them from scratch — `quizengine`'s or `ballot`'s two-module split
  is the closest template (see next point).
- **Two-module split**: a pure-Java `core/` (or `engine/`) module with **no
  Minecraft on its classpath** — the build should fail if a stray
  `net.minecraft.*` import lands there — and a `fabric/` module for everything
  Minecraft-facing (events, commands, chat, persistence). `quizengine`'s
  `engine`/`fabric` split and `ballot`'s `core`/`fabric` split are the two
  existing examples; copy whichever's build file structure is closer to what
  you want.
- **Atomic config writes** (temp file + rename) for any per-player state file,
  matching `dailyquests`' `state.json` and `cobbleeconomy`'s `accounts.json`.
- **Config generated on first boot** under `config/bounties/`, following the
  `readOrCreate` pattern used in `dailyquests`' `Quests.java` and `quizengine`'s
  `Content.java` — write sensible defaults if the file doesn't exist, log that
  you created it, never silently overwrite a file that fails to parse.
- **No Mixins** if avoidable — every sibling mod manages without them, using
  Fabric API event callbacks instead (`ServerTickEvents`,
  `ServerLivingEntityEvents`, etc.).
- **Op-level admin gate** via `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)`
  for anything admin-only, matching `wondrous`'s `Gate.mayAdminister` and
  `cobbleeconomy`'s `AdminCommands` — though note a separate LuckPerms
  integration effort may retrofit these gates later; don't block on that, just
  follow the existing simple pattern for now.
- **Reward payouts as physical vanilla diamonds**, via a `giveOrDrop`-style
  helper (add to inventory, drop at the player's feet if full) — the same
  pattern used in `quizengine`'s `Orchestrator.grant` and referenced in
  `wondrous`'s and `cobbleeconomy`'s READMEs. This keeps `bounties` fully
  decoupled from `cobbleeconomy`'s bank — no cross-mod API calls needed at all
  for the reward path.

## What this mod does

A rotating "bounty board" of kill quests, always showing exactly 2 active
bounties. Players can accept bounties they see and hold up to 3 at a time
indefinitely (no expiry once accepted); completing an accepted bounty (killing
the required number of the target mob) auto-pays diamonds with no turn-in step.

### The board

- Exactly 2 bounties visible at any time.
- Each has a **1-hour display lifespan**, and the two are offset by 30 minutes,
  so a new bounty appears on the board every 30 minutes (one of the two slots
  rotates each half hour).
- **Derive the board from the clock, not from stored/scheduled state** — same
  principle as `dailyquests`' `Quests.forDay`, which derives the day's riddle
  from `Math.floorMod(dayKey.hashCode(), quests.size())` so a restart mid-day
  can't change the riddle. Do the equivalent here: compute a "half-hour window
  index" from the current time (e.g. epoch millis / 30 minutes), and hash that
  index into the bounty pool to pick each slot. Slot A uses window index `n`;
  slot B should use the adjacent window (`n` shifted so it changes on the
  off-half-hour from slot A) — work out the exact arithmetic so the two slots
  are staggered by exactly 30 minutes and both are fully deterministic
  functions of wall-clock time. This makes the board **restart-safe with zero
  persisted board state** — nothing about the board itself can desync or
  corrupt across a crash.
- This rotation math belongs in the `core` module and must be unit-testable
  without a running Minecraft server (pass in a window index or a clock value,
  assert which pool entries come back).

### Accepting and holding

- `/bounty` — show the current board (2 active bounties) and the player's
  currently held bounties with progress.
- `/bounty accept <1|2>` — accept one of the two currently-displayed board
  slots. Fails if the player already holds 3.
- `/bounty abandon <n>` — drop a held bounty, freeing a slot. Explicit
  abandon-then-accept, not auto-replace-on-full — less surprising for a player
  who forgot they had 3 held.
- Accepted bounties **do not expire when the board rotates** — a player who
  accepted "kill 5 blazes" keeps that goal indefinitely (or until they abandon
  it or complete it), even after that slot on the public board has moved on to
  a different bounty. The board and a player's held bounties are separate
  pieces of state.
- **Multiple players can hold the same bounty simultaneously** — each
  progresses and completes independently. Do not model this as a single
  shared claim.

### Completion

- Fully automatic — no turn-in command. Hook a death event
  (`ServerLivingEntityEvents` or the closest 26.2 equivalent — confirm the
  exact class/method name against the installed jar, since sibling mods'
  READMEs note Minecraft 26.x has renamed APIs before, e.g.
  `ResourceLocation` -> `Identifier`) that fires when a player's kill matches
  a held bounty's target mob type, increments progress, and on reaching the
  required count: pays diamonds via the `giveOrDrop` pattern, removes the
  bounty from the player's held list, and announces the completion in chat
  (`"<player> completed the bounty: <description> (+<n> diamonds)"` or similar).

### Rewards

- Diamonds only, scaled to target difficulty. Suggested starting point (tune
  later): zombie 1 diamond, blaze 3 diamonds, wither 10 diamonds — put these
  numbers in config, not code.
- Bounties are the **high-effort, high-pay** diamond faucet in this mod set
  (contrast with `quizengine`'s scheduled trivia, which is deliberately
  low-effort/low-pay) — don't underprice these relative to trivia, or the
  effort/reward ordering inverts and trivia becomes the better use of time.

## Config files (under `config/bounties/`)

- **`bounties.json`** — the pool. Each entry: mob id (e.g.
  `minecraft:zombie`), kill count required, diamond reward, and optionally a
  difficulty label for display. **Seed with 20+ entries minimum** — at a
  30-minute rotation with only a handful of pool entries, repeats become
  visible within a day, which makes the board feel broken rather than lively.
- **`state.json`** — per-player: list of held bounties (which pool entry, when
  accepted) and current kill progress toward each. Keyed by UUID. Atomic write,
  same pattern as `dailyquests`' `state.json`.

## Coordination with `quizengine`

`quizengine`'s scheduled trivia is also moving to a 30-minute cadence as a
separate piece of work. To avoid both mods spamming chat at the same wall-clock
moment, **offset this mod's half-hour boundary by 15 minutes** relative to
trivia — e.g. if trivia fires on the hour and half-hour, bounty board rotations
and "new bounty available" announcements should land at :15 and :45. Confirm
the actual trivia schedule with whoever's doing that work rather than assuming;
the goal is just non-overlapping wall-clock alignment.

## Architecture checklist

- `core/` (or `engine/`): `BountyDefinition` (pool entry), board-rotation math
  (pure function of a window index/timestamp -> 2 selected definitions),
  `PlayerBounties` state transitions (accept/abandon/progress/complete) as
  pure functions returning new state — no Minecraft imports anywhere in this
  module, enforced by an empty `core/build.gradle.kts` the way
  `cobbleeconomy`'s core module does it.
- `fabric/`: mod entrypoint, death-event listener, `state.json` /
  `bounties.json` persistence, `BountyCommands`, chat announcements, the
  `giveOrDrop` reward helper.
- Unit tests in `core` (no Gradle/Minecraft needed to run them, following
  `quizengine`'s "run the engine tests without Gradle" pattern in its README):
  rotation is a deterministic function of the window index (same input always
  gives the same 2 bounties, and restarting doesn't change it for the same
  wall-clock time); accepting when already holding 3 is rejected; abandon
  frees a slot; progress increments correctly and completion fires exactly
  once, not more.

## Done when

- `core` module builds standalone with zero Minecraft imports and has passing
  unit tests covering rotation determinism, accept/abandon/complete
  transitions.
- `/bounty` shows a 2-slot board that changes appropriately over time and
  survives a server restart without visibly changing (same wall-clock moment
  -> same board).
- Accepting, holding up to 3, and abandoning all work via commands.
- Killing a matching mob while holding its bounty increments progress and
  auto-completes/pays diamonds at the required count, with a chat
  announcement.
- Two players can hold and complete the same bounty independently.
- The pool has 20+ entries in `bounties.json`.
- Rotation timing is offset from `quizengine`'s trivia schedule by 15 minutes.
