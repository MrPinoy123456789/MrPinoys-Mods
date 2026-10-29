# Foundry

A server-side Fabric mod for Minecraft 26.2. Content creators describe custom items (later furniture, recipes,
sounds and more) in JSON files; the mod registers them through Polymer so vanilla clients can use them with only a
resource pack. No client mod, no Java per item. There is no editor UI: Claude Code skills and a validator are the
authoring interface.

Status: Phase 0 plus tasks F0, F1, F3 and F5 of the handoff. Items with textures, models and mechanics work on a
dev server and in gametests; nothing has been seen by a vanilla client yet (see "What is not verified").

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

`/foundry help`, `status`, `validate`, `reload`, `pack status`, `give <player> <id> [count]`.

## Build and test

```bash
./gradlew.bat build        # compile, pure tests, jar
./gradlew.bat coreTest     # pure tests only
./gradlew.bat runServer    # dev server; needs run/eula.txt with eula=true
```

The jar is `build/libs/MrPinoys_Foundry-0.1.0.jar`. The server must also have `polymer-bundled 0.17.5+26.2`;
Foundry never shades it.

## What works (verified 2026-10-29)

- `coreTest`: 50 checks pass (ids, parsing, validation, pack generation, every mechanics limit).
- `runGameTest`: 6 gametests pass (registration, name, rarity, stack size, durability, a texture item, the sword
  mechanic through `Item.use` with its cooldown, a broken file refusing `/foundry reload` and keeping the old content).
- Dev server: `Foundry: 2 items registered, 0 problems`; `polymer/resource_pack.zip` holds the generated client item
  definition, the generated model and the texture; `/foundry pack status` and `/foundry reload` answer.

## Verified by the owner in a vanilla client (2026-10-29)

On a local dev server (`runServer`, offline mode) with an unmodded 26.2 client: the pack loaded, `/foundry give` of
`demo:glow_gem` and `demo:crystal_sword` worked, and the owner reported that it works. The report was a single "it works";
the exact look of each item and the sound were not itemised. Not tried: the Kinetic test server, a remote client over
the public address, or an upgrade over an existing world.

## What is not verified

- Recipes (F2) are blocked on a decision, see DECISIONS.md D8. Furniture (F4) waits for D3.
- Effects and sounds apply to the acting player only; there is no target selector, so "slow the enemy you hit" is not
  possible yet. The pack is built at server start; there is no runtime rebuild.
