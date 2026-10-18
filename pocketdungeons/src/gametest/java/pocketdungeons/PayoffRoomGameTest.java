package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Payoff rooms (plan 2026-10-17), Mason's Shortcut and Windlift: each holds its cache (a chest with the
 * {@code vaults/spur_payoff} table) where the plan says, the cache cannot be reached on foot, and the floor
 * under it leads back to the door by a walkable route. The library check covers the doors once
 * {@code PayoffSpecs} is in its spec class list; these methods pin the cache and the route.
 */
public final class PayoffRoomGameTest {

    private static final BlockPos CHEST = new BlockPos(8, 4, 13);
    private static final BlockPos DOOR_INSIDE = new BlockPos(7, 1, 1);

    /** Builds the room at a clear cell and runs {@code body}; always clears afterwards. */
    private static void inRoom(GameTestHelper helper, String name, Body body) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(new BlockPos(0, 1, 0));
        RoomSpec spec = RoomTemplateGenerator.specNamed(name);
        helper.assertTrue(spec != null, name + " has a spec");
        clear(level, o);
        try {
            RoomTemplateGenerator.buildForCheck(level, o, spec);
            body.run(level, o);
        } finally {
            clear(level, o);
        }
    }

    private interface Body {
        void run(ServerLevel level, BlockPos o);
    }

    private static void assertCache(GameTestHelper helper, ServerLevel level, BlockPos o) {
        BlockState state = level.getBlockState(o.offset(CHEST));
        helper.assertTrue(state.is(Blocks.CHEST), "the cache chest stands at " + CHEST.toShortString());
        BlockEntity be = level.getBlockEntity(o.offset(CHEST));
        helper.assertTrue(be instanceof RandomizableContainer container && container.getLootTable() != null
                && container.getLootTable().identifier().getPath().equals(PayoffSpecs.CACHE_TABLE),
                "the chest pays " + PayoffSpecs.CACHE_TABLE);
    }

    @GameTest(maxTicks = 40)
    public void masonsShortcutNeedsBlocksButLeadsBack(GameTestHelper helper) {
        inRoom(helper, "masons_shortcut", (level, o) -> {
            assertCache(helper, level, o);
            Set<BlockPos> fromDoor = new HashSet<>();
            flood(level, o, DOOR_INSIDE, fromDoor);
            helper.assertTrue(!fromDoor.contains(new BlockPos(8, 4, 12)),
                    "the far platform cannot be reached on foot: the gap is five blocks");
            BlockPos gapFloor = new BlockPos(8, 1, 9);
            helper.assertTrue(fromDoor.contains(gapFloor), "the gap floor is open ground");
            Set<BlockPos> fromGap = new HashSet<>();
            flood(level, o, gapFloor, fromGap);
            helper.assertTrue(fromGap.contains(DOOR_INSIDE), "the gap floor leads back to the door");
            helper.assertTrue(fromDoor.contains(new BlockPos(6, 4, 5)), "the walkway is reached by the stair");
        });
        helper.succeed();
    }

    @GameTest(maxTicks = 40)
    public void windliftNeedsALiftButLeadsBack(GameTestHelper helper) {
        inRoom(helper, "windlift", (level, o) -> {
            assertCache(helper, level, o);
            Set<BlockPos> fromDoor = new HashSet<>();
            flood(level, o, DOOR_INSIDE, fromDoor);
            helper.assertTrue(!fromDoor.contains(new BlockPos(8, 4, 12)),
                    "the ledge top cannot be reached on foot: it is three blocks up");
            BlockPos foot = new BlockPos(8, 1, 11);
            helper.assertTrue(fromDoor.contains(foot), "the foot of the ledge is open ground");
            Set<BlockPos> fromFoot = new HashSet<>();
            flood(level, o, foot, fromFoot);
            helper.assertTrue(fromFoot.contains(DOOR_INSIDE), "the ledge foot leads back to the door");
            helper.assertTrue(level.getBlockState(o.offset(8, 0, 11)).is(Blocks.CHISELED_TUFF),
                    "the launch spot is marked with chiseled tuff");
        });
        helper.succeed();
    }

    // ---- walking (local coordinates; the same rules as RoomLibraryGameTest: step up 1, drop up to 3) ----

    private static void flood(ServerLevel level, BlockPos o, BlockPos start, Set<BlockPos> reached) {
        if (!walkable(level, o, start)) {
            return;
        }
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        reached.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            BlockPos at = queue.poll();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                for (int dy = 1; dy >= -3; dy--) {
                    BlockPos next = at.relative(d).offset(0, dy, 0);
                    if (next.getX() < 1 || next.getX() > 14 || next.getZ() < 1 || next.getZ() > 14
                            || next.getY() < 1 || next.getY() > 5) {
                        continue;
                    }
                    if (walkable(level, o, next)) {
                        if (reached.add(next)) {
                            queue.add(next);
                        }
                        break;
                    }
                }
            }
        }
    }

    private static boolean walkable(ServerLevel level, BlockPos o, BlockPos local) {
        BlockPos feet = o.offset(local);
        return open(level, feet) && open(level, feet.above()) && !open(level, feet.below());
    }

    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.JIGSAW) || state.getCollisionShape(level, pos).isEmpty();
    }

    private static void clear(ServerLevel level, BlockPos o) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -2; y <= RoomGeometry.CEILING_Y + 1; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }
}
