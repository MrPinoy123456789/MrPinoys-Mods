# Tasks

## Status (updated 2026-10-29, after Claude built part of the list on branch feature/foundry-phase0)

| Task | State |
|---|---|
| F0 | Done. Empty-folder boot confirmed `No key layers` is vanilla world-gen noise (COMPATIBILITY.md). 11 gametests pass |
| F1 | Done. Autohost settings in docs/PACK_HOSTING.md; `demo:wizard_staff` is the hand-written `model` demo; missing model-referenced textures are errors |
| F2 | Done. D8 resolved without a Mixin: generated datapack under `config/foundry/datapack` served through a builtin pack; recipes reload live |
| F3 | Done. Target selector `self`/`hit` ships; `add-foundry-mechanic` skill written |
| F4 | Decision record approved by the owner 2026-10-29 (block-bound, solid by default, cardinal facing). Phase B is open, see below |
| F5 | Done. `/foundry pack build` on a worker thread, last-good retention, incremental skip on unchanged input hashes |
| F6 | Done. `/foundry rename` rewrites references with backups and refuses collisions; orphan ledger live; decode experiment recorded in D5 |
| F7 | Done. `add-foundry-item`, `add-foundry-mechanic`, `add-foundry-recipe` skills; examples cover items, recipes and both asset kinds |
| F8 | Not started (needs its own decision records) |

The sections below are the original task text; where the table says done, treat the section as the acceptance list to
re-check, not as work to repeat.

Each task: goal, exact behaviour, files, tests, acceptance. Branch `devin/<id>-<topic>` from the integration tip.
Later tasks may depend on earlier ones; say which branch you stacked on in the report. Every task ends with
`./gradlew.bat build` green and the numbers quoted.

## F0: baseline checks (small)

- Run `coreTest` and `build`; record the check count.
- Boot `runServer` with `examples/content` copied into `run/config/foundry/content`. Confirm `/foundry status`.
- Boot once WITHOUT the Foundry jar's content (empty content folder) and note whether `No key layers in MapLike[{}]`
  still appears; update the note in `docs/COMPATIBILITY.md`.
- Add the missing gametest scaffold: `src/gametest/java/foundry/gametest/` with one test that boots a server, loads a
  temp content folder with the demo item, and asserts the item is registered and `/foundry give` yields a stack with
  the right name. Register it in `src/gametest/resources/fabric.mod.json`. Follow the `add-tests` skill pattern
  (it is written for Pocket Dungeons but the mechanics are identical).
- Acceptance: gametest count goes from 0 to at least 1 and passes.

## F1: custom models and textures end to end

Goal: an item with a `model` field shows a custom model to a vanilla client.

- Asset source: `config/foundry/assets/<namespace>/textures/item/*.png` and `.../models/item/*.json`. Decide and
  document the folder layout in `docs/ARCHITECTURE.md`; keep authored assets apart from generated output.
- Use Polymer's resource pack builder to inject them. Verify the real API in the resolved jars
  (`polymer-resource-pack-0.17.5+26.2.jar`) with `javap` before writing code; do not guess. Record the calls you
  used in `docs/COMPATIBILITY.md`.
- The client item definition (`assets/<ns>/items/<name>.json`) must be generated per item with a model, so the
  `model` id in the definition resolves. Generation is deterministic; same inputs give the same bytes.
- Replace the `id -> true` asset predicate in `FoundryMod.loadContent` with a real check against the asset folder.
  A missing model is a WARNING today; make a missing texture referenced by a model an ERROR with a fix.
- Add `examples/content/` and `examples/assets/` for a second demo item with a texture (draw a simple 16 by 16 PNG
  in code or generate it in a test fixture).
- Pack delivery: Polymer autohost config is the intended path. Document exactly what the owner must set so the
  Kinetic test server serves the pack (public URL and hash). Do not edit server config yourself.
- Tests: pure tests for the asset predicate and deterministic generation; a gametest that the generated pack zip
  contains the expected entries.
- Acceptance: pack zip contains the model, texture and item definition; a written manual procedure for the owner
  to confirm in a vanilla client (join, `/foundry give @s demo:glow_gem`, see the model). Mark the client check
  "not verified" unless you could actually run a client.

## F2: recipes

- New definition type `recipe`: `shaped` and `shapeless`, ingredients as vanilla or Foundry ids, result id and count,
  optional recipe group. Schema in `schema/recipe.v1.schema.json`; parser and validator in `foundry.core`.
- Validate: ingredient and result ids resolve (vanilla via predicate, Foundry via the loaded set), shaped patterns
  are rectangular and every key is used, no duplicate recipe id.
- Register through the supported recipe path for 26.2 (inspect before choosing). Must NOT clear or replace vanilla
  or other mods' recipes on reload; a gametest asserts that a vanilla recipe still exists after a Foundry reload.
