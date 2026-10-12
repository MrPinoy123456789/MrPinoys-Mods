# Known issues and traps

## Status at handoff

- The merged branch `feature/act-1-2-roadmap` passed the whole suite: `All 302 required tests passed`, `BUILD SUCCESSFUL`
  (it was 248 before this build). Pure tests green. Start every task from a green tree and keep it green.
- `sessionStateTest` is not part of the default `test` task; T01 allowlisted `PocketDungeonsConfig.KNOBS` and
  `FinaleScripts.SCRIPTS`. New static state holders must be registered with `SessionState.clearedAtStop` or allowlisted.
- `defaultConfigFileTest` is real: `config/pocketdungeons.default.json` must list every knob. After adding knobs run
  `PD_WRITE_DEFAULTS=1 ./gradlew defaultConfigFileTest` to rewrite it, then commit the file.

## Design facts that surprised people

- Blood: an affix with a `life_cost` op, paid once at door commit. The deal cannot know the party's lives, so an unpayable
  Blood door is refused with the existing lives line; with 1 life and three Blood doors the party can only go home.
- 13 dungeon edges still carry the older `lives` price; they were not removed.
- The Deepslate blood door could not be built: the loader requires every non-final floor to have a free exit, forbids
  duplicate edges, and only allows edges from layer n to n+1. Deepslate is now a 4 floor run with a free door.
- "Reaching floor 3 of the previous act's mine" (the act gate) means clearing the guardian floor, because the depth record
  is written on a clear.
- Floors with no node (the Endless Mine) pay no wager piece in the copper chest: there is nothing to seed it on.
- Restless excludes mobs tagged boss, finale, capstone wave, unscaled boss, Steve/Alex, whelp, tamed pets and mobs that
  already rose. Tag your new mobs with the finale tag (via the finale context) so Restless skips them.
- Gametest worlds have no dungeon dimension, so `FinaleWave.tick` cannot run in them: drive `FinaleWave.step(...)` as
  `FinaleScriptGameTest` does.
- Finale gametests share `FinaleWave` state by slot number: colliding slot numbers make tests unregister each other.

## Environment traps

- Linux: use `./gradlew`, not `tools/ship.sh` (Windows only). Java 25.
- Config fixtures (`src/test/resources/config-fixtures/*`) conflict on every branch that adds a knob; keep both sides and
  regenerate (see 00-START-HERE).
- The stamp fingerprint in `StampDeterminismGameTest` moves on any room change; re-pin in its own commit.
- A new gametest is not run unless listed in `src/gametest/resources/fabric.mod.json`.

## Not verifiable offline (live playtest only)

How tells read, pathing of scaled mobs, whether the pad stays shut in a real run, real spawn spots in cluttered rooms,
and every "does it feel fair" question. List them in your report; do not try to settle them.

## Test traps found while integrating

- Several gametests passed alone and failed in the full run: seeds and delays that mix in the test's absolute world
  position, a random owner UUID that sometimes rolls a new affix (Blood) at compass 5 or more, and shared static state
  between classes. Use compass 4 or lower when you need an offer with no seeded affix; track state changes over time
  instead of sampling one late tick; use unique slots.
