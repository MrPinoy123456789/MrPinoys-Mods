package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Extra act 1 rooms. Every room is authored at rotation 0 with interior x and z 1 to 14 and
 * y 1 to 5, and keeps the door lanes clear.
 */
final class ActOneRoomSpecs {

    private ActOneRoomSpecs() {}

    private static final BlockState COBBLE = Blocks.COBBLESTONE.defaultBlockState();
    private static final BlockState INFESTED = Blocks.INFESTED_COBBLESTONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState WEB = Blocks.COBWEB.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(gnawedVein());
        return specs;
    }

    private static void fill(ServerLevel level, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2,
                             BlockState state) {
        ResourceBiomeSpecs.fill(level, o, x1, y1, z1, x2, y2, z2, state);
    }

    private static void put(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        RoomBuilder.set(level, o.offset(x, y, z), state);
    }

    /**
     * Gnawed vein: a cobblestone tunnel chewed by silverfish. Ore shows in pockets one block deep in the
     * walls (declared as metadata nodes), but the rock directly behind each pocket is infested, so
     * digging deeper wakes silverfish.
     */
    private static RoomSpec gnawedVein() {
        return new RoomSpec("gnawed_vein", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 6))
                .spawns(new BlockPos(3, 1, 8), new BlockPos(6, 1, 6), new BlockPos(9, 1, 8), new BlockPos(12, 1, 6))
                .decor((level, o) -> {
                    fill(level, o, 1, 1, 1, 14, 5, 5, COBBLE);
                    fill(level, o, 1, 1, 9, 14, 5, 14, COBBLE);
                    // North pockets (z 5) with infested backing (z 4).
                    fill(level, o, 3, 1, 5, 5, 3, 5, AIR);
                    fill(level, o, 10, 1, 5, 12, 3, 5, AIR);
                    fill(level, o, 3, 1, 4, 5, 3, 4, INFESTED);
                    fill(level, o, 10, 1, 4, 12, 3, 4, INFESTED);
                    // South pockets (z 9) with infested backing (z 10).
                    fill(level, o, 5, 1, 9, 7, 3, 9, AIR);
                    fill(level, o, 11, 1, 9, 13, 3, 9, AIR);
                    fill(level, o, 5, 1, 10, 7, 3, 10, INFESTED);
                    fill(level, o, 11, 1, 10, 13, 3, 10, INFESTED);
                    // Webs: in the path, and across the mouth of the first north pocket.
                    put(level, o, 7, 4, 8, WEB);
                    put(level, o, 10, 4, 6, WEB);
                    put(level, o, 4, 4, 6, WEB);
                });
    }
}
