# Races: passives, trinkets and gathering bonuses

Status: spec, 2026-11-01, owner decisions folded in. Target is Minecraft 26.3. Build order: passives first (gathering
in Foundry, combat in Charter), then trinkets. Every number is a starting point to tune.

Scope: the five races of the kingdom server (Azrak Dwarf, Aurelia Human, Azurius Merfolk, Faeloria Fae, Everdelle
Fairy). Charter owns who is which race (`race:<kingdom>` permission nodes, `RaceNodes`). Foundry owns items, triggers
and the drop hooks. Foundry never depends on Charter; it only asks for permission nodes.

## 1. Template

Every race has the same five lines.

| Slot | Rule |
|---|---|
| Gathering passive | A free level of Fortune or Looting on one resource family that no other race owns. It exists to make kingdoms trade. |
| Combat passive | Works on a different axis from every other race's combat passive. |
| Trinket | One worn item, visibly modelled, that carries the race's signature mechanic. |
| Weapon | Already exists for Dwarf, Merfolk, Fae and Fairy. Human has none yet. |
| Identity | Height, vision and a line of flavour. |

Active abilities from the brainstorm are out of scope for this pass.

## 2. Fairies and Fae must stay apart

The brainstorm made them too alike (both small, winged, Blindness tricksters, both healers). The split:

| | Fairy | Fae |
|---|---|---|
| Identity | Tiny, fast, airborne support | Human-sized, grounded, deceptive control |
| Movement | Flight, the only race with it | Average; Jump Boost in green biomes |
| Height | 1.2 blocks | 1.8 blocks |
| Vision | Normal | Night Vision |
| Gathering | Dusts (section 3) | Alchemy plants (section 3) |
| Combat | Evasion | Deception |
| Trinket | Wings | Antler circlet |

Fairy Mischief changes from Blindness plus Slowness to something airborne (a short blink, or a dust cloud that makes
enemies miss), so it no longer overlaps Fae Trickery. Mushroom Ring exempts "fae-kind" without naming fairies.

## 3. Gathering passives

Each is a free +1 level, in the style of Fortune I (blocks) or Looting I (mobs). All five are the same strength.

| Race | Bonus | Applies to |
|---|---|---|
| Dwarf | Fortune I | Coal, iron, copper, gold, lapis, diamond, emerald, nether quartz and nether gold ores. Not redstone ore. |
| Fairy | Fortune I and Looting I | Dusts: redstone ore, glowstone blocks, gunpowder (creepers, ghasts, witches) and similar mob dust drops. |
| Human | Looting I | Farm animals: cow, pig, sheep, chicken, rabbit (meat, leather, wool, feathers). |
| Fae | Fortune I | Alchemy plants: nether wart, sweet and glow berries, mushrooms, flowers. |
| Merfolk | Looting I | Water things: guardians, elder guardians, drowned, squid, glow squid, plus a bonus on fish and sea blocks (prismarine, kelp, sea pickles). No books. |

Rules:
- Each resource has one owner. Redstone ore belongs to Fairy, so it is off the Dwarf list.
- The bonus adds to the player's own tool enchantment and is capped at level III. A Fortune III pick gains nothing.
- The bonus applies only to a player who holds the race's permission node. Mercenaries and members alike.
- Brewing mob drops (blaze rods, ghast tears, spider eyes) stay out of Fae; they are plants only.
- The lists live in JSON so they can change without Java.

Why free levels and not bigger numbers: a bonus drives trade only if other players cannot easily get the resource
themselves. Equal, modest bonuses on separate families keep any one race from being the obvious pick.

## 4. Combat passives

Always on, and deliberately mild. They are knobs in Charter (`charter.json`), so a weak number is one edit away.

