# Foundry

A server-side Fabric mod for Minecraft 26.2. Content creators describe custom items (later furniture, recipes,
sounds and more) in JSON files; the mod registers them through Polymer so vanilla clients can use them with only a
resource pack. No client mod, no Java per item. There is no editor UI: Claude Code skills and a validator are the
authoring interface.

Status: Phase 0 scaffold. One item type works end to end on a dev server (see "What works").

## Layout

| Path | What |
|---|---|
| `src/main/java/foundry/core/` | Pure JDK: ids, definitions, parser, validator, loader. No Minecraft imports, so tests run without a server |
| `src/main/java/foundry/mc/` | Minecraft and Polymer integration: entrypoint, item class, registration, commands |
| `src/test/java/foundry/CoreSuite.java` | Pure tests, `main()` style like the other mods |
| `schema/` | JSON Schema for each definition type |
| `examples/content/` | Sample content; copy to `config/foundry/content/` to try it |
| `docs/` | Architecture, decisions, compatibility, and the Devin handoff pack in `docs/devin/` |

## Content on disk

`config/foundry/content/<namespace>/items/<name>.json`. One file per definition. Field list: `schema/item.v1.schema.json`.

## Commands (operators)

`/foundry help`, `/foundry status`, `/foundry validate`, `/foundry give <player> <id> [count]`.

## Build and test

```bash
./gradlew.bat build        # compile, pure tests, jar
./gradlew.bat coreTest     # pure tests only
./gradlew.bat runServer    # dev server; needs run/eula.txt with eula=true
```

The jar is `build/libs/MrPinoys_Foundry-0.1.0.jar`. The server must also have `polymer-bundled 0.17.5+26.2`;
Foundry never shades it.

## What works (verified 2026-10-29)

- `coreTest`: 18 checks pass.
- Dev server boots with the demo item, logs `Foundry: 1 items registered, 0 problems`, Polymer builds
  `polymer/resource_pack.zip`, and `/foundry status` answers.

## What is not verified

- Nothing has been tested with a vanilla client yet: whether the demo item shows, whether `/foundry give` yields a
  usable stack, whether a custom `model` renders. First job for the handoff (task F1).
- No custom models, textures, recipes, furniture or mechanics exist yet.
