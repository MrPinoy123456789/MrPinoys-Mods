# Hand-off: fix the area-tool balance before they go in the shop

## Context

`wondrous` is a Fabric 26.2 / JDK 25 server-side mod providing a small catalog of
custom items (pocket crafting stations, flying boots, and two "area tools" — a
pickaxe and shovel that break a 3x3 plane instead of one block). Read
`@/a:/wondrous/README.md` for the full item catalog and how the mod works
(no Mixins, event-callback based, items are vanilla items tagged with custom
data rather than new registry entries).

These two area tools (`big_hole_pick`, `big_hole_shovel`) are about to be sold
in another mod's shop (`cobbleeconomy`) for diamonds. Before that happens they
need a documented balance fix, or they will be strictly-better-than-vanilla
diamond tools forever, which undercuts every other item in the shop's price
ladder regardless of how expensive they're priced.

## What to do

Read `@/a:/wondrous/AREA-TOOLS.md` in full — it documents exactly what's needed
under the "Not done: the speed penalty" section. Summary:

1. **Confirm the attribute id first.** It has moved between Minecraft versions
   (`mining_efficiency` vs `block_break_speed`). Run against the installed
   Minecraft jar:

   ```bash
   javap -classpath "$MC_JAR" net.minecraft.world.entity.ai.attributes.Attributes \
     | grep -iE "mining|break_speed|dig"
   ```

   Find the jar path the same way `cobbleeconomy`'s README documents it
   (`~/.gradle/caches/fabric-loom/26.2/minecraft-merged.jar` or equivalent).

2. **Add a negative mining-speed `ATTRIBUTE_MODIFIERS` entry** to both area
   tools' item `decorate` step (wherever these two items are constructed/decorated
   in the item registration code — search for where their `ItemStack` is built).
   This is a data change, not new logic — no new class needed.

3. **Run through the test gate already written in `AREA-TOOLS.md`** (numbered
   list near the bottom of that file) to confirm the tools still work correctly
   for their intended purpose (breaks the right blocks, respects Fortune/Silk
   Touch, stops at bedrock, etc.) and now also mine measurably slower than a
   plain diamond tool, making the AoE a genuine trade-off rather than a strict
   upgrade.

## What NOT to change

- Everything else about the mod (pocket stations, flying boots, `Gate` admin
  check, item catalog, commands) is untouched. This is scoped entirely to the
  two area tools' mining speed.
- Don't touch pricing — that's decided in the `cobbleeconomy` shop config, not
  here.

## Done when

- The attribute modifier is confirmed against the real jar and applied to both
  `big_hole_pick` and `big_hole_shovel`.
- All items in the `AREA-TOOLS.md` test gate still pass.
- Mining with either area tool is noticeably slower than mining the same block
  with a plain diamond tool of the same type.
- `AREA-TOOLS.md`'s "Not done" section can be updated to say it's done.
