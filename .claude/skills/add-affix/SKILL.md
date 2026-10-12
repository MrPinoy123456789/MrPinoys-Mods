---
name: add-affix
description: Add a Pocket Dungeons affix, or a new closed-set affix operation (a field in AffixEffects). Covers the files in order (AffixEffects, AffixMeta, AffixIds, the dungeon_affix JSON, AffixManifest validation, the runtime reader), the frozen operation order, the symmetry rule for incompatible, and the test. Use for Blackout style work, a new affix, or a new effect field.
---

# Adding an affix

Work in `pocketdungeons`. Read `AffixEffects` (the javadoc lists the frozen operation order), `AffixMeta` (JSON parse),
`AffixManifest` (load gates), `AffixIds` (constants). Model to copy: Blackout (`dark_cells`, `BlackoutTest`).

## First decide: new affix, or new operation?
- An affix that only recombines existing operations (`ominous`, `trial_count_multiplier`, `cooldown_factor`,
  `player_range`, `consumable_rule`, `neutral_wolf_spawn`, `hazard_kind` + `hazards_per_cell`, `voided_floor`,
  `bonus_tool_pool`, `decor_pool`, `undead_rise_chance`, `dark_cells`) needs only steps 4 to 6.
- A new behaviour needs a new operation field first (steps 1 to 3). The set is closed on purpose: an affix whose
  `effects` object sets nothing the parser reads is rejected at load ("declares no operation").

## Order of operations
1. **`AffixEffects`**: add a `final` field with its default (the no-op value), a javadoc line with the bound, bind it
   in the private constructor and in `none()`, and add a `withX(value)` method that range checks and throws
   `IllegalArgumentException("x must be in [a, b]")`, copying every other field (see `withDarkCells`). Add it to
   `hasAnyOperation()` (its non-default test). Add one line to the class javadoc's numbered operation list: that
   order is frozen so a seed stamps the same floor; new operations go at the END.
2. **`AffixMeta.parseEffects`**: read the JSON key (`doubleOr` / `intOr` / `boolOr` / `enumOr`, snake_case key) and
   chain the `withX` call. Unknown keys are ignored by Gson, so a typo in the JSON fails the "no operation" gate,
   not a parse error: test the real file (step 6).
3. **Runtime reader**: the code that acts on it reads `def.effects.x` where the cell is stamped or the event happens.
   Keep Minecraft types out of `AffixEffects`/`AffixMath` (they are pure Java for the plain-javac tests); put the
   decision in a static pure method (like `DungeonLight.blackoutCells`) and call it from the game side.
4. **`AffixIds`**: new affix only. Add `static final String NAME = "pocketdungeons:name";`, append it to
   `BUILT_IN_ORDER` (last; the manifest coverage gate and `ContentSnapshot` compare against this list, and the
   order is the render and seed order) and add `map.put("name", NAME)` to the legacy bridge in `buildLegacy`.
   Add its colour case in `Keystone` (the switch on the ids) so the name renders.
5. **JSON** `src/main/resources/data/pocketdungeons/dungeon_affix/<name>.json`, one line, like `restless.json`:
   `version` 1, `label`, `short_name` (1 to 5 characters, only read when the label is over 5), `blurb` (player text:
   follow `player-text`), `order`, `min_level`, `weight`, `depletion_multiplier` (1 or 2), `incompatible` (list of
   ids or bare names), `effects`. The file name is the id.
6. **Validation and symmetry**: `AffixManifest` rejects at load a missing built-in, an unknown loot table, an
   `incompatible` entry naming an unknown affix, and a ONE-SIDED `incompatible` (A lists B, B must list A). So if you
   list another affix, edit its JSON too. A rejection shows as `Loaded N dungeon affixes (M rejected)`; M must be 0.
7. **Test**: a pure `FooTest` that reads the real JSON with `AffixMeta.fromJson(obj, AffixIds.NAME)` (see
   `BlackoutTest`), checks the bounds throw, the default is a no-op, `hasAnyOperation()` is true, and every
   `incompatible` partner lists this affix back. Scaffold the wiring per `add-tests` (two places in
   `build.gradle.kts`). If a count of affixes is pinned (`ConfigCharacterization` is not; grep `AffixMathTest`,
   `DungeonDefTest`, gametest `Loaded 10 dungeon affixes`) update it. Then `tools/ship.sh --pure-fast`, and
   `tools/ship.sh --only` for a gametest of the runtime reader.
8. Report a live check fragment (`python tools/livecheck.py new <id> "<title>"`) when the effect is felt in play.
