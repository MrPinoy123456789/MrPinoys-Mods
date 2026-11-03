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
| Weapon | All five races have one: the Azrak pick, the Aurelia oathblade, the Azurius trident, the Faeloria staff and the Everdelle wand, three tiers each. |
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

Merfolk also mine in the water exactly as fast as standing on land, whether on the sea floor or floating (vanilla slows
submerged mining to a fifth, and to a fifth again when standing on nothing). This is a utility, not combat: they
already dominate the water, so it only removes the penalties and gives nothing extra. An Aqua Affinity helmet adds
nothing on top (before 2026-11-03 it did, and a Merfolk with one mined five times faster than on land). Standing on the
sea floor they also walk as on land (water movement efficiency 1, what Depth Strider III gives; Depth Strider boots add
nothing on top). Floating or treading water, not swimming, the same boost is halved by vanilla, so they move about as
with Depth Strider II. Their Dolphin's Grace (Speed II on Bedrock) is only while swimming, arms paddling (it has no
real levels: vanilla only checks whether a player has it), and they have
Night Vision while their head is under water.

Glamour is the loosest fit. Vanilla has no event for a mob choosing a target and Foundry and Charter use no Mixins, so
Glamour clears the target of nearby hostile mobs on a short timer instead of preventing it. A mob can still notice a
Fae for an instant.

## 5. Trinkets

Each race has one worn trinket with its own model, so a race is recognisable at a glance.

| Race | Trinket | Carries |
|---|---|---|
| Dwarf | Forgeman's Pauldron | Haste I for Dwarves of Azrak |
| Merfolk | Tidal Gills | Water Breathing and Dolphin's Grace (moved out of `racePassives` into the item) |
| Human | Kingdom cape | Luck I for Humans of Aurelia (anyone else wears it as decoration) |
| Fae | Antler circlet | Night Vision for the Fae of Faeloria |
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

1. Human gameplay: the Aurelia oathblade exists, so Humans have Rally, a weapon and a cape with Luck I. Answered 2026-11-01: nothing more is planned before the server ships.
2. Which Merfolk effects stay in `racePassives` once Tidal Gills takes Water Breathing and Dolphin's Grace? Both stay until the trinket works on a live server. The Bedrock Speed II stand in (`racePassivesBedrock`) stays too: a worn trinket cannot carry it, since it exists only because Bedrock has no Dolphin's Grace.
3. Glamour is approximate. The target is cleared on a 10 tick timer, so a mob can glance at a Fae for up to half a second. No mod can prevent targeting without a Mixin. Not tuned stronger. Needs a play test; the knobs are `faeGlamourRange` and `faeGlamourGraceTicks`.
4. Combat numbers stay mild: Dwarf 0.4 knockback resistance only while sneaking, Merfolk +1.5 attack damage only in water, Human +0.5 per ally (3 at most, so +1.5), Fairy 8 percent dodge on melee hits and no fall damage, Fae 6 block Glamour. All are knobs in `charter.json` and `raceCombat = false` turns every one off. Unchanged.
5. Fishing bonus. OWNER: there is no Fabric API hook for a fish being caught. The only no Mixin route is to change the fishing loot tables with `LootTableEvents.MODIFY`: add a pool to each of `gameplay/fishing/fish`, `junk` and `treasure` that rolls the same table again, guarded by a custom loot condition `foundry:has_permission` that asks the permission API about the angler. It needs one registered condition type and has not been tried. The other option is to drop the fishing bonus; Merfolk keep their mob and sea block bonuses.
6. Trinkets mod stack. DECIDED 2026-11-01: use Patbox's "Trinkets Updated" `4.2.1+26.3` (release, Modrinth `trinkets-updated`, Maven `eu.pb4:trinkets`) and "Polymer Patch for Trinkets Updated" `4.2.0.0+26.3` (beta, Modrinth `trinkets-polymer`), which is server side only and lets vanilla clients use the slots. Both jars were downloaded and checked against Modrinth's sizes and SHA 512 hashes. They need Polymer (the server already has polymer-bundled) and Fabric API. Foundry compiles against Trinkets but starts without it. The jars are not on any server yet.
7. Bedrock and worn models. Foundry's Bedrock mappings (`foundry/examples/bedrock/custom_mappings/foundry.json`, 11 items) map held items by base item. A worn trinket model through Trinkets is a client side render. Vanilla Java clients will not render it without a resource pack, and Geyser has no worn item mapping, so Bedrock players should expect to see no worn model. The trinket would still work. Not promised.
8. Flight (Fairy wings). SUPERSEDED 2026-11-01 later: the wings go in the trinket cape slot, glide like an elytra and never wear out (see docs/KINGDOM_SERVER.md, "Race trinkets"). The earlier decision follows. DECIDED 2026-11-01: the wings are an elytra. `kingdoms:fairy_wings` is a Foundry item on the elytra base with the glider and chest slot components, a durability of 432 (the wing wears out as it flies, which is the cost) and a race lock. It goes in the chest slot, so it cannot be worn with a chestplate, and it cannot glide in water. A player without the permission is stopped from gliding. Not a trinket slot item: "Trinkets Elytra Support" is marked incompatible with the Polymer patch by its author, so a trinket slot elytra would need the mod on every client. The lock on gliding has no gametest (a mock player does not glide); verify it on a live server.
9. Fishing bonus. Postponed by the owner on 2026-11-01. Option 5 above stays open.
10. A locked item and its slot. A trinket with a race lock (the gills) refuses a right click for anyone else but can still be put in a slot from the Trinkets screen, where it does nothing. Whether a non race player can equip it by right click on a live server is unchecked.

