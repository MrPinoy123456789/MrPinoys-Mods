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
| Custom item, base item look | Verified in gametests and on a dev server | Registers with name, rarity, stack size, durability. Owner confirmed in a vanilla client on a local dev server |
| Custom item model and texture | Pack contents verified; owner reported it works in a vanilla client on a local dev server (2026-10-29) | `RESOURCE_PACK_CREATION_EVENT` plus `ResourcePackBuilder.addData` and `addStringData` (checked with javap); `getPolymerItemModel` returns the item id, whose client item definition Foundry generates |
| Item mechanics (on_use, on_hit_entity) | Verified in gametest | `Item.use` and `Item.hurtEnemy` overrides on `FoundryItem` |
| Left click (on_swing) | Verified in gametest, not in a client | No item swing hook exists in 26.2 and Foundry uses no Mixins, so `Swings` watches `ServerPlayer.swinging` each tick and ignores swings caused by right click, hitting an entity or starting to mine |
| Bolts (launch_bolt, on_bolt_hit) | Verified in gametest, not in a client | Server-side stepped point with particles, no entity; `Bolts` |
| Permission filters | Verified in gametest with an injected check | `Permission.Atom` on vanilla's `PermissionSet`; whether LuckPerms feeds it on 26.2 is unchecked |
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

- The dev server prints `No key layers in MapLike[{}]` on a worker thread while creating a fresh flat world.
  Checked on 2026-10-29 with an empty content folder (0 items, 0 problems): the line still appears, so it is
  vanilla world generation noise, not Foundry content. It does not appear on later boots that reuse the world.
- The Windows ship launcher heap workaround in the root `CLAUDE.md` applies to Foundry too.
