# M1 — Themes foundation

> Roadmap: `../ROADMAP.md` · Why: `../VISION.md` §5.1–5.3 · Status: `../PROGRESS.md`

**Goal:** 14 templates × N processor lists instead of 140 hand-authored `.nbt`
files. After this, a theme is datapack JSON and needs no recompile.

**Blocked on:** M0 (only for iteration comfort). **Blocks:** M7, all content work.

---

## T1.1 — Wire `processors` — ⚠ a draft is already in the working tree

**Uncommitted work exists** touching `TemplateStamper`, `LayoutStamper`,
`RoomManifest` and `DungeonRoomMeta`. Read it before writing anything. It builds
and the test suite passes; no room references a processor list, so it is inert.

What the draft does:

1. `TemplateStamper.place(...)` gains a 7-arg overload taking an `Identifier
   processorList`; the 6-arg form delegates with `null`, so the three other
   callers (`DungeonCommands:469`, `Instances:867`, `Instances:1132`) are
   untouched.
2. Resolution is `level.registryAccess().lookupOrThrow(Registries.PROCESSOR_LIST)
   .getValue(id)`, then `settings.addProcessor(...)` for each.
3. `LayoutStamper` passes `entry.meta.processors`.
4. `RoomManifest.load` rejects a room whose processor list does not resolve,
   alongside the existing "template not found" rejection.
5. `DungeonRoomMeta.stringOrNull` now treats blank as absent.

**Verified against the 26.2 jar:**
`Registries.PROCESSOR_LIST` is
`ResourceKey<Registry<StructureProcessorList>>`; `StructureProcessorList.list()`
returns `List<StructureProcessor>`; `StructurePlaceSettings.addProcessor` exists.

**Ordering decision to preserve:** theme processors are added **after**
`JigsawReplacementProcessor.INSTANCE`, so a theme's rules see each jigsaw's
`final_state` rather than the jigsaw block. Reversing it leaves jigsaw-authored
blocks untinted and visibly patchy around every doorway.

⚠ **Known gap, document it:** `JigsawFallback.replaceRemaining` writes final
states **straight to the world after placement**, bypassing processors entirely.
It only fires for jigsaws the vanilla processor missed, so the patchiness is
confined to a path that should not be taken — but if themes ever look wrong at
doorways, this is the first suspect.

---

## T1.2 — `theme` on `DungeonRoomMeta`

Parse symmetrically with `roles`: a string array, but **optional** with an empty
default, because every existing room omits it.

Add to `Entry`, and filter in `RoomSelector` the same way `queryAnyRotation`
already filters on `role` — one extra `continue` against the requested theme,
with "no theme requested" matching everything.

**Hazard:** `RoomSelector.validate` guarantees reachability. Filtering the pool by
theme shrinks it, and a theme that does not cover all 53 (mask, role)
combinations will make planning fail. **Mitigation:** themes are *processor
lists* applied to shared templates, so the pool does not shrink at all unless an
author writes theme-exclusive rooms. Add a planner rejection message that names
the theme, so the failure is legible when it does happen.

---

## T1.3 — Three proof themes

`data/pocketdungeons/worldgen/processor_list/theme_{deepslate,prismarine,blackstone}.json`,
each a `minecraft:rule` processor rewriting the shell palette:

| From | Deepslate | Prismarine | Blackstone |
|---|---|---|---|
| `stone_bricks` | `deepslate_bricks` | `prismarine_bricks` | `polished_blackstone_bricks` |
| `polished_andesite` | `polished_deepslate` | `dark_prismarine` | `blackstone` |
| `sea_lantern` | `sea_lantern` | `sea_lantern` | `shroomlight` |

Keep `sea_lantern` where the light level matters — the dimension is
`ambient_light: 0.0` and swapping every light source is how a theme becomes
unplayable rather than atmospheric.

**Tier alignment:** `VISION.md` §3.6.1 wants palettes to tier by depth —
deepslate/prismarine are tier 2, blackstone reads tier 3. Wire that in M6 with
the loot pass, not here; here they are proof the mechanism works.

---

## Done when

- [x] Adding `"processors": "pocketdungeons:theme_deepslate"` to one
      `dungeon_room` json and running `/reload` changes that room's palette
- [x] A bad processor-list id is **rejected at manifest load**, named in
      `rejections()`, not silently ignored at stamp time
- [x] Three themes produce three visibly different dungeons from the same 14
      `.nbt` files
- [x] No recompile was needed for any of the above
- [x] `./gradlew build` green
