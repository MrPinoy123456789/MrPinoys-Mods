package pocketdungeons;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Room library geometry: each listed room, built exactly as the generator builds it before capture,
 * connects every one of its doors to every other door by a walkable route with two blocks of headroom,
 * and every chest and spawn point can be reached. The usual authoring bug (a decor block in a door
 * lane, a pocket sealing a doorway) fails here before a template is ever generated.
 *
 * <p>Every room in the listed new spec files is checked automatically ({@link #checked()}). {@link #everyLibraryRoomReport}
 * walks the whole library and only logs, so older rooms are never blocked by it.
 */
public final class RoomLibraryGameTest {

    /** Rooms inside shared spec files (not whole files) that must pass. */
    private static final List<String> NAMED = List.of("mineshaft_junction", "mineshaft_dead_end");

    /**
     * Spec files whose every room must pass: a room added to one of these is checked automatically,
     * so authoring a room never needs an edit here. Looked up by name so a file that does not exist
     * yet is skipped.
     */
    private static final String[] CHECKED_SPEC_CLASSES = {
            "ActOneRoomSpecs", "FinalFloorSpecs", "FinalFloorEarlySpecs", "FinalFloorLateSpecs",
            "GroveAndResourceSpecs", "CowWardSpecs",
            "CopperWorksRoomSpecs", "FrostworksRoomSpecs", "DeepslateRoomSpecs", "EnderArchiveRoomSpecs",
            "GenericHallVariantSpecs", "SculkRoomSpecs"};

    /** The rooms that must pass: {@link #NAMED} plus every room of {@link #CHECKED_SPEC_CLASSES}. */
    private static List<String> checked() {
        List<String> names = new ArrayList<>(NAMED);
        for (String className : CHECKED_SPEC_CLASSES) {
            try {
                java.lang.reflect.Method list = Class.forName("pocketdungeons." + className).getDeclaredMethod("list");
                list.setAccessible(true);
                for (Object spec : (List<?>) list.invoke(null)) {
                    names.add(((RoomSpec) spec).name);
                }
            } catch (ClassNotFoundException missing) {
                // not authored yet
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("cannot list " + className, e);
            }
        }
        return names;
    }

    @GameTest(maxTicks = 400)
    public void newRoomsConnectEveryDoor(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        StringBuilder failures = new StringBuilder();
        for (String name : checked()) {
            failures.append(check(level, o, name));
        }
        if (!failures.isEmpty()) {
            helper.fail(failures.toString());
            return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks = 400)
    public void everyLibraryRoomReport(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO);
        int bad = 0;
        for (String name : RoomTemplateGenerator.specNames()) {
            String problem = check(level, o, name);
            if (!problem.isEmpty()) {
                bad++;
                PocketDungeonsMod.LOG.info("Room library check: {}", problem.trim());
            }
        }
        PocketDungeonsMod.LOG.info("Room library check: {} room(s) with a geometry finding", bad);
        helper.succeed();
    }

    /** Builds the room at {@code o}, checks it, clears it. Returns findings, one line each, or an empty string. */
    private static String check(ServerLevel level, BlockPos o, String name) {
        RoomSpec spec = RoomTemplateGenerator.specNamed(name);
        if (spec == null) {
            return name + ": no such spec\n";
        }
        clear(level, o, spec.spanY);
        try {
            RoomTemplateGenerator.buildForCheck(level, o, spec);
            return walk(level, o, spec);
        } finally {
            clear(level, o, spec.spanY);
        }
    }

    private static String walk(ServerLevel level, BlockPos o, RoomSpec spec) {
        StringBuilder out = new StringBuilder();
        List<BlockPos> entries = new ArrayList<>();
        for (Direction door : spec.doors) {
            entries.add(switch (door) {
                case WEST -> new BlockPos(1, 1, 7);
                case EAST -> new BlockPos(14, 1, 7);
                case NORTH -> new BlockPos(7, 1, 1);
                default -> new BlockPos(7, 1, 14);
            });
        }
        if (entries.isEmpty()) {
            return spec.name + ": no doors\n";
        }
        for (BlockPos entry : entries) {
            if (!walkable(level, o, entry)) {
                out.append(spec.name).append(": the doorway inside ").append(entry.toShortString())
                        .append(" is not walkable\n");
            }
        }
        Set<BlockPos> reached = new HashSet<>();
        flood(level, o, entries.get(0), reached);
        for (BlockPos entry : entries) {
            if (!reached.contains(entry)) {
                out.append(spec.name).append(": door entry ").append(entry.toShortString())
                        .append(" is cut off from ").append(entries.get(0).toShortString()).append('\n');
            }
        }
        for (BlockPos spawn : spec.spawns) {
            if (!reached.contains(spawn)) {
                out.append(spec.name).append(": spawn point ").append(spawn.toShortString())
                        .append(" is not reachable\n");
            }
        }
        for (BlockPos chest : spec.chests) {
            boolean beside = false;
            for (Direction d : Direction.Plane.HORIZONTAL) {
                if (reached.contains(chest.relative(d))) {
                    beside = true;
                }
            }
            if (!beside) {
                out.append(spec.name).append(": chest ").append(chest.toShortString()).append(" cannot be reached\n");
            }
        }
        return out.toString();
    }

    /** Flood fill over walkable cells (local coordinates): 4 neighbours, step up 1, drop up to 3. */
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

    /** Feet and head cells are open and the block below holds a player up. Local coordinates. */
    private static boolean walkable(ServerLevel level, BlockPos o, BlockPos local) {
        BlockPos feet = o.offset(local);
        return open(level, feet) && open(level, feet.above()) && !open(level, feet.below());
    }

    /** Whether a player passes through the block: no collision, or a jigsaw marker (erased at stamp time). */
    private static boolean open(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(Blocks.JIGSAW) || state.getCollisionShape(level, pos).isEmpty();
    }

    private static void clear(ServerLevel level, BlockPos o, int spanY) {
        int below = RoomGeometry.storyOffset(spanY);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -below - 2; y <= RoomGeometry.CEILING_Y + 1; y++) {
                    level.setBlock(o.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }
}
