# Area tools

Two new items, one new class.

| id | Name | Base | Flavour |
|---|---|---|---|
| `big_hole_pick` | Big Hole Pick | Diamond pickaxe | *oops thats a lot of stone* |
| `big_hole_shovel` | Big Hole Shovel | Diamond shovel | *just a lil dig* |

Both break a 3×3 plane facing the hit face. Sneak for a single block.

## Why AFTER and not BEFORE

I said `BEFORE` last time. `AFTER` is better, and the reason is
`ServerPlayerGameMode.destroyBlock(BlockPos)` -- which is already in the recon file.

That one call does the full vanilla sequence: correct-tool check, drops using the
held tool so Fortune and Silk Touch work, ore experience, tool durability, stats,
block entities. Breaking each neighbour through it means none of that is
hand-rolled, and the risky `hurtAndBreak` signature never comes up.

With `BEFORE` we'd cancel vanilla and own the centre block's drops, XP and Fortune
ourselves. Tidier cancellation, considerably more to get wrong.

Cost of `AFTER`: `destroyBlock` re-enters our own handler, so there's a `breaking`
set guarding against infinite recursion. Cheap.

## Why the face is cached

`PlayerBlockBreakEvents` doesn't carry the hit face, and without it the plane can't
be oriented -- mining a wall should carve into the wall, mining a floor should
carve into the floor.

`AttackBlockCallback` fires when the player *starts* breaking and does give a
`Direction`. It's cached per UUID and read back in the break handler. Deriving the
face from the look vector instead is wrong exactly when you're mining at an angle,
which is most of the time underground.

## The filters, and why each exists

Copied from how TiC hammers behave, because that's the part with a decade of
balance passes behind it.

- **`isCorrectToolForDrops`** — mine stone, get a 3×3 of stone; the dirt and gravel
  in the plane stay put. This single rule is the difference between a fun tool and
  a terrain eraser. It's also what makes the shovel version work with no extra code.
- **Hardness guard** (`> centre + 0.5`) — stops mining stone from incidentally
  scooping the ancient debris next to it.
- **Hardness < 0** — bedrock, barriers, the void.
- **Block entities skipped** — nobody wants their chest in the blast radius.
- **Fluids skipped** — otherwise you flood the tunnel you're standing in.
- **Sneak to disable** — how you place a torch or mine one block cleanly.

Durability is charged per block because `destroyBlock` does it, and the loop stops
early if the tool breaks mid-swing.

## Done: the speed penalty

Both area tools now carry a Tier-0 `ATTRIBUTE_MODIFIERS` entry in their `decorate`
step that reduces `BLOCK_BREAK_SPEED` by 30% while held in the main hand. The 3×3 is
no longer a strict upgrade over a plain diamond tool — it's faster for clearing
volume, but slower for mining a single block.

The attribute id was confirmed against the merged 26.2 jar:

```bash
javap -classpath "$JAR" net.minecraft.world.entity.ai.attributes.Attributes \
  | grep -iE "mining|break_speed|dig"
```

`BLOCK_BREAK_SPEED` is the multiplier vanilla applies to mining progress, so it
was the right choice (the jar also exposes `MINING_EFFICIENCY`, but that attribute
adds a flat bonus rather than scaling the whole speed).

## Signatures to watch on first build

Three things here weren't in the original recon:

1. **`AttackBlockCallback` parameter order** — expected
   `(player, level, hand, pos, direction)`.
2. **`PlayerBlockBreakEvents.AFTER` parameters** — expected
   `(level, player, pos, state, blockEntity)`.
3. **`ItemStack.isCorrectToolForDrops(BlockState)`** — may want a `Level` and
   `BlockPos` too on this version.

`BlockState.getDestroySpeed(BlockGetter, BlockPos)`, `Level.getBlockEntity`,
`BlockPos.offset(int,int,int)` and `ServerPlayerGameMode.destroyBlock` are all
either confirmed or long-stable.

## Test gate

1. Mine stone in a wall — 3×3 carved into the wall, not the floor
2. Mine stone in the floor — 3×3 in the floor
3. Sneak-mine — exactly one block
4. Mine stone with dirt adjacent — the dirt stays
5. Mine stone with a chest adjacent — the chest survives
6. Mine an ore with Fortune — Fortune applies to all of them
7. Mine near bedrock — bedrock survives
8. Break the tool mid-swing — no crash, loop stops
9. Mine with an ordinary diamond pickaxe — one block, as always
10. Dig dirt with the shovel — 3×3 of dirt; stone in the plane stays

Numbers 4 and 10 are the interesting ones: they're both the effectiveness filter,
and they're what stops the tools feeling broken.
