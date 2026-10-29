# Foundry

A server-side Fabric mod for Minecraft 26.2. Content creators describe custom items (later furniture, recipes,
sounds and more) in JSON files; the mod registers them through Polymer so vanilla clients can use them with only a
resource pack. No client mod, no Java per item. There is no editor UI: Claude Code skills and a validator are the
authoring interface.

Status: Phase 0 plus tasks F0, F1, F2, F3 and F5 of the handoff. Items with textures, models and mechanics work on a
dev server and in gametests; crafting recipes load through a generated datapack. A local vanilla client has used the
pack and items once; broader client and remote verification is open (see "What is not verified").

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

`config/foundry/content/<namespace>/items/<name>.json` for items, `config/foundry/content/<namespace>/recipes/<name>.json`
for crafting recipes. One file per definition. Field lists: `schema/item.v1.schema.json`, `schema/recipe.v1.schema.json`.
Recipes are written to a generated datapack at `config/foundry/datapack/` and reach the recipe book through a server
data reload (see `docs/DECISIONS.md` D8).

## Commands (operators)

`/foundry help`, `status`, `validate`, `reload`, `pack status`, `pack build`, `rename <old> <new>`,
`give <player> <id> [count]`.

## Build and test

```bash
./gradlew.bat build        # compile, pure tests, jar
./gradlew.bat coreTest     # pure tests only
./gradlew.bat runServer    # dev server; needs run/eula.txt with eula=true
```

The jar is `build/libs/MrPinoys_Foundry-0.1.0.jar`. The server must also have `polymer-bundled 0.17.5+26.2`;
Foundry never shades it.

## What works (verified 2026-10-29)

- `coreTest`: 92 checks pass (ids, parsing, validation, pack generation, every mechanics limit, recipes, renames,
  the orphan ledger, pack-input hashing).
- `runGameTest`: 11 gametests pass (registration, name, rarity, stack size, durability, texture and hand-written
  model items, mechanics through `use` and `hurtEnemy` including the hit-entity target, recipes served through the
  generated datapack and assembling to a Foundry item, an unregistered-id stack failing strict decode, a broken file
  refusing `/foundry reload` and keeping the old content).
- Dev server: `Foundry: 3 items registered, 0 problems`; `polymer/resource_pack.zip` holds the generated client item
  definition, the generated model and the texture; `/foundry pack status` and `/foundry reload` answer.

## Verified by the owner in a vanilla client (2026-10-29)

On a local dev server (`runServer`, offline mode) with an unmodded 26.2 client: the pack loaded, `/foundry give` of
`demo:glow_gem` and `demo:crystal_sword` worked, and the owner reported that it works. The report was a single "it works";
the exact look of each item and the sound were not itemised. Not tried: the Kinetic test server, a remote client over
the public address, or an upgrade over an existing world.

## What is not verified

- Furniture (F4) waits for decision D3. Rename/orphan handling (F6) is not built.
- The pack is still only rebuilt on demand: `/foundry pack build` or `/polymer generate-pack`. Nothing watches the
  files. Whether a client that is already connected picks up the rebuilt pack without a command depends on the
  Polymer autohost provider and is not verified.
