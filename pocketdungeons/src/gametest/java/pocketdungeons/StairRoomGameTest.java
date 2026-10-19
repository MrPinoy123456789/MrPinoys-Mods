package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Vertical floors phase 2: the four generic stair rooms. They load with a top story door west and a lower story
 * door east, north or south; they stamp with those doors open and every other door slot walled; and a mob finds a
 * path down the stairs, with no ladder anywhere in the room, so a fight on them is fair and a mob can follow a
 * player.
 */
public final class StairRoomGameTest {

    private static final int H = RoomGeometry.STORY_HEIGHT;

    /** Room name to the wall its lower door is on, at rotation 0 (the top door is always west). */
    private static final Map<String, DoorMask.Direction> LOWER_DOOR = Map.of(
            "stair_straight", DoorMask.Direction.EAST,
            "stair_gallery", DoorMask.Direction.EAST,
            "stair_turn_north", DoorMask.Direction.NORTH,
            "stair_turn_south", DoorMask.Direction.SOUTH);

    @GameTest(maxTicks = 20)
    public void stairRoomsLoadWithADoorOnEachStory(GameTestHelper helper) {
        RoomManifest manifest = RoomManifest.current();
        for (Map.Entry<String, DoorMask.Direction> e : LOWER_DOOR.entrySet()) {
            RoomManifest.Entry entry = manifest.byName(e.getKey());
            helper.assertTrue(entry != null, e.getKey() + " is in the room manifest");
            helper.assertValueEqual(entry.meta.spanY, 2, e.getKey() + " spans two stories");
            int expected = DoorMask.edge(DoorMask.Direction.WEST, 0) | DoorMask.edge(e.getValue(), 1);
            helper.assertValueEqual(entry.maskAtRotation0, expected, e.getKey() + " door mask");
            helper.assertTrue(entry.meta.requires.isEmpty() && entry.meta.provides.isEmpty(),
                    e.getKey() + " requires and provides nothing");
            for (int turns = 0; turns < 4; turns++) {
                int rotated = entry.maskAtRotation(turns);
                helper.assertValueEqual(Integer.bitCount(DoorMask.story(rotated, 0)), 1,
                        e.getKey() + " keeps one top door at turn " + turns);
                helper.assertValueEqual(Integer.bitCount(DoorMask.story(rotated, 1)), 1,
                        e.getKey() + " keeps one lower door at turn " + turns);
            }
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 200)
    public void stairRoomsStampWithOnlyTheirTwoDoorsOpen(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = nearOrigin(helper);
        forceLoad(level, origin, true);
        try {
            for (Map.Entry<String, DoorMask.Direction> e : LOWER_DOOR.entrySet()) {
                clear(level, origin);
                stamp(level, origin, e.getKey());
                for (DoorMask.Direction wall : DoorMask.Direction.values()) {
                    boolean topOpen = wall == DoorMask.Direction.WEST;
                    boolean lowerOpen = wall == e.getValue();
                    helper.assertTrue(slotOpen(level, origin, wall) == topOpen,
                            e.getKey() + ": top story " + wall + " door " + (topOpen ? "open" : "walled"));
                    helper.assertTrue(slotOpen(level, origin.below(H), wall) == lowerOpen,
                            e.getKey() + ": lower story " + wall + " door " + (lowerOpen ? "open" : "walled"));
                }
                int ladders = 0;
                int stairs = 0;
                for (int x = 0; x < RoomGeometry.CELL; x++) {
                    for (int y = -H; y <= RoomGeometry.CEILING_Y; y++) {
                        for (int z = 0; z < RoomGeometry.CELL; z++) {
                            var state = level.getBlockState(origin.offset(x, y, z));
                            if (state.is(Blocks.LADDER)) {
                                ladders++;
                            }
                            if (state.is(Blocks.STONE_BRICK_STAIRS) && x >= 4 && x <= 11 && (z == 7 || z == 8)) {
                                stairs++;
                            }
                        }
                    }
                }
                helper.assertValueEqual(ladders, 0, e.getKey() + " has no ladder");
                helper.assertValueEqual(stairs, 16, e.getKey() + " has eight steps two wide");
                helper.assertTrue(ReturnPathValidator.validate(level, origin, 2),
                        e.getKey() + " passes the return path check");
            }
        } finally {
            clear(level, origin);
            forceLoad(level, origin, false);
        }
        helper.succeed();
    }

    /**
     * A mob has a path from the top landing to the lower door in every stair room. The mob is marked on the
     * ground first: a navigation refuses to plan until its mob stands on something.
     */
    @GameTest(maxTicks = 100)
    public void aMobFindsAPathDownEveryStairRoom(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = nearOrigin(helper);
        forceLoad(level, origin, true);
        Mob mob = null;
        try {
            for (String room : new java.util.TreeSet<>(LOWER_DOOR.keySet())) {
                clear(level, origin);
                stamp(level, origin, room);
                mob = spawnHusk(level, origin.offset(2, 1, 7));
                mob.setOnGround(true);
                BlockPos goal = lowerLanding(origin, LOWER_DOOR.get(room));
                Path path = mob.getNavigation().createPath(goal, 0);
                helper.assertTrue(path != null && path.canReach(),
                        room + ": a mob has a path from the top landing to the lower door (nodes "
                                + nodes(path, origin) + ")");
                int lowest = Integer.MAX_VALUE;
                for (int n = 0; n < path.getNodeCount(); n++) {
                    lowest = Math.min(lowest, path.getNode(n).y);
                    if (n > 0) {
                        // A mob may also step off the side of a run near the bottom, a drop it takes freely.
                        helper.assertTrue(Math.abs(path.getNode(n).y - path.getNode(n - 1).y) <= 3,
                                room + ": no step of the path drops more than a mob can fall ("
                                        + nodes(path, origin) + ")");
                    }
                }
                helper.assertTrue(lowest <= origin.getY() - H + 1,
                        room + ": the path reaches the lower story (lowest node " + lowest + ")");
                mob.discard();
                mob = null;
            }
        } finally {
            if (mob != null) {
                mob.discard();
            }
            clear(level, origin);
            forceLoad(level, origin, false);
        }
        helper.succeed();
    }

    /**
     * The mob does not just have a path: it walks it, down the stairs, to the lower door. The mob is ticked by
     * hand on any tick the world did not tick it (a chunk outside the test area may not entity tick).
     */
    @GameTest(maxTicks = 700)
    public void aMobWalksDownTheStairs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = nearOrigin(helper);
        forceLoad(level, origin, true);
        clear(level, origin);
        stamp(level, origin, "stair_straight");
        BlockPos goal = lowerLanding(origin, DoorMask.Direction.EAST);
        Mob mob = spawnHusk(level, origin.offset(2, 1, 7));
        int[] lowest = {Integer.MAX_VALUE};
        int[] lastTicks = {-1};
        double[] farthest = {-1e9};
        for (int tick = 1; tick <= 600; tick++) {
            helper.runAfterDelay(tick, () -> {
                if (mob.tickCount == lastTicks[0]) {
                    mob.tick();
                }
                lastTicks[0] = mob.tickCount;
                lowest[0] = Math.min(lowest[0], (int) Math.floor(mob.getY()));
                farthest[0] = Math.max(farthest[0], mob.getX());
                if (tickIsFifth(mob) && !mob.getNavigation().isInProgress()
                        && mob.distanceToSqr(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5) > 4.0) {
                    mob.getNavigation().moveTo(goal.getX() + 0.5, goal.getY(), goal.getZ() + 0.5, 1.0);
                }
            });
        }
        helper.runAfterDelay(620, () -> {
            try {
                helper.assertTrue(lowest[0] <= origin.getY() - H + 2,
                        "the mob walked down the stairs to the lower story (lowest block y " + lowest[0]
                                + ", lower floor " + (origin.getY() - H) + ", mob at "
                                + (mob.getX() - origin.getX()) + "," + (mob.getY() - origin.getY()) + ","
                                + (mob.getZ() - origin.getZ()) + " ticks " + mob.tickCount + ")");
                helper.assertTrue(farthest[0] > origin.getX() + 11,
                        "the mob came out the far end of the run (farthest x " + (farthest[0] - origin.getX()) + ")");
            } finally {
                mob.discard();
                clear(level, origin);
                forceLoad(level, origin, false);
            }
            helper.succeed();
        });
    }

