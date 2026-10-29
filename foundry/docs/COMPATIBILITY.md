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
| Custom item, base item look | Verified on a dev server | Registers, Polymer pack builds, `/foundry status` works. Not yet seen by a vanilla client |
| Custom item model and texture | Not verified | `PolymerItem.getPolymerItemModel` exists in the resolved jar; asset injection API still to be confirmed in task F1 |
| Recipes | Not verified | |
| Furniture | Not verified | Needs D3 |
| Custom blocks | Not verified | Needs D4; expect a hard cap on distinct blocks |
| Armor appearance | Not verified | Depends on 26.2 equipment asset support |
| Sounds, glyph fonts | Not verified | Pack generation only; fonts need a text replacement strategy |

Do not move a row to "Verified" until a test or a vanilla client run proves it.

## Known runtime notes

- The dev server prints `No key layers in MapLike[{}]` while creating a fresh flat world. Seen with and without
  Foundry content loaded. It looks like vanilla world creation noise but was not checked without Foundry (task F0).
- The Windows ship launcher heap workaround in the root `CLAUDE.md` applies to Foundry too.
