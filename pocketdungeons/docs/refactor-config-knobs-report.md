# Refactor report: one source of truth per config knob

Branch: `refactor/config-knobs` (off `feature/bug-testing-and-refinement`). Three commits, then this report.

## What changed

`PocketDungeonsConfig` no longer states each default four times. Every setting is one `Knob<T>` constant;
loading, the reset to defaults, the first-boot file and the stored value all walk one list (`KNOBS`).

Descriptor shape:

```java
static final class Knob<T> {
    String key; T defaultValue;
    Function<JsonObject, T> reader;   // the old readInt/readDouble/readBoolean/readString call, closed over key, default, validator, message
    Function<T, JsonElement> writer;  // how the default is written to a new file
    // optional: .normalising(v -> v')  .after(Runnable)  .early()  .withoutAccessor()  .publishing(Consumer<T>)
}
private static final Knob<Integer> SLOT_PITCH = integer("slotPitch", 2048, v -> v > 0 && v % 16 == 0, "must be > 0 and a multiple of 16");
public static int slotPitch() { return SLOT_PITCH.get(); }
```

Factories: `integer`, `decimal`, `flag`, `text(key, default, allowEmpty)`. The private `readInt`, `readDouble`,
`readBoolean`, `readString` helpers are untouched, so every log message is byte for byte what it was.

- 103 knobs (102 after the merge removed `salvageEmeraldsPerTier`), in the old `defaultsJson` order (the written key order). All 102 public accessors keep their names and
  signatures; the 103rd is `modules`, which never had one.
- `trimBonuses` and `powerBonuses` are descriptors with their own reader and writer (`readTrimBonuses`,
  `readPowerBonuses`, unchanged). No behaviour change, so they were converted.
- `modules` is a descriptor marked `.early()` (read before every other knob, as before), `.withoutAccessor()`, and
  `.publishing(ContentModules::setOverrides)`, so the reset and the load still hand the map to `ContentModules`.
- The `pathLengthMax < pathLengthMin` raise is an `.after(...)` hook on `pathLengthMax`; the grid fit clamp is an
  `.after(...)` hook on `maxGridSpan`. The `rerollBlock` smithing table and `storageBlock` copper chest remaps are
  `.normalising(...)` on their own knobs. Same code and messages, moved next to the knob they belong to.
- `applyDefaults`, `apply` and `defaultsJson` are now loops. `effectiveForSave`, `load`, `setModuleOverride`,
  `RETIRED_KEYS` and the on-disk format are unchanged.

Adding a knob is now one declaration plus one one-line accessor named after the key. The accessor is the only
second line and `ConfigKnobsTest` fails if it is missing or the wrong type. (A fully one-line change is not possible
while accessors must stay real methods; the 49 callers use them.)

## One deliberate, invisible difference

Scalar knobs are now read in written-file order rather than the old read order (which differed from the file order in a few
places, for example `clearBlocksPerTick` and `floorsPerSafeVisit`). Values are identical; the only effect is the order of
log lines between two different bad keys in the same file. The two path length clamps run right after the knob they hang
off instead of mid sequence. No fixture is sensitive to any of this, and none changed.

## How the old files are proved to load identically

`ConfigCharacterizationTest` was committed first, against the old code, with fixtures in
`src/test/resources/config-fixtures`; the refactor commit passes it with the fixtures untouched.

- `defaults-accessors.txt`: every public accessor's default value, plus the module overrides.
- `defaults-written.json`: the exact text `load(emptyDir)` writes (key order and number formatting).
- `validators.txt`: for each of the 100 scalar knobs, a spread of probe values (27 for ints, 20 for doubles, 4 for strings,
  both booleans); which are accepted, what the accessor returns after each, the first rejection's WARN and ERROR log
  lines, any other knob the probe disturbed, and seven odd shapes per knob (string `"abc"`, string `"7"`, boolean, `1.5`,
  `[]`, `null`, `{}`) with the resulting value and log line.
- `all-nondefault.json` and `all-nondefault-accessors.txt`: a file with every knob at a valid non-default value (plus
  non-default `trimBonuses`, `powerBonuses`, `modules`) loads to those values and is left byte for byte unrewritten.
- `scenarios.txt`: rewrite of a partial file with a retired key, an unknown key and an invalid value kept as written; a
  complete file carrying retired and unknown keys; a complete file with nothing to change; a non-JSON file, an empty file
  and a non-object file (defaults apply, file untouched); both path length clamps; the `rerollBlock` and `storageBlock`
  remaps; empty `payoutCommand` allowed and empty `vaultKeyItem` rejected; `trimBonuses` and `powerBonuses` with a good and
  a malformed entry, none valid, empty, and not an array; module overrides with qualified, bare, bad and non-object
  values, and the overrides cleared by a file without `modules`; `setModuleOverride` add and clear (the module keys are
  sorted in the fixture because `ContentModules.overrides()` is a hash map and its write order is not pinned).

Log capture uses a log4j appender on the `PocketDungeons` logger. Fixtures are regenerated only with
`PD_CONFIG_REGEN=<dir>` set; the normal run compares. The run was repeated several times to confirm it is deterministic.

`ConfigKnobsTest` (new): keys unique; every knob is in the written defaults at its position with its default; every
knob has a public static accessor named after its key with the right primitive type returning the default; every public
accessor is backed by a knob; the source holds exactly as many `Knob` constants as the list and no hand read key or plain
static settings field. Mutation check: a knob added without an accessor made it fail with
`knob tempProbe has no public accessor named tempProbe()`.

`ConfigSaveTest` passes unchanged. `build.gradle.kts` gained two test tasks and their `dependsOn` lines (the only
file outside my set that I touched). `DefaultConfigFileTest` does not exist in this tree; there was nothing to extend.

## Merge with the main branch

Merged `feature/bug-testing-and-refinement` into this branch and ported its changes into the descriptor design:
`salvageEmeraldsPerTier` removed (knob, accessor and declaration; the key is in `RETIRED_KEYS`), `writeAtomically` and the
PD-200 rule that a failed rewrite keeps the applied values, and the regenerated `config/pocketdungeons.default.json` with
`DefaultConfigFileTest` (passes untouched: the written order did not change). The dimension routing touched no config code.
Fixtures changed only by deleting the `salvageEmeraldsPerTier` lines (20 deleted lines, no additions). The characterization
test gained a PD-200 check: with a directory sitting where the temp file goes, a partial file still applies its values and
is left as it was, and a normal rewrite leaves no temp file. There are now 102 knobs.

## Tests

Before: 241 required gametests passed, plus the pure suites. After the merge, at the branch tip: `./gradlew.bat test runGameTest` BUILD SUCCESSFUL, "All 241 required tests passed" (the new config tests are pure `main()` tasks under `test`, so the gametest count is unchanged), with `ConfigCharacterizationTest`, `ConfigKnobsTest`, `ConfigSaveTest` and `DefaultConfigFileTest` all passing.

Cannot be verified without a live server: nothing in this change touches the world; the one runtime path is boot-time
`PocketDungeonsConfig.load` on a real operator file, which the fixtures exercise.