    private static boolean tickIsFifth(Mob mob) {
        return mob.tickCount % 5 == 0;
    }

    // ---- helpers -------------------------------------------------------------------------------------

    private static Mob spawnHusk(ServerLevel level, BlockPos at) {
        Mob mob = (Mob) EntityTypes.HUSK.spawn(level, at, EntitySpawnReason.TRIGGERED);
        mob.setPersistenceRequired();
        return mob;
    }

    private static void dump(ServerLevel level, BlockPos origin, String room) {
        StringBuilder sb = new StringBuilder("DEBUG " + room + " slice z=7 (rows y from top)" + System.lineSeparator());
        for (int y = RoomGeometry.CEILING_Y + 1; y >= -H - 1; y--) {
            sb.append(String.format("%3d ", y));
            for (int x = -1; x <= RoomGeometry.CELL; x++) {
                var s = level.getBlockState(origin.offset(x, y, 7));
                sb.append(s.isAir() ? '.' : s.is(Blocks.STONE_BRICK_STAIRS) ? 'S' : s.is(Blocks.BEDROCK) ? 'B'
                        : s.is(Blocks.JIGSAW) ? 'J' : '#');
            }
            sb.append(System.lineSeparator());
        }
        PocketDungeonsMod.LOG.info(sb.toString());
    }

