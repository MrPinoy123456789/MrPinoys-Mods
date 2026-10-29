# Foundry decisions

Dated records. Newer entries may supersede older ones; say so in the entry.

## D1 (2026-10-29): authoring is skills plus a validator, not an editor

Status: approved by the owner. A web editor ("Studio") is deferred. Claude Code skills write definition files, a
validator reports problems in plain language, and `/foundry give` is the preview. Keep the content format and the
validator editor-agnostic so an editor can be added later without changing either.

## D2 (2026-10-29): Polymer bundled, built against, never shaded

Target: Minecraft 26.2, Fabric Loader 0.19.3, Fabric API 0.156.0+26.2, Java 25, `polymer-bundled 0.17.5+26.2`
(the version on the owner's server). Foundry compiles against it and declares `polymer-core >=0.17.5` in
`fabric.mod.json`. Polymer 0.18.x targets 26.3 and is not used. SGUI is not in the bundle; add `sgui` only when
menus are built (task F8).

## D3 (open): furniture representation

Proposed, not approved: display entities for visuals, an interaction entity for clicks, and a barrier or light block
where collision is needed. A logical furniture object has its own stable id stored in world persistence; entity UUIDs
are only caches. Task F4 starts with a written record that verifies this against `polymer-virtual-entity` and covers
chunk unload, crash recovery and duplicate prevention. No furniture code before the owner approves that record.

## D4 (open): custom blocks

Proposed: not in the first release. Polymer blocks draw from a finite pool of vanilla block states, which caps the
count and constrains mining speed, sounds and redstone. Needs a record comparing registered Polymer blocks, vanilla
blocks with pack models, and display-entity blocks. Owner decides after reading it.

## D5 (proposed): removed or renamed definitions

Never delete data silently. If a definition is missing at startup, keep the id in a `foundry/orphans.json` ledger,
log it once with the file it used to come from, and let the server start. Item stacks of a missing id are handled by
vanilla registry sync behaviour; verify what actually happens before promising recovery (task F6).

## D6 (2026-10-29): no Mixins, no packet interception

Everything so far uses Polymer and Fabric API. A Mixin needs a written justification, a flag in the handoff report,
and owner approval. The same applies to NMS-style internals.

## D8 (2026-10-29, open): how Foundry recipes reach the recipe manager

Finding: in 26.2 `RecipeManager` fills itself from data pack JSON during reload, and its `apply` and `prepare` are
protected; there is no public call to add a recipe, and Fabric's recipe API only adds ingredient types. Without a
Mixin the only paths found are data-pack based:
1. Write vanilla-format recipe JSON into a data pack that is enabled for the world (a `datapacks/` folder pack needs
   `/datapack enable` per world; unreliable for a hands-off flow).
2. Ship recipes inside a built-in pack via `ResourceLoader.registerBuiltinPack`, which reads from the mod jar, so it
   is static, not generated from `config/foundry`.
3. A Mixin or accessor on `RecipeManager` (forbidden by D6 without owner approval).
Not yet tried: supplying a pack from memory through the `fabric-resource-loader-v1` internals; read its sources
before choosing. Next step for whoever takes F2: try that, record the result, and ask the owner before using a Mixin.

## D7 (2026-10-29): name

The mod is called Foundry (`foundry/`, jar `MrPinoys_Foundry`). It must not be called NexoCraft. The owner may still
rename it; if so the change is a find and replace of the id, package and folder done by Claude, not by Devin.
