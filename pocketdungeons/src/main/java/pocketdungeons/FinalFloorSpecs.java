package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Rooms written for the final floors of the dungeons, where a generic room undersells the
 * floor's name (see docs/reference/FINAL_FLOOR_ROOMS.md). Authored at rotation 0 in room-local
 * coordinates with {@link RoomDsl}; the west and east door lanes (z 7 to 8 for x 1 to 3 and 12
 * to 14) are left clear.
 */
final class FinalFloorSpecs {

    private FinalFloorSpecs() {}

    private static final BlockState DRIPSTONE = Blocks.DRIPSTONE_BLOCK.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(greatDripCavern());
        return specs;
    }

    /** A pointed dripstone cell pointing up or down at the given thickness. */
    private static BlockState pointed(Direction tip, SpeleothemThickness thickness) {
        return Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(BlockStateProperties.VERTICAL_DIRECTION, tip)
                .setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, thickness);
    }

    /** A stalactite: a ceiling block at y=5, a base at y=4 and a tip at y=3 (two open blocks beneath). */
    private static void stalactite(ServerLevel level, BlockPos o, int x, int z) {
        put(level, o, x, 5, z, DRIPSTONE);
        put(level, o, x, 4, z, pointed(Direction.DOWN, SpeleothemThickness.BASE));
        put(level, o, x, 3, z, pointed(Direction.DOWN, SpeleothemThickness.TIP));
    }

    private static void stalagmite(ServerLevel level, BlockPos o, int x, int z) {
        put(level, o, x, 1, z, pointed(Direction.UP, SpeleothemThickness.TIP));
    }

    private static void basin(ServerLevel level, BlockPos o, int x, int z) {
        put(level, o, x, 1, z, Blocks.WATER_CAULDRON.defaultBlockState().setValue(LayeredCauldronBlock.LEVEL, 3));
    }

    /**
     * The Great Drip Cavern (Lush Caves final floor): a vast wet cavern. Dripstone pillars run
     * floor to ceiling, stalactites hang over the middle with two water basins beneath them,
     * moss and cave vines soften the floor and roof. Clay, sand and iron are declared in the
     * manifest over the air pockets left here.
     */
    private static RoomSpec greatDripCavern() {
        return new RoomSpec("great_drip_cavern", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(6, 1, 4), new BlockPos(9, 1, 11), new BlockPos(6, 1, 11),
                        new BlockPos(9, 1, 4))
                .decor((level, o) -> {
                    // Ceiling: patches of dripstone so the roof reads as rock, not a flat lid.
                    for (int x = 1; x <= 14; x++) {
                        for (int z = 1; z <= 14; z++) {
                            if ((x * 7 + z * 3) % 5 == 0) {
                                put(level, o, x, 5, z, DRIPSTONE);
                            }
                        }
                    }
                    // Moss carpet patches on the floor (never in the sand, clay or iron pockets).
                    BlockState moss = Blocks.MOSS_CARPET.defaultBlockState();
                    for (int x = 2; x <= 13; x++) {
                        for (int z = 2; z <= 13; z++) {
                            boolean sand = x >= 12 && z >= 10 && z <= 11;
                            boolean clay = x >= 7 && x <= 8 && z >= 12;
                            if ((x * 5 + z * 11) % 4 == 0 && !sand && !clay) {
                                put(level, o, x, 1, z, moss);
                            }
                        }
                    }
                    // Floor to ceiling dripstone pillars.
                    int[][] pillars = {{4, 4}, {11, 11}, {4, 11}, {11, 4}};
                    for (int[] p : pillars) {
                        column(level, o, p[0], 1, 5, p[1], DRIPSTONE);
                    }
                    // Stalactites over the central cavern; two of them drip into basins.
                    int[][] stalactites = {{6, 5}, {9, 10}, {7, 7}, {8, 9}, {5, 8}, {10, 7}, {7, 11}, {9, 3}};
                    for (int[] s : stalactites) {
                        stalactite(level, o, s[0], s[1]);
                    }
                    basin(level, o, 6, 5);
                    basin(level, o, 9, 10);
                    // Stalagmites on the floor, clear of the walking routes.
                    int[][] stalagmites = {{2, 2}, {13, 2}, {2, 13}, {5, 13}, {10, 2}, {12, 6}};
                    for (int[] s : stalagmites) {
                        stalagmite(level, o, s[0], s[1]);
                    }
                    // Lush touches.
                    put(level, o, 3, 1, 5, Blocks.AZALEA.defaultBlockState());
                    put(level, o, 12, 1, 9, Blocks.FLOWERING_AZALEA.defaultBlockState());
                    put(level, o, 6, 1, 8, Blocks.BIG_DRIPLEAF.defaultBlockState());
                    put(level, o, 3, 1, 10, Blocks.BIG_DRIPLEAF.defaultBlockState());
                    BlockState spore = Blocks.SPORE_BLOSSOM.defaultBlockState();
                    put(level, o, 3, 5, 3, spore);
                    put(level, o, 12, 5, 12, spore);
                    put(level, o, 6, 5, 9, spore);
                    put(level, o, 10, 5, 5, spore);
                    put(level, o, 2, 5, 10, spore);
                    RoomDsl.vines(level, o, 2, 4);
                    RoomDsl.vines(level, o, 13, 10);
                    RoomDsl.vines(level, o, 6, 13);
                    RoomDsl.vines(level, o, 9, 2);
                    RoomDsl.vines(level, o, 12, 5);
                    // The sand shelf, clay bed and east wall iron pocket are left as air for the
                    // manifest nodes (sand rests on the solid floor row below).
                    box(level, o, 12, 1, 10, 13, 1, 11, RoomDsl.AIR);
                    box(level, o, 7, 1, 12, 8, 1, 13, RoomDsl.AIR);
                    box(level, o, 14, 1, 3, 14, 3, 5, RoomDsl.AIR);
                });
    }
}