- Reload: recipes reload without a restart. Failed reload keeps the previous recipes.
- Skill: add `.claude/skills/add-foundry-recipe/SKILL.md` in the same style as `add-foundry-item`.
- Acceptance: tests for each validation rule, a gametest crafting a Foundry item, count before and after.

## F3: mechanics engine

- Pure `foundry.core.mechanics`: a closed registry of trigger types, condition types and action types. A mechanic is
  trigger, ordered conditions, ordered actions, optional cooldown, optional probability. Definitions reference types
  by name only; unknown names are validation errors with a list of valid names as the fix.
- Safety limits, enforced and tested: max actions per mechanic, max nesting depth, max executions per player per
  second, no recursive trigger chains. Exceeding a limit stops that mechanic and logs once, never hangs the tick.
- Triggers to implement first: `on_use`, `on_hit_entity`. Actions: `play_sound`, `apply_effect`, `give_item`,
  `run_cooldown`. Conditions: `cooldown_ready`, `has_permission`. Each is one class plus a parameter validator.
- Hook `on_use` and `on_hit_entity` into `FoundryItem` using supported Item overrides (`use`, `hurtEnemy` or the
  26.2 equivalents; check the real signatures). Mechanics execute on the server thread only.
- Extend `ItemDef` with an optional `mechanics` list and bump nothing: it is additive. Update the schema file, the
  parser, `CoreSuite`, and the sample item.
- Skill: `.claude/skills/add-foundry-mechanic/SKILL.md` covering when Java is needed (a new type) versus JSON only.
- Acceptance: pure tests for every limit and every validator error; a gametest where using the demo sword plays the
  sound and applies the effect; the cooldown blocks a second use.

## F4 Phase B: build furniture (approved, start here)

Read the "Owner decisions" section of `docs/FURNITURE_DECISION.md` first. Build a `furniture` definition type
(schema, parser, validator, skill, example) and the runtime for it:
- Definition: id, display name, item or block model reference (reuse the `texture` and `model` rules), interaction box
  size, `solid` (default true), optional mechanic ids on click, drops. Rotation is a cardinal facing only.
- Runtime: placement through an item (a placeable Foundry item), a real block underneath for collision, an
  `ElementHolder` with display and interaction elements, `BlockBoundAttachment` for cleanup, a level `SavedData`
  store as the single source of truth, respawn on chunk load with the duplicate check from the record.
- Breaking removes the block, the holder and the record; drops follow the definition. Anything vanilla blocks do
  (pistons, water, explosions) stays whatever vanilla does; do not add behaviour.
- Tests: pure parser and validator checks; gametests for place, break, restart recovery (reload the store and rebuild
  holders), no duplicates after a double chunk load. Owner client check procedure written in the report.
- Do not add free-floating placement, arbitrary rotation, or special cases such as stairs or armor stands.

Original phase text (kept for reference):

## F4: furniture (decision record first, then stop)

- Phase A: write `docs/FURNITURE_DECISION.md` (D3). Verify against `polymer-virtual-entity` in the resolved jar:
  how display and interaction entities are created, tracked and removed; how collision is done; how a logical
  object id is persisted (world saved data) with entity UUIDs as caches only; what happens on chunk unload, crash
  and restart; duplicate prevention. Include a minimal prototype result or the exact blocker.
- STOP. Do not write furniture code until the owner approves the record. Report and wait.

## F5: reload and pack lifecycle

- `/foundry reload`: parse into a candidate ContentSet, validate fully, swap atomically only on success, keep the
  previous set on failure, and print exactly what changed and what needs a restart.
- `/foundry pack build` and `/foundry pack status`: build on a worker thread, never the server thread; report hash,
  size, last success time, last failure reason; keep the last good build when a new one fails.
- Incremental: do not rebuild when inputs are unchanged (hash of sorted inputs).
- Acceptance: tests for swap-on-success and keep-on-failure; gametest that a broken file leaves the old set live.

## F6: rename, delete and orphans

- `/foundry rename <old> <new>`: updates references in other definitions, refuses if the new id exists, writes a
  backup of every touched file first. Pure logic in core, file IO behind an interface so it is testable.
- Implement D5 orphan ledger after verifying what the server does with a stack of an unregistered id.
- Acceptance: tests for reference rewriting and refusal cases; written result of the orphan experiment.

## F7: skills and example pack

- Finish `.claude/skills/add-foundry-*` for each shipped type; each skill names the schema, an example file, the
  validate command and the in-game check. Keep each under 120 lines and link to the schema rather than copying it.
- Add `examples/content/` covering every shipped type with a README line per example.

## F8 and later (do not start without the owner)

Sounds, glyph fonts, armor, custom blocks (D4), paintings, menus (needs `sgui`). Each begins with a decision record
and an owner approval, like F4.