## 7a. Balance pass (2026-11-01): every race one movement trait, one passive and one sense

The owner asked that every race has its own movement trait and about the same total power: one movement, one passive,
one sense, on top of the combat and gathering passives every race already has (sections 3 and 4). Final table:

| Race (node) | Movement | Passive | Sense | Combat (Charter) | Gathering |
|---|---|---|---|---|---|
| Azrak Dwarves (`race:azrak`) | Deep Stride: +15% walking speed under a roof of blocks | Haste I | Ore sense, 8 blocks: gold, lapis, emerald, iron, copper and redstone ores (and deepslate ones); never diamond or ancient debris | Bedrock Stance | Fortune I on ores |
| Aurelia Humans (`race:aurelia`) | Horsemanship: the horse, donkey, mule or camel they ride gets +20% speed and +10% jump. Crouch slide: sprinting on the ground, press sneak to slide about 4 blocks forward, 3 s recharge (`slide` in `race_traits.json`: `speed` 1.7, `cooldown` 60) | Better trades: 10% off villager and wandering trader prices (rounded, never below 1) | Treasure sense, 8 blocks: unopened surface structure loot (villages, shipwrecks, buried treasure, mineshafts, ruined portals) | Rally | Looting I on farm animals |
| Faeloria Fae (`race:faeloria`) | Wing dash flight (3 bursts the way they look, a slow fall between, recharging in the air) and half fall damage | Night Vision | Herb sense, 12 blocks: ripe nether wart, torchflower, pitcher plant, wither rose, chorus flower, glow berries on cave vines, ripe cocoa | Glamour | Fortune I on alchemy plants |
| Azurius Merfolk (`race:azurius`) | Swim dash (2 s cooldown) and Dolphin's Grace | Water Breathing | Tide sense, 10 blocks: sponge, wet sponge, suspicious sand and gravel | Tidewrath | Looting I on water things |
| Everdelle Fairies (`race:everdelle`) | Glide, always, nothing in the chest slot; a takeoff leap (sneak and jump, 5 s cooldown) and 3 wingbeats while gliding (jump), no rockets needed | none (their Featherlight already spares all fall damage) | Glimmer sense, 7 blocks, every 4 s: diamond and emerald ores, amethyst clusters, budding amethyst | Featherlight | Fortune and Looting I on dusts |
| Mossbeard's Muddle (`race:mossbeard_s_muddle`) | Old Forest Walk: +15% walking speed on living ground (grass, moss, podzol, mycelium, rooted or coarse dirt, mud, leaves) | Keeps everything on death (Charter `racesKeepInventory`) | Kindred sense, 12 blocks: bee nests, beehives, turtle eggs, sniffer eggs, frogspawn | none | none |

How the new traits work (Foundry, `config/foundry/race_traits.json`, built in defaults match the shipped example):
- Movement is server side, so Bedrock players get it: speed bonuses are movement speed attribute modifiers (a share of
  base speed, 0.15 is three quarters of Speed I), the Human mount bonus is on the mount's own speed and jump attributes
  and comes off when they get down, the Fae's half fall damage cuts the fall damage multiplier (a mixin), and the double
  jump and swim dash are velocity pushes. None of it touches the Fairy glide or the wings.