    private static String nodes(Path path, BlockPos origin) {
        if (path == null) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.getNodeCount(); i++) {
            sb.append('[').append(path.getNode(i).x - origin.getX()).append(',')
                    .append(path.getNode(i).y - origin.getY()).append(',')
                    .append(path.getNode(i).z - origin.getZ()).append(']');
        }
        return sb.toString();
    }

    private static BlockPos lowerLanding(BlockPos origin, DoorMask.Direction lowerDoor) {
        return switch (lowerDoor) {
            case EAST -> origin.offset(13, -H + 1, 7);
            case NORTH -> origin.offset(7, -H + 1, 2);
            case SOUTH -> origin.offset(7, -H + 1, 13);
            case WEST -> origin.offset(2, -H + 1, 7);
        };
    }

    /** True when the whole 2 by 3 doorway on {@code wall} of the story at {@code storyOrigin} is air. */
    private static boolean slotOpen(ServerLevel level, BlockPos storyOrigin, DoorMask.Direction wall) {
        List<BlockPos> slots = CellGeometry.doorSlotPositions(storyOrigin, wall);
        for (BlockPos pos : slots) {
            if (!level.getBlockState(pos).isAir()) {
                return false;
            }
        }
        return true;
    }

    private static void stamp(ServerLevel level, BlockPos origin, String room) {
        TemplateStamper.place(level, level.getServer().getStructureManager(), origin,
                Identifier.parse("pocketdungeons:rooms/" + room), 0, 1L, null);
    }

    private static BlockPos nearOrigin(GameTestHelper helper) {
        BlockPos near = helper.absolutePos(BlockPos.ZERO);
        return new BlockPos((near.getX() >> 4) << 4, near.getY() + 24, (near.getZ() >> 4) << 4);
    }

    private static void forceLoad(ServerLevel level, BlockPos origin, boolean forced) {
        ChunkPos chunk = new ChunkPos(origin.getX() >> 4, origin.getZ() >> 4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setChunkForced(chunk.x() + dx, chunk.z() + dz, forced);
            }
        }
    }

    private static void clear(ServerLevel level, BlockPos origin) {
        for (int x = -2; x <= RoomGeometry.CELL + 1; x++) {
            for (int z = -2; z <= RoomGeometry.CELL + 1; z++) {
                for (int y = -H - 2; y <= RoomGeometry.CEILING_Y + 2; y++) {
                    level.setBlock(origin.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }
}
