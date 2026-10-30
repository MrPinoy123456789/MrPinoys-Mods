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

## D3 (2026-10-29, approved): furniture representation

Approved by the owner: block-bound, solid by default, cardinal facing like vanilla blocks; no extra movement or
interaction behaviour beyond vanilla unless explicitly requested. Details and the build consequences are in
`docs/FURNITURE_DECISION.md`. The original proposal follows.

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

Verified 2026-10-29 (gametest `aStackOfAnUnregisteredIdFailsStrictDecode`): `ItemStack.CODEC` rejects an
unregistered id outright; the stack does not survive a strict decode, so held items of a removed definition are
dropped, not preserved. The ledger is therefore the only durable trace, and re-adding the definition cannot bring
dropped stacks back. Implemented: `config/foundry/orphans.json`, updated at startup and after each successful
`/foundry reload`, logging newly vanished ids once and removing ids that come back.

## D6 (2026-10-29): no Mixins, no packet interception

Everything so far uses Polymer and Fabric API. A Mixin needs a written justification, a flag in the handoff report,
and owner approval. The same applies to NMS-style internals.

## D8 (2026-10-29): how Foundry recipes reach the recipe manager

Finding: in 26.2 `RecipeManager` fills itself from data pack JSON during reload, and its `apply` and `prepare` are
protected; there is no public call to add a recipe, and Fabric's recipe API only adds ingredient types. Without a
Mixin the only paths found are data-pack based:
1. Write vanilla-format recipe JSON into a data pack that is enabled for the world (a `datapacks/` folder pack needs
   `/datapack enable` per world; unreliable for a hands-off flow).
2. Ship recipes inside a built-in pack via `ResourceLoader.registerBuiltinPack`, which reads from the mod jar, so it
   is static, not generated from `config/foundry`.
3. A Mixin or accessor on `RecipeManager` (forbidden by D6 without owner approval).
The untried option, in-memory packs through `fabric-resource-loader-v1`, works: `registerBuiltinPack` only asks for a
`ModContainer`, an api interface, and resolves `<rootPaths[0]>/<namespace>/<pack id>` as an NIO folder pack that is
re-read on every data reload. Resolution (2026-10-29): a small fake `ModContainer` whose root is
`config/foundry/datapack` gives Foundry an always-enabled builtin pack at
`config/foundry/datapack/resourcepacks/recipes/` (`registerBuiltinPack` resolves `resourcepacks/<id path>` inside each
container root) with a `pack.mcmeta` and generated recipe JSON. Content is regenerated on `/foundry reload`, then applied with the
same `server.reloadResources` call vanilla `/reload` uses. No Mixin, no access widener, no NMS internals; the fake
container implements only `fabric-loader` api interfaces, and if Fabric ever tightens this, the datapacks route in
option 1 is the fallback.

## D7 (2026-10-29): name

The mod is called Foundry (`foundry/`, jar `MrPinoys_Foundry`). It must not be called NexoCraft. The owner may still
rename it; if so the change is a find and replace of the id, package and folder done by Claude, not by Devin.

## D9 (2026-10-30): kingdom weapons, tiers and what they needed

Five weapons (one per race) each come in three tiers; every tier is its own item and a shapeless recipe turns the tier
below into the next. They are written by `tools/gen_weapons.py`, so rebalancing is a table edit and a rerun. Decisions
that went into it:
- Melee numbers are item fields (`attack_damage`, `attack_speed`) applied as attribute modifiers, because a custom
  item otherwise punches for 1 whatever its look.
- Armour piercing is vanilla `indirect_magic` damage, which bypasses armour in 26.2 data. A bonus after a swing lands
  inside the target's hit window, so `damage` has `ignore_hit_delay` to clear it first.
- Hold to charge follows the bow: the client sees a `consumable` component with the bow animation and a very long time,
  so it never finishes by itself; the server decides at release. The mechanic is armed when the hold starts, so
  letting go of sneak halfway changes nothing. Server item keeps no consumable component, or vanilla would eat it.
- The thrown trident is a returning bolt, not a vanilla trident with Loyalty: a Polymer stack inside a vanilla
  entity cannot be tested here, and a bolt can carry the soak effect on every hit. It homes on the caster, passes
  through blocks, and hits entities on the way back that it has not already struck. The target it just hit is not struck
  again, so "twice on one target" is not a thing.
- Recipes unlock in the book through generated advancements (the vanilla pattern), triggered by holding the first
  ingredient. Upgrades list the tier being upgraded first.
- New weapons use the `kingdoms` namespace; the wand stays `demo:fairy_wand` so held items keep working, and can be
  moved with `/foundry rename`.
- Items can be locked to a people. `require_permission` on an item (a node such as `race:azrak`) stops use, charge,
  swing, on-hit mechanics and the melee hit itself for anyone without it; `locked_message` says why on the action bar.
  The node is asked through the Fabric permission API (`PermissionContextOwner`), because Fabric does not feed vanilla's
  `permissions()`. Operators pass so they can test any item. Charter answers `race:<kingdom>` for its members.
- `keep_on_death` lifts the stack out of the inventory at `ALLOW_DEATH`, before the drops, and gives it back at
  `COPY_FROM`. The set aside stacks live in memory only: a server stop while a player sits on the death screen loses them.
