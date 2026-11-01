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
| Dwarf | Fortune I | Coal, iron, copper, gold, lapis, diamond, emerald, quartz and stone-tier blocks. Not redstone ore. |
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

1. Human gameplay: the kingdom cape is cosmetic. Rally is the only Human mechanic until a Human weapon exists.
2. Which Merfolk effects stay in `racePassives` once Tidal Gills takes Water Breathing and Dolphin's Grace? Until the
   trinket exists they stay, so nothing is lost.
3. Bedrock players: a worn trinket model may not show without a resource pack. Verify before promising it.
4. Glamour is approximate (section 4). Say so if a mob that glances at a Fae is not acceptable.

## 8. Build status

| Part | State |
|---|---|
| Spec | This document |
| Combat passives (Charter) | In progress; see git log |
| Gathering passives (Foundry) | In progress; see git log |
| Trinkets and `while_worn` | Not started |

The build environment for this spec had no access to the Fabric Maven host and no JDK 25, so Minecraft-side code was
written but not compiled there. Pure logic is tested. Compile and gametest before relying on any of it.
