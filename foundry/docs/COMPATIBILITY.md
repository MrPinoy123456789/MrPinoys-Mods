# Foundry compatibility

## Version matrix (pinned)

| Component | Version | Source |
|---|---|---|
| Minecraft | 26.2 | workspace standard |
| Fabric Loader | 0.19.3 | workspace standard |
| Fabric API | 0.156.0+26.2 | workspace standard |
| Fabric Loom | 1.17-SNAPSHOT (`net.fabricmc.fabric-loom`) | copied from Lemon |
| Java | 25 | workspace standard |
| polymer-bundled | 0.17.5+26.2 | owner's server; Nucleoid maven `https://maven.nucleoid.xyz` |

The bundle contains autohost, blocks, common, core, networking, registry-sync-manipulator, resource-pack,
resource-pack-extras, sound-patcher and virtual-entity. It does not contain SGUI.

## Feature feasibility (what is known, what is not)

| Feature | State | Notes |
|---|---|---|
| Custom item, base item look | Verified in gametests and on a dev server | Registers with name, rarity, stack size, durability. Not yet seen by a vanilla client |
| Custom item model and texture | Pack contents verified; client rendering not verified | `RESOURCE_PACK_CREATION_EVENT` plus `ResourcePackBuilder.addData` and `addStringData` (checked with javap); `getPolymerItemModel` returns the item id, whose client item definition Foundry generates |
| Item mechanics (on_use, on_hit_entity) | Verified in gametest | `Item.use` and `Item.hurtEnemy` overrides on `FoundryItem`; effects and sounds target the acting player only |
| Recipes | Blocked | No public add path found without a Mixin; DECISIONS.md D8 |
| Furniture | Not verified | Needs D3 |
| Custom blocks | Not verified | Needs D4; expect a hard cap on distinct blocks |
| Armor appearance | Not verified | Depends on 26.2 equipment asset support |
| Sounds, glyph fonts | Not verified | Pack generation only; fonts need a text replacement strategy |

Do not move a row to "Verified" until a test or a vanilla client run proves it.

## Findings worth keeping

- `Item.Properties.component(ITEM_NAME, ...)` is silently replaced by the translation key in the `Item` constructor.
  Foundry sets names through `DefaultItemComponentEvents.MODIFY` instead (caught by a gametest).
- Polymer's built pack zip has fixed 1969 timestamps, so identical inputs give identical zips.

## Known runtime notes

- The dev server prints `No key layers in MapLike[{}]` while creating a fresh flat world. Seen only with Foundry
  loaded. It looks like vanilla world creation noise but was not checked without Foundry (still open from F0).
- The Windows ship launcher heap workaround in the root `CLAUDE.md` applies to Foundry too.