- The Fae fly by wing dash (`wing_dash` in `race_traits.json`; built 2026-11-02, see
  `docs/FAELORIA_FLIGHT_OPTIONS.md`): jump in the air beats the leaf wings, a burst the way they look (`forward` 1.2
  blocks a tick, `up` 0.8 looking straight up, half that looking level, none looking down). After a burst they fall
  slowly (a short, hidden Slow Falling, renewed while it lasts) until they land or press sneak. `charges` 3, all back on
  landing and one back every `refill` 30 ticks in the air, at least `gap` 8 ticks apart, so a Fae who paces the bursts
  stays up: at one burst per refill, looking about half up gains about a block a beat and looking level loses about
  1.7 (a `refill` of 20 holds height looking level). The server runs with allow-flight off, so a Fae who has beaten
  their wings is never counted as floating (`resetFlyingTicks`). Bursts never leave them faster than `max_speed` 1.3 across the ground, and only for a moment: a burst looking level carries about 13 blocks, then air drag takes it (a Fairy glides at up to 1.2 for as long as it stays up) and stop
  climbing above `ceiling` 12 blocks over the ground below. A hit from a mob, a player or a projectile takes a charge
  and drops them for `hit_drop` 20 ticks; poison, fire and falls do not. With no `wing_dash` block the Fae fly on these
  defaults; `"charges": 0` brings back the old double jump (`double_jump` cooldown in `worn_displays.json`, forward
  carry `jump_drift`). A burst resets the fall, so the half fall damage applies to whatever fall comes after it.
- A sense searches the blocks within its radius (1 to 16, knob `radius`) every two seconds (knob `every`), each player
  on their own beat, and points at the nearest find to that player only: a short trail of coloured dust from the player
  toward it (two blocks long at most, so it hints rather than marks) and an action bar line, "Ore sense: Diamond Ore,
  about 6 blocks away". Both reach Bedrock through Geyser. Nothing past the radius is ever revealed. Block lists take
  ids, ids with block state values (`minecraft:cocoa[age=2]`) and `#loot_chest`.
- Passives stay Charter's `racePassives` (unchanged). Every knob: `stride` (`bonus`, `ground`: underground, wild or
  anywhere), `mount` (`speed`, `jump`), `fall_damage`, `jump_drift`, `trade_discount`, `flight`, `sense` (`name`, `blocks`,
  `radius`, `every`, `colour`). A race listed in the file replaces its built in traits.
- Better trades (`trade_discount`, Humans 0.1, at most 0.5): when a villager or wandering trader starts trading with
  the player, each offer's first cost drops by that share, rounded (a 10 emerald offer costs 9, a 3 emerald one stays
  3), never below one; on the server, the same per offer change vanilla uses for Hero of the Village, with no effect or
  particles. It comes off when trading stops. Charter Currency's `/shop` is not discounted: its prices are flat per
  listing with no per player hook, and it does not know races. Humans no longer get Luck (Charter's default
  `racePassives` no longer lists `aurelia`; a server whose `charter.json` still lists it must remove that entry).
- Fairy flight (`flight`, Fairies only): sneak and jump on the ground springs them about 7 blocks up and starts the glide
  at the top (`leap` 1.05, `leap_cooldown` 100 ticks). While gliding, jump beats the wings: the fall is cancelled, they
  lift (`beat_lift` 0.55) and are pushed the way they look (`beat_push` 0.3), never past 1.2 blocks a tick across the
  ground (`max_speed`; a rocket goes about 1.7). Three beats (`beats`); all come back after half a second on the ground,
  one every 10 s in the air (`refill` 200). The action bar shows the beats left. It is the middle way between gliding
  only and rockets: a Fairy can get airborne and climb out of a dive, but cannot stay up for ever or keep pace with
  rockets. Rockets still work for them as for anyone. Unverified: whether a Bedrock client reports a jump press while
  gliding (if not, Bedrock Fairies get the leap but not the beats).
- Sense lists also take `#loot:<prefix>`: a container that still has its loot to roll whose loot table id starts with
  the prefix (the Humans' list is `minecraft:chests/village/`, `shipwreck_`, `buried_treasure`, `abandoned_mineshaft` and
  `ruined_portal`; no ancient cities, end cities, bastions or trial vaults). Ancient debris is nobody's to sense.
