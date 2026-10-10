# Pocket Dungeons code map

Grep this file before reading source: `grep -n -i "sculk" docs/CODEMAP.md`. The class list below is generated
(`python tools/gen_codemap.py`); regenerate it after adding or renaming a class. This head is hand written.

## Where to look for a task

| Task | Start here | Skill or doc |
|---|---|---|
| A menu, dialog or confirm screen | `DialogScreens` (build), `DialogRouter` (clicks), `DialogKit` | `dialog-screens` |
| A config knob | `PocketDungeonsConfig` (one `Knob` per setting), `config/pocketdungeons.default.json` | `tune-knobs` |
| A room (spec, template, manifest) | `*Specs.java`, `RoomTemplateGenerator`, `dungeon_room/*.json` | `room-building` |
| A dungeon, floor, node, reward | `dungeon/*.json`, `DungeonDef`, `RunLifecycle.promisedItems`, `PromisedGear` | `dungeon-content` |
| Door picker, preview, commit | `HallRoom`, `StagingFurniture`, `Instances.previewDoor` and `commitDoor`, `RunLifecycle.commitDoor` | |
| Floor clear, bank, leave, fail | `RunLifecycle` (`bankHaul`, `advanceFloor`, `completeRun`), `Instances.failRunOmen`, `failRunLeft` | decision docs in `docs/` |
| Scrap, haul, compass math | `ScrapMath`, `FloorPay`, `IntervalBanking`, `DungeonLog` | |
| Sculk, Heard meter, whelp | `PressureSources`, `SculkOmen`, `Whelp`, `SonicBoomWhelpMixin` | |
| Wolves, Kennels, pillagers | `HostileWolves`, `KennelSpecs`, `FeralContent`, `kennels` data | |
| Player save data | `DungeonLog` (`Entry` and its sub-records), `RoomStore`, `RoomWhitelist`, `RoomBans`, `RoomCompany` | golden fixtures in `src/test/resources/dungeonlog` |
| Party, invites, bans, visits | `PartyService`, `VisitService`, `RoomBans` | |
| Instance lifecycle and cleanup | `Instances`, `InstanceTeardown`, `InstanceAdmin`, `SessionState` | |
| Mob stats and scaling | `MobScaling`, `AffixEffects`, `MobUniforms` | |
| Player-facing words | | `player-text` |
| A test | `src/test` (pure `main()`), `src/gametest` (register in `fabric.mod.json`) | `add-tests` |
| Build, test, deploy | `tools/ship.sh`, `tools/server/pdserver.mjs` | `deploy-test-server` |
| Scripted source edits | `tools/pyedit.py` (CRLF safe) | |
| Sub-agent prompts | | `agent-brief` |