| Race | Combat passive | Axis | Shipped number |
|---|---|---|---|
| Dwarf | Bedrock Stance: knockback resistance while sneaking | Defense | +0.4 knockback resistance (0 to 1 scale) |
| Merfolk | Tidewrath: extra melee damage while in water | Terrain power | +1.5 attack damage (about Strength I's half) |
| Human | Rally: extra melee damage for each nearby member of your own kingdom | Team | +0.5 per ally within 8 blocks, 3 allies at most |
| Fae | Glamour: hostile mobs lose interest at range | Deception | Mobs farther than 6 blocks drop their target; for 10 s after the Fae attacks, they do not |
| Fairy | Featherlight: no fall damage and a small dodge | Evasion | No fall damage; 8% chance to dodge a melee hit |

Merfolk also mine at normal speed while submerged (vanilla slows submerged mining to a fifth). This is a utility, not
combat: they already dominate the water, so it only removes the penalty and gives nothing extra.

Glamour is the loosest fit. Vanilla has no event for a mob choosing a target and Foundry and Charter use no Mixins, so
Glamour clears the target of nearby hostile mobs on a short timer instead of preventing it. A mob can still notice a
Fae for an instant.

## 5. Trinkets

Each race has one worn trinket with its own model, so a race is recognisable at a glance.

| Race | Trinket | Carries |
|---|---|---|
| Dwarf | Forgeman's Pauldron | Cosmetic for now |
| Merfolk | Tidal Gills | Water Breathing and Dolphin's Grace (moved out of `racePassives` into the item) |
| Human | Kingdom cape | Cosmetic for now; shows the wearer's kingdom |
| Fae | Antler circlet | Cosmetic for now |
| Fairy | Wings | Flight |

Each trinket works only for its race (permission gated); anyone else can wear it as decoration.

### Worn slot

Decided: Patbox's Trinkets, a separate slot so wings do not cost the chest slot. A 26.3 build exists. The `while_worn`
trigger still sits behind a small worn-items interface, so a vanilla equipment slot stays possible as a fallback.

## 6. What Foundry needs

Foundry today has four held-item triggers (`on_use`, `on_hit_entity`, `on_swing`, `on_bolt_hit`) and `apply_effect`
already takes `require_permission` and `exclude_permission`.

New work:
1. A `while_worn` trigger, plus a worn-items interface (Trinkets or vanilla slots behind it).
2. A block-drop hook (Fortune style) and a mob-drop hook (Looting style) from Fabric API loot events. No Mixins (D6).
3. A fish hook for the Merfolk bonus.
4. Race lists in JSON, one file per race.
5. A flight mechanism for the Fairy wings (glider component or a fly-ability toggle).
6. Bedrock: custom item mappings exist (`foundry/examples/bedrock/custom_mappings/foundry.json`). A worn model may not
   show for Bedrock players without a resource pack; verify before promising it.

Charter work: none for the trinkets. The always-on combat passives, if chosen, extend `racePassives`.

## 7. Open questions

Answered in writing on 2026-11-01. Items marked OWNER need a decision before the work continues.

1. Human gameplay: the kingdom cape is cosmetic. Rally is the only Human mechanic until a Human weapon exists. No change.
2. Which Merfolk effects stay in `racePassives` once Tidal Gills takes Water Breathing and Dolphin's Grace? Both stay until the trinket works on a live server. The Bedrock Speed II stand in (`racePassivesBedrock`) stays too: a worn trinket cannot carry it, since it exists only because Bedrock has no Dolphin's Grace.
3. Glamour is approximate. The target is cleared on a 10 tick timer, so a mob can glance at a Fae for up to half a second. No mod can prevent targeting without a Mixin. Not tuned stronger. Needs a play test; the knobs are `faeGlamourRange` and `faeGlamourGraceTicks`.
4. Combat numbers stay mild: Dwarf 0.4 knockback resistance only while sneaking, Merfolk +1.5 attack damage only in water, Human +0.5 per ally (3 at most, so +1.5), Fairy 8 percent dodge on melee hits and no fall damage, Fae 6 block Glamour. All are knobs in `charter.json` and `raceCombat = false` turns every one off. Unchanged.
5. Fishing bonus. OWNER: there is no Fabric API hook for a fish being caught. The only no Mixin route is to change the fishing loot tables with `LootTableEvents.MODIFY`: add a pool to each of `gameplay/fishing/fish`, `junk` and `treasure` that rolls the same table again, guarded by a custom loot condition `foundry:has_permission` that asks the permission API about the angler. It needs one registered condition type and has not been tried. The other option is to drop the fishing bonus; Merfolk keep their mob and sea block bonuses.
6. Trinkets mod stack. OWNER: Patbox's mod is "Trinkets Updated" (Modrinth `trinkets-updated`), version `4.2.1+26.3`, a release. It needs the mod on the client as well as the server, because the slot screen is a client feature. The kingdom server is vanilla client and Geyser only, so it also needs "Polymer Patch for Trinkets Updated" (Modrinth `trinkets-polymer`), version `4.2.0.0+26.3`, which is marked beta and is server side only, and which requires Polymer. That is two extra jars on the server, one of them beta, and a changed equip flow for every player. Not started.
7. Bedrock and worn models. Foundry's Bedrock mappings (`foundry/examples/bedrock/custom_mappings/foundry.json`, 11 items) map held items by base item. A worn trinket model through Trinkets is a client side render. Vanilla Java clients will not render it without a resource pack, and Geyser has no worn item mapping, so Bedrock players should expect to see no worn model. The trinket would still work. Not promised.
8. Flight (Fairy wings). OWNER: with vanilla clients the only server side flight is the creative style `mayfly` ability, which gives free creative flight. The limits in section 5 (stamina or hunger cost, none in heavy armour or swimming) would be enforced by the server turning flight off. A glide or a short burst needs an elytra style item instead. Not built until the mod stack above is decided.

## 8. Build status

| Part | State |
|---|---|
| Spec | This document |
| Combat passives | Done and verified. Rules, knobs and tests (`charter/core/.../RaceCombat.java`, 25 checks). Game glue (`RaceCombatPassives.java`) compiled with no changes. Gametests in `RaceCombatGameTest`: fall immunity and the dodge (with a control player who takes the hit), Dwarf sneak knockback, Rally counting only same kingdom allies, Merfolk submerged mining, and the `raceCombat` switch. All 116 Charter gametests pass. Glamour has no gametest (see section 7). |
| Gathering passives | Done and verified. Definition, parser, drop math and tests (`foundry/core/Bonus*.java`, 279 core checks). Game glue (`foundry/mc/Bonuses.java`) needed one fix for 26.3 (entity type tags go through `builtInRegistryHolder()`) and one change (the creative check reads the game mode, because the gametest mock player always answers `isCreative()` true). Gametests in `BonusesGameTest` use the shipped example files: a Dwarf gets extra raw iron and a player of no race gets exactly one per ore, a Fairy does not get a Dwarf's iron, and a Fairy gets extra gunpowder from creepers but no extra sticks from witches. All 43 Foundry gametests pass. |
| Fishing bonus (Merfolk) | Not built. Fabric API has no fish caught event (checked the entity events, loot, item and interaction modules of 0.165.0+26.3). See section 7 for the option. |
| Trinkets and `while_worn` | Not started. Needs an owner decision on the mod stack (section 7). |

Still to check on a live server: that a Dwarf gets extra ore, a Fairy extra gunpowder, a Fairy takes no fall damage and a Merfolk mines at normal speed underwater, with real clients. The gametests use mock players.

How a bonus file reads (`<content>/<namespace>/bonuses/<name>.json`): `require_permission` (the race node),
`kind` (`fortune` for blocks, `looting` for mobs), `level` (default 1), `cap` (default 3), `targets` (ids or `#tags`)
and an optional `only_items` list that limits which extra items are granted.