- The Human look is now a sashimono: the pole against the back, an arm from its top reaching back, and the tall black
  cloth hanging from it beside the pole, so it shows full on from the left and right; three silver bands across its top,
  the crowned skull below, silver rings where it is laced to the pole.

Where this differs from earlier parts of this document, this section wins:
- Section 2 gave the Fae "average movement, Jump Boost in green biomes". The build gives them a double jump and half
  fall damage instead (owner's choice), and no Jump Boost.
- Section 5's trinkets are retired: the race looks are cosmetic and the effects are Charter's `racePassives`.
- Section 7 item 1 said nothing more was planned for Humans before the server ships; Horsemanship and Treasure sense
  are new.
- Mossbeard's Muddle (the admin kingdom) is not in sections 1 to 6; its traits are only in the table above.
- Section 2 gives the Fairy a height of 1.2 blocks and the Fae 1.8. Heights are not built (everyone is player sized),
  and no height or vision for the other races is written anywhere in the repository.

## 8. Build status

| Part | State |
|---|---|
| Spec | This document |
| Combat passives | Done and verified. Rules, knobs and tests (`charter/core/.../RaceCombat.java`, 25 checks). Game glue (`RaceCombatPassives.java`) compiled with no changes. Gametests in `RaceCombatGameTest`: fall immunity and the dodge (with a control player who takes the hit), Dwarf sneak knockback, Rally counting only same kingdom allies, Merfolk submerged mining, and the `raceCombat` switch. All 116 Charter gametests pass. Glamour has no gametest (see section 7). |
| Gathering passives | Done and verified. Definition, parser, drop math and tests (`foundry/core/Bonus*.java`, 279 core checks). Game glue (`foundry/mc/Bonuses.java`) needed one fix for 26.3 (entity type tags go through `builtInRegistryHolder()`) and one change (the creative check reads the game mode, because the gametest mock player always answers `isCreative()` true). Gametests in `BonusesGameTest` use the shipped example files: a Dwarf gets extra raw iron and a player of no race gets exactly one per ore, a Fairy does not get a Dwarf's iron, and a Fairy gets extra gunpowder from creepers but no extra sticks from witches. All 43 Foundry gametests pass. |
| Fairy Mischief | Changed on 2026-11-01. The bolt now hurts and lifts its target off the ground (Levitation, 30, 40 and 50 ticks by tier, with a cloud) instead of blinding and slowing, so it no longer overlaps Fae Trickery. All three wand tiers; `WandGameTest` was updated to match (the old test checked for blindness and slowness, which the spec removed). |
| Trinket art | Five 16 by 16 sprites in `foundry/examples/assets/kingdoms/textures/item/`, the five items point at them, and the Bedrock mappings and pack were regenerated with `tools/gen_bedrock.py` (26 items). Plain placeholder art, easy to replace. |
| Fishing bonus (Merfolk) | Not built. Fabric API has no fish caught event (checked the entity events, loot, item and interaction modules of 0.165.0+26.3). See section 7 for the option. |
| Trinkets and `while_worn` | Built and gametested in Foundry with Trinkets Updated 4.2.1+26.3. The `while_worn` trigger fires once a second for a Foundry item worn in a Trinkets slot (or an armour slot, as a fallback when the mod is absent). Five items in `foundry/examples/content/kingdoms/items/`: `tidal_gills` (Water Breathing and Dolphin's Grace for Merfolk only), `fairy_wings` (an elytra, see section 7), and `kingdom_cape` (Luck I for Humans), `forgeman_pauldron` (Haste I for Dwarves) and `antler_circlet` (Night Vision for the Fae), each for its own race only and decoration for anyone else. `data/trinkets/entities/foundry_player.json` gives players the four slots they need. 47 Foundry gametests pass. Not tried with a real client or the Polymer patch. |

Still to check on a live server: that a Dwarf gets extra ore, a Fairy extra gunpowder, a Fairy takes no fall damage and a Merfolk mines at normal speed underwater, with real clients. The gametests use mock players.

How a bonus file reads (`<content>/<namespace>/bonuses/<name>.json`): `require_permission` (the race node),
`kind` (`fortune` for blocks, `looting` for mobs), `level` (default 1), `cap` (default 3), `targets` (ids or `#tags`)
and an optional `only_items` list that limits which extra items are granted.
