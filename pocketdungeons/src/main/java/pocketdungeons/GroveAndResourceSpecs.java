package pocketdungeons;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Grove family (three templates re-skinned by the grove processor lists) and two act 1
 * resource rooms (charnel_niches, root_sandbar). Authored with RoomDsl, local coordinates.
 * Trunks use WALL (becomes log or mossy cobble) and canopy or hedges use ACCENT (becomes
 * leaves or rooted dirt); fallen logs are real oak logs.
 */
final class GroveAndResourceSpecs {

    private static final BlockState W = RoomDsl.WALL;
    private static final BlockState A = RoomDsl.ACCENT;
    private static final BlockState AIR = RoomDsl.AIR;

    private GroveAndResourceSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(groveTee());
        specs.add(grovePath());
        specs.add(groveGlade());
        specs.add(charnelNiches());
        specs.add(rootSandbar());
        return specs;
    }

    private static void plant(ServerLevel level, BlockPos o, BlockState state, int[][] cells) {
        for (int[] c : cells) {
            RoomDsl.put(level, o, c[0], 1, c[1], state);
        }
    }

    /** A small tree: trunk two high, a 3x3 canopy at y3 and a plus at y4. */
    private static void smallTree(ServerLevel level, BlockPos o, int x, int z) {
        RoomDsl.column(level, o, x, 1, 2, z, W);
        RoomDsl.box(level, o, x - 1, 3, z - 1, x + 1, 3, z + 1, A);
        RoomDsl.put(level, o, x, 4, z, A);
        RoomDsl.put(level, o, x - 1, 4, z, A);
        RoomDsl.put(level, o, x + 1, 4, z, A);
        RoomDsl.put(level, o, x, 4, z - 1, A);
        RoomDsl.put(level, o, x, 4, z + 1, A);
    }

    // ---- A1. Grove tee ----

    private static RoomSpec groveTee() {
        return new RoomSpec("grove_tee", EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(5, 1, 8), new BlockPos(8, 1, 5), new BlockPos(11, 1, 8), new BlockPos(8, 1, 11))
                .decor((level, o) -> {
                    // The great tree: trunk 3 high, canopy 5x5 minus corners at y4, 3x3 at y5, lantern heart.
                    RoomDsl.column(level, o, 8, 1, 3, 8, W);
                    RoomDsl.box(level, o, 6, 4, 6, 10, 4, 10, A);
                    RoomDsl.put(level, o, 6, 4, 6, AIR);
                    RoomDsl.put(level, o, 10, 4, 6, AIR);
                    RoomDsl.put(level, o, 6, 4, 10, AIR);
                    RoomDsl.put(level, o, 10, 4, 10, AIR);
                    RoomDsl.put(level, o, 8, 4, 8, Blocks.SEA_LANTERN.defaultBlockState());
                    RoomDsl.box(level, o, 7, 5, 7, 9, 5, 9, A);
                    // Two small trees in the west half.
                    smallTree(level, o, 3, 4);
                    smallTree(level, o, 3, 12);
                    // A fallen log.
                    RoomDsl.put(level, o, 5, 1, 11, RoomDsl.log(Direction.Axis.X));
                    RoomDsl.put(level, o, 6, 1, 11, RoomDsl.log(Direction.Axis.X));
                    // A leafy nook around the chest at (13,1,13); open on the north and west sides.
                    RoomDsl.box(level, o, 14, 1, 12, 14, 2, 14, A);
                    RoomDsl.box(level, o, 12, 1, 14, 13, 2, 14, A);
                    RoomDsl.put(level, o, 13, 2, 13, A);
                    // Understory.
                    plant(level, o, Blocks.SHORT_GRASS.defaultBlockState(), new int[][] {{6, 4}, {10, 10}, {4, 9}, {12, 9}});
                    plant(level, o, Blocks.FERN.defaultBlockState(), new int[][] {{5, 6}, {11, 11}, {6, 13}, {12, 3}});
                    plant(level, o, Blocks.DANDELION.defaultBlockState(), new int[][] {{10, 5}, {12, 4}, {4, 7}});
                    plant(level, o, Blocks.POPPY.defaultBlockState(), new int[][] {{12, 6}, {9, 12}});
                    plant(level, o, Blocks.AZURE_BLUET.defaultBlockState(), new int[][] {{6, 8}, {10, 13}});
                });
    }

    // ---- A2. Grove path ----

    private static RoomSpec grovePath() {
        return new RoomSpec("grove_path", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 2))
                .spawns(new BlockPos(5, 1, 6), new BlockPos(11, 1, 9), new BlockPos(3, 1, 13), new BlockPos(8, 1, 8))
                .decor((level, o) -> {
                    // Hedge rows with a gap at x6..9.
                    for (int z : new int[] {4, 11}) {
                        RoomDsl.box(level, o, 1, 1, z, 5, 2, z, A);
                        RoomDsl.box(level, o, 10, 1, z, 14, 2, z, A);
                    }
                    // North tree: trunk at (4,z3), canopy arching south over the path.
                    RoomDsl.column(level, o, 4, 1, 3, 3, W);
                    RoomDsl.box(level, o, 3, 4, 2, 5, 4, 8, A);
                    RoomDsl.box(level, o, 4, 5, 3, 4, 5, 7, A);
                    // South tree: trunk at (11,z12), canopy arching north over the path.
                    RoomDsl.column(level, o, 11, 1, 3, 12, W);
                    RoomDsl.box(level, o, 10, 4, 8, 12, 4, 14, A);
                    RoomDsl.box(level, o, 11, 5, 9, 11, 5, 13, A);
                    // A fallen log on the path.
                    RoomDsl.put(level, o, 9, 1, 10, RoomDsl.log(Direction.Axis.X));
                    RoomDsl.put(level, o, 10, 1, 10, RoomDsl.log(Direction.Axis.X));
                    // Flowers and ferns.
                    plant(level, o, Blocks.SHORT_GRASS.defaultBlockState(), new int[][] {{7, 5}, {9, 6}, {6, 9}, {8, 13}, {2, 2}});
                    plant(level, o, Blocks.FERN.defaultBlockState(), new int[][] {{12, 2}, {6, 2}, {2, 12}, {13, 13}});
                    plant(level, o, Blocks.DANDELION.defaultBlockState(), new int[][] {{7, 3}, {9, 2}, {7, 6}});
                    plant(level, o, Blocks.POPPY.defaultBlockState(), new int[][] {{10, 3}, {6, 12}, {12, 5}});
                    plant(level, o, Blocks.AZURE_BLUET.defaultBlockState(), new int[][] {{8, 12}, {13, 10}});
                });
    }

    // ---- A3. Grove glade ----

    private static RoomSpec groveGlade() {
        return new RoomSpec("grove_glade", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(12, 1, 9))
                .spawns(new BlockPos(5, 1, 8), new BlockPos(8, 1, 4), new BlockPos(8, 1, 12), new BlockPos(13, 1, 5))
                .decor((level, o) -> {
                    // The ancient tree: trunk 4 high at (11,z8), wide canopy at y4 and y5.
                    RoomDsl.column(level, o, 11, 1, 4, 8, W);
                    RoomDsl.box(level, o, 9, 4, 6, 13, 4, 10, A);
                    RoomDsl.put(level, o, 11, 4, 8, W);
                    RoomDsl.put(level, o, 9, 4, 6, AIR);
                    RoomDsl.put(level, o, 13, 4, 6, AIR);
                    RoomDsl.put(level, o, 9, 4, 10, AIR);
                    RoomDsl.put(level, o, 13, 4, 10, AIR);
                    RoomDsl.box(level, o, 8, 5, 5, 14, 5, 11, A);
                    RoomDsl.put(level, o, 8, 5, 5, AIR);
                    RoomDsl.put(level, o, 14, 5, 5, AIR);
                    RoomDsl.put(level, o, 8, 5, 11, AIR);
                    RoomDsl.put(level, o, 14, 5, 11, AIR);
                    // Root flares; the chest sits between two of them.
                    RoomDsl.put(level, o, 11, 1, 9, W);
                    RoomDsl.put(level, o, 12, 1, 8, W);
                    RoomDsl.put(level, o, 11, 1, 7, W);
                    // Fallen log bench.
                    RoomDsl.put(level, o, 4, 1, 5, RoomDsl.log(Direction.Axis.X));
                    RoomDsl.put(level, o, 5, 1, 5, RoomDsl.log(Direction.Axis.X));
                    // Ring of flowers round the tree.
                    plant(level, o, Blocks.POPPY.defaultBlockState(), new int[][] {{8, 8}, {9, 6}, {13, 10}});
                    plant(level, o, Blocks.DANDELION.defaultBlockState(), new int[][] {{9, 10}, {11, 5}, {14, 8}});
                    plant(level, o, Blocks.AZURE_BLUET.defaultBlockState(), new int[][] {{11, 11}, {13, 6}});
                    plant(level, o, Blocks.FLOWERING_AZALEA.defaultBlockState(), new int[][] {{13, 12}});
                    plant(level, o, Blocks.AZALEA.defaultBlockState(), new int[][] {{6, 11}});
                    plant(level, o, Blocks.SHORT_GRASS.defaultBlockState(), new int[][] {{6, 6}, {5, 10}, {10, 12}, {12, 3}});
                    plant(level, o, Blocks.FERN.defaultBlockState(), new int[][] {{7, 5}, {13, 2}, {3, 11}});
                });
    }

    // ---- B1. Charnel niches (ossuary) ----

    private static RoomSpec charnelNiches() {
        return new RoomSpec("charnel_niches", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 5))
                .spawns(new BlockPos(4, 1, 6), new BlockPos(7, 1, 8), new BlockPos(10, 1, 6), new BlockPos(11, 1, 8))
                .decor((level, o) -> {
                    RoomDsl.box(level, o, 1, 1, 1, 14, 5, 14, W);
                    // The tunnel, path z6..8.
                    RoomDsl.box(level, o, 1, 1, 6, 14, 4, 8, AIR);
                    // Burial niches, 2 wide and 2 high, one deep, under the rock lintel at y3.
                    for (int x : new int[] {2, 5, 9, 12}) {
                        RoomDsl.box(level, o, x, 1, 5, x + 1, 2, 5, AIR);
                        RoomDsl.box(level, o, x, 1, 9, x + 1, 2, 9, AIR);
                    }
                    // Two niches with a skull and candles (baked, not nodes).
                    RoomDsl.put(level, o, 9, 1, 5, Blocks.SKELETON_SKULL.defaultBlockState());
                    RoomDsl.put(level, o, 10, 1, 5, RoomDsl.candle(3));
                    RoomDsl.put(level, o, 2, 1, 9, Blocks.SKELETON_SKULL.defaultBlockState());
                    RoomDsl.put(level, o, 3, 1, 9, RoomDsl.candle(2));
                });
    }

    // ---- B2. Root sandbar (rootworks) ----

    private static RoomSpec rootSandbar() {
        return new RoomSpec("root_sandbar", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(4, 1, 6), new BlockPos(8, 1, 9), new BlockPos(11, 1, 7), new BlockPos(6, 1, 12))
                .decor((level, o) -> {
                    // Two root pillars, three logs each.
                    RoomDsl.column(level, o, 6, 1, 3, 9, Blocks.OAK_LOG.defaultBlockState());
                    RoomDsl.column(level, o, 10, 1, 3, 6, Blocks.OAK_LOG.defaultBlockState());
                    // Roots hanging from the ceiling.
                    int[][] roots = {{4, 6}, {8, 5}, {12, 9}, {7, 10}, {11, 4}};
                    for (int[] r : roots) {
                        RoomDsl.put(level, o, r[0], 5, r[1], Blocks.ROOTED_DIRT.defaultBlockState());
                        RoomDsl.put(level, o, r[0], 4, r[1], Blocks.HANGING_ROOTS.defaultBlockState());
                    }
                    // Cave vines with berries.
                    RoomDsl.vines(level, o, 6, 6);
                    RoomDsl.vines(level, o, 9, 10);
                    RoomDsl.vines(level, o, 12, 5);
                    // Moss, and a big dripleaf on a moss block.
                    int[][] moss = {{9, 9}, {10, 9}, {10, 10}, {2, 5}, {2, 10}};
                    plant(level, o, Blocks.MOSS_CARPET.defaultBlockState(), moss);
                    RoomDsl.put(level, o, 13, 0, 10, Blocks.MOSS_BLOCK.defaultBlockState());
                    RoomDsl.put(level, o, 13, 1, 10, Blocks.BIG_DRIPLEAF.defaultBlockState());
                });
    }
}
