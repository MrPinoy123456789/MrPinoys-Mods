# Report: t5 act data and rewards

## Branch

- Branch: `devin/t5-act-data-rewards`
- Base: `devin/t4-astrolabe-glyph` (`72e4a3a1`)
- Tip: `a69e00bd`

## Test counts

| Suite | Before | After |
|---|---|---|
| `./gradlew test` (pure) | 130 tasks | 131 tasks, green (adds `ActRewardDataTest`; `ShellPaletteTest` updated to 7 palettes) |
| `./gradlew runGameTest` (All N required tests passed) | 402 | targeted run only: ActRewardGameTest 2/2 pass (full suite not rerun on this tip) |
| `./gradlew dungeonIntegrationTest` | PASS | not rerun on this tip |

## What changed

- `src/main/resources/data/pocketdungeons/act_reward/act_<0..5>.json`: six files, spec 5 exactly (colour, crest, two story lines, 8x6 window art, rewards); act 0 `"rewards": {}`.
- `src/main/java/pocketdungeons/ActReward.java`: the record and parser (version gate, ChatFormatting name check, 6 rows of 8 known letters, decor weight <= 64).
- `src/main/java/pocketdungeons/ActRewardManifest.java`: parses `data/<pack>/act_reward/*.json`, validates crest/disc/decor/window item ids against the item registry, rejects duplicates and names missing acts as rejections; published through `ContentSnapshot`/`ContentReload` like the other manifests, so `PackValidator` findings pick up rejections automatically.
- `src/main/java/pocketdungeons/ActRewards.java`: `actCompleted` pays every online member of `record.members` once per player; `pay` delivers disc, window bundle (one stack per colour, 48 pixels), decor bundle, title (`titlesEarned` + `pd_title_<n>` team with the diamond prefix and reset, foreign teams left alone and logged once) and shell unlock; spec 6.4 chat lines, milestone title and challenge sound; `backPay` pays unclaimed completed acts on join.
- `src/main/java/pocketdungeons/DungeonLog.java`: `act_rewards_claimed` and `titles_earned` sidecar maps with optional codec fields and claim/earn accessors.
- `src/main/java/pocketdungeons/DungeonProgress.java`: `evaluate` calls `actCompleted` at the prologue flag (act 0), each newly opened act (act n), and campaign complete (act 5).
- `src/main/java/pocketdungeons/RunLifecycle.java`: `onJoinHaul` runs `backPay` and `applyTitleTeam`.
- `src/main/java/pocketdungeons/RoomBuilder.java`: `OLD_MINE` palette (spruce floor and ceiling, cobblestone wall, spruce slab/stairs, hint "Finish Act 1") registered in `SHELL_PALETTES` and `shellOrder`; the generic shell menu shows it locked until earned.
- `src/gametest/java/pocketdungeons/ActRewardGameTest.java`: both members collect rewards + title + shell + team once; repeat pays nothing; back pay on join runs once.
- `src/test/java/pocketdungeons/ActRewardDataTest.java`: the six files parse, windows are 6x8 valid letters, act 0 empty, decor <= 64, act 1 shell is `old_mine`.
- `src/test/java/pocketdungeons/ShellPaletteTest.java`, `build.gradle.kts`, `src/gametest/resources/fabric.mod.json`: palette count 7, test registrations.

## Decisions I made

- `pay` counts "rewards in your inventory" as delivered kinds (disc, window, decor, title, shell), so act 1 says "5 rewards" and acts 2 to 5 say "4".
- Missing acts surface as PackValidator findings (`act_reward - no file for act n`) rather than a required coverage gate, matching the optional content treatment.
- The title team prefix is rebuilt each apply, so edited act data or a title rename fixes the nameplate on the next join.

## Deviations from the spec

- `minecraft:chain` and `minecraft:blackstone_bricks` do not exist in 26.2; act 1 decor uses `minecraft:iron_chain`, act 4 decor uses `minecraft:polished_blackstone_bricks` (the same items under their current registry names).

## Only a live server can confirm

- Finish the last dungeon of Act 1 with a party: every member gets the toast, the chat lines, the Delver prefix over their head and in tab, the disc, both bundles and the Old Mine shell in Manage Room.
- Rejoin after Act 1: back pay fires once and the prefix appears.
- Put a player on any other team, grant the title: their nameplate is untouched and one log line records it.

## Expected merge conflicts

- `DungeonLog.java` (codec + accessors) and `DungeonProgress.java` (`evaluate`) with any progression work.
- `RoomBuilder.java` palette list with other shell additions.
