# Hand-off: strip `wondrousReward` from dailyquests

## Context

`dailyquests` is a Fabric 26.2 / JDK 25 server-side mod. Riddle-of-the-day with a
streak, paid in diamonds. It currently has a soft-dependency integration with a
sibling mod called `wondrous` (item catalog): a completed quest can *optionally*
grant a wondrous item in addition to the streak diamonds. This is being removed —
the design direction for the whole mod set is that every mod pays out in plain
vanilla items (mostly diamonds) and nothing cross-depends on another mod's items
or economy. Diamonds are the only reward `dailyquests` should ever hand out.

Read `@/a:/dailyquests/README.md` first for full mod context (config files,
commands, how the daily quest is derived from the date).

## What to change

1. **`src/main/java/dailyquests/Quests.java`**
   - The `Quest` record (around line 45) has a `wondrousReward` field with
     accompanying javadoc. Remove the field and update the javadoc.
   - `sampleQuests()` (near the bottom of the file) passes `"big_hole_shovel"` as
     the `wondrousReward` arg on one sample entry — drop that argument, all
     `Quest` entries become 4-arg constructor calls.

2. **Turn-in logic** — find wherever a completed quest's `wondrousReward()` is
   read and a wondrous item is granted (likely in a `TurnIn.java` or similar —
   the README calls out `TurnIn.lookup()` specifically as untested code). Remove
   the grant branch entirely. The turn-in should still pay streak diamonds exactly
   as it does today.

3. **`build.gradle.kts`** — remove the `compileOnly("wondrous:wondrous-api:0.1.0")`
   dependency line and its explanatory comment block (it's directly next to the
   `dependencies {}` block, clearly marked as being for the wondrous soft-dep).

4. **`src/main/resources/fabric.mod.json`** — remove the `"suggests"` entry that
   references `wondrous`, if present.

5. **Existing `quests.json` files on disk** — no migration needed. Gson silently
   ignores JSON fields that no longer exist on the target class, so old config
   files with a `wondrousReward` key still parse fine. Not a compatibility
   concern, just note it for anyone testing against an existing config directory.

## What NOT to change

- Streak mechanic, diamond cap, date-derived quest selection, `/daily` commands,
  `settings.json`, `state.json` format — all unchanged.
- This is a purely subtractive change. No new features, no new tests needed
  beyond confirming the mod still builds and a turn-in still pays diamonds.

## Done when

- `./gradlew build` succeeds with zero references to `wondrous` anywhere in
  `dailyquests`'s source or build files.
- A turn-in still awards streak diamonds correctly.
- `grep -ri wondrous` in the `dailyquests` tree returns nothing except possibly
  this plan file and the README (update the README too if it mentions the
  `wondrousReward` field or gives an example quest using it).
