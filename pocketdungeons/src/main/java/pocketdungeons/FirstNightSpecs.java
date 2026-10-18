package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SpeleothemThickness;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.LAMP;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * The thematic rooms of Act 0, The First Night: a treeline, a rock outcrop, a coal hillside, an iron
 * ravine and a cave mouth. Floors 1 to 4 are gather floors with no encounter or loot cells, so their
 * rooms are corridor rooms with no spawn points and no chests; the gather objective stands its own
 * logs, stone, coal and iron on open floor, so the features sit in the four corners and against closed
 * walls and the middle cross stays open. Each biome comes in four door shapes (straight, bend, fork
 * and nook) that share one decor routine; closed walls get a wall hugging band, so the shapes differ.
 * Authored at rotation 0 in room-local coordinates.
 */
final class FirstNightSpecs {

    private FirstNightSpecs() {}

    private enum Biome { TREELINE, STONE_RISE, BLACK_SEAM, RUST_RAVINE }

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        addBiome(specs, Biome.TREELINE, "treeline");
        addBiome(specs, Biome.STONE_RISE, "stone_rise");
        addBiome(specs, Biome.BLACK_SEAM, "black_seam");
        addBiome(specs, Biome.RUST_RAVINE, "rust_ravine");
        specs.add(caveMouthNook());
        specs.add(caveMouthRun());
        return specs;
    }

    private static void addBiome(List<RoomSpec> specs, Biome biome, String stem) {
        specs.add(biomeRoom(biome, stem + "_run", 0, EnumSet.of(Direction.WEST, Direction.EAST)));
        specs.add(biomeRoom(biome, stem + "_bend", 1, EnumSet.of(Direction.WEST, Direction.NORTH)));
        specs.add(biomeRoom(biome, stem + "_fork", 2,
                EnumSet.of(Direction.WEST, Direction.EAST, Direction.SOUTH)));
        specs.add(biomeRoom(biome, stem + "_nook", 3, EnumSet.of(Direction.WEST)));
    }

    private static RoomSpec biomeRoom(Biome biome, String name, int shape, Set<Direction> doors) {
        return new RoomSpec(name, doors).decor((level, o) -> {
            switch (biome) {
                case TREELINE -> treeline(level, o, shape, doors);
                case STONE_RISE -> stoneRise(level, o, shape, doors);
                case BLACK_SEAM -> blackSeam(level, o, shape, doors);
                case RUST_RAVINE -> rustRavine(level, o, shape, doors);
            }
            // Every room is lit: a lamp in each ceiling corner. The theme turns the placeholder into a lantern.
            quad4(level, o, 5, 5, 5, LAMP);
        });
    }

    // ---- shared helpers ----

    /** Places one block in all four quadrants (x and z mirrored about the room centre). */
    private static void quad4(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        put(level, o, x, y, z, state);
        put(level, o, 15 - x, y, z, state);
        put(level, o, x, y, 15 - z, state);
        put(level, o, 15 - x, y, 15 - z, state);
    }

    /** Places the same box in all four quadrants. */
    private static void qbox(ServerLevel level, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2,
                             BlockState state) {
        box(level, o, x1, y1, z1, x2, y2, z2, state);
        box(level, o, 15 - x1, y1, z1, 15 - x2, y2, z2, state);
        box(level, o, x1, y1, 15 - z1, x2, y2, 15 - z2, state);
        box(level, o, 15 - x1, y1, 15 - z1, 15 - x2, y2, 15 - z2, state);
    }

    /**
     * A wall hugging band against every side the room has no door on. Layer {@code i} of {@code layers} is the
     * block at height {@code i + 1}; {@code depth} is how far it reaches in from the wall.
     */
    private static void closedBands(ServerLevel level, BlockPos o, Set<Direction> doors, int depth,
                                    BlockState[] layers) {
        for (int i = 0; i < layers.length; i++) {
            int y = i + 1;
            BlockState state = layers[i];
            if (!doors.contains(Direction.NORTH)) {
                box(level, o, 1, y, 1, 14, y, depth, state);
            }
            if (!doors.contains(Direction.SOUTH)) {
                box(level, o, 1, y, 15 - depth, 14, y, 14, state);
            }
            if (!doors.contains(Direction.WEST)) {
                box(level, o, 1, y, 1, depth, y, 14, state);
            }
            if (!doors.contains(Direction.EAST)) {
                box(level, o, 15 - depth, y, 1, 14, y, 14, state);
            }
        }
    }

    private static void plants(ServerLevel level, BlockPos o, BlockState state, int[][] spots) {
        for (int[] spot : spots) {
            put(level, o, spot[0], 1, spot[1], state);
        }
    }

    private static BlockState leaves(net.minecraft.world.level.block.Block block) {
        return block.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true);
    }

    // ---- floor 1: the treeline ----

    private static void treeline(ServerLevel level, BlockPos o, int shape, Set<Direction> doors) {
        BlockState trunk = Blocks.DARK_OAK_LOG.defaultBlockState();
        BlockState canopy = leaves(Blocks.DARK_OAK_LEAVES);
        BlockState hedge = leaves(Blocks.OAK_LEAVES);
        BlockState flowering = leaves(Blocks.FLOWERING_AZALEA_LEAVES);
        // A leafy hedge along the closed walls, flecked with azalea blossom.
        closedBands(level, o, doors, 1, new BlockState[] {hedge, flowering, hedge, hedge});
        // A dark oak in each corner: a podzol patch, a trunk and a canopy that closes over it.
        qbox(level, o, 2, 0, 2, 4, 0, 4, Blocks.PODZOL.defaultBlockState());
        qbox(level, o, 2, 4, 2, 5, 5, 5, canopy);
        for (int[] c : new int[][] {{3, 3}, {12, 3}, {3, 12}, {12, 12}}) {
            column(level, o, c[0], 1, 4, c[1], trunk);
        }
        // Undergrowth and flowers between the trunks, clear of the door lanes.
        plants(level, o, Blocks.SHORT_GRASS.defaultBlockState(),
                new int[][] {{6, 5}, {9, 10}, {5, 9}, {10, 4}, {4, 6}, {11, 9}});
        plants(level, o, Blocks.FERN.defaultBlockState(), new int[][] {{5, 4}, {10, 11}, {4, 10}, {11, 5}});
        put(level, o, 6, 1, 3, Blocks.DANDELION.defaultBlockState());
        put(level, o, 9, 1, 12, Blocks.POPPY.defaultBlockState());
        put(level, o, 2, 1, 6, Blocks.BROWN_MUSHROOM.defaultBlockState());
        put(level, o, 13, 1, 9, Blocks.RED_MUSHROOM.defaultBlockState());
        if (shape == 1) {
            // A fallen trunk on its side, in the south east quarter.
            box(level, o, 9, 1, 10, 12, 1, 10, Blocks.DARK_OAK_LOG.defaultBlockState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.X));
        }
        if (shape == 2) {
            // A cold camp: a lit fire ring where someone slept before the night.
            put(level, o, 6, 1, 12, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.LIT, true));
            put(level, o, 4, 1, 12, Blocks.OAK_LOG.defaultBlockState()
                    .setValue(BlockStateProperties.AXIS, Direction.Axis.Z));
        }
        if (shape == 3) {
            // A stump and a berry bush at the end of the trail.
            put(level, o, 10, 1, 7, Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
            put(level, o, 11, 1, 10, Blocks.AZALEA.defaultBlockState());
        }
    }

    // ---- floor 2: the rock outcrop ----

    private static void stoneRise(ServerLevel level, BlockPos o, int shape, Set<Direction> doors) {
        BlockState andesite = Blocks.ANDESITE.defaultBlockState();
        BlockState granite = Blocks.GRANITE.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        // Cut faces along the closed walls, banded like a quarry wall.
        closedBands(level, o, doors, 2, new BlockState[] {cobble, andesite, granite, andesite, tuff});
        // Stepped boulders in the corners, on a bed of worn cobble.
        qbox(level, o, 2, 0, 2, 5, 0, 5, cobble);
        qbox(level, o, 2, 1, 2, 4, 1, 4, andesite);
        qbox(level, o, 2, 2, 2, 3, 2, 3, granite);
        quad4(level, o, 2, 3, 2, andesite);
        // Loose rocks and tufts across the floor.
        for (int[] s : new int[][] {{6, 4}, {9, 11}, {5, 10}, {10, 5}, {11, 9}}) {
            put(level, o, s[0], 1, s[1], s[0] % 2 == 0 ? Blocks.ANDESITE_SLAB.defaultBlockState()
                    : Blocks.COBBLESTONE_SLAB.defaultBlockState());
        }
        put(level, o, 6, 1, 12, Blocks.SHORT_GRASS.defaultBlockState());
        put(level, o, 9, 1, 3, Blocks.FERN.defaultBlockState());
        if (shape == 1) {
            // A leaning spire of stacked rock.
            column(level, o, 6, 1, 4, 6, andesite);
            put(level, o, 6, 5, 6, tuff);
        }
        if (shape == 2) {
            box(level, o, 9, 1, 12, 11, 2, 13, granite);
        }
        if (shape == 3) {
            put(level, o, 10, 1, 10, Blocks.CALCITE.defaultBlockState());
            put(level, o, 10, 2, 10, Blocks.CALCITE.defaultBlockState());
        }
    }

    // ---- floor 3: the coal hillside ----

    private static void blackSeam(ServerLevel level, BlockPos o, int shape, Set<Direction> doors) {
        BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        BlockState coarse = Blocks.COARSE_DIRT.defaultBlockState();
        BlockState andesite = Blocks.ANDESITE.defaultBlockState();
        BlockState coal = Blocks.COAL_ORE.defaultBlockState();
        // A cut in the hillside along the closed walls: turf over earth over a seam of dark rock.
        closedBands(level, o, doors, 2, new BlockState[] {andesite, coal, andesite, dirt, grass});
        // A turf mound in each corner with a coal seam showing in its face.
        qbox(level, o, 2, 0, 2, 5, 0, 5, coarse);
        qbox(level, o, 2, 1, 2, 4, 1, 4, grass);
        qbox(level, o, 2, 2, 2, 3, 2, 3, grass);
        quad4(level, o, 4, 1, 3, coal);
        quad4(level, o, 3, 1, 4, coal);
        // Tufts of grass on the slope.
        plants(level, o, Blocks.SHORT_GRASS.defaultBlockState(), new int[][] {{6, 4}, {9, 11}, {5, 9}, {10, 6}});
        put(level, o, 11, 1, 9, Blocks.FERN.defaultBlockState());
        if (shape == 2) {
            // A mine timber frame: two posts and a beam on each side of the crossing, clear of the lanes.
            for (int[] p : new int[][] {{5, 6}, {5, 9}, {10, 6}, {10, 9}}) {
                column(level, o, p[0], 1, 3, p[1], Blocks.OAK_FENCE.defaultBlockState());
            }
            box(level, o, 5, 4, 6, 5, 4, 9, Blocks.OAK_PLANKS.defaultBlockState());
            box(level, o, 10, 4, 6, 10, 4, 9, Blocks.OAK_PLANKS.defaultBlockState());
        }
        if (shape == 1) {
            // A dead lantern post and a crate of nothing.
            put(level, o, 7, 1, 5, Blocks.OAK_FENCE.defaultBlockState());
        }
        if (shape == 3) {
            put(level, o, 9, 1, 7, Blocks.COAL_BLOCK.defaultBlockState());
        }
    }

    // ---- floor 4: the iron ravine ----

    private static void rustRavine(ServerLevel level, BlockPos o, int shape, Set<Direction> doors) {
        BlockState terracotta = Blocks.TERRACOTTA.defaultBlockState();
        BlockState sandstone = Blocks.RED_SANDSTONE.defaultBlockState();
        BlockState granite = Blocks.GRANITE.defaultBlockState();
        BlockState iron = Blocks.IRON_ORE.defaultBlockState();
        // Banded cliffs along the closed walls: rust red strata, layer on layer.
        closedBands(level, o, doors, 3, new BlockState[] {terracotta, sandstone, granite, terracotta, sandstone});
        // A spire in each corner on a bed of scorched earth, with iron showing in the inner face.
        qbox(level, o, 2, 0, 2, 4, 0, 4, Blocks.COARSE_DIRT.defaultBlockState());
        qbox(level, o, 2, 1, 2, 3, 1, 3, sandstone);
        qbox(level, o, 2, 2, 2, 3, 3, 3, terracotta);
        quad4(level, o, 2, 4, 2, granite);
        quad4(level, o, 3, 2, 3, iron);
        // Scrub on the ravine floor.
        plants(level, o, Blocks.DEAD_BUSH.defaultBlockState(), new int[][] {{6, 4}, {9, 11}, {5, 10}, {10, 5}});
        if (shape == 1) {
            box(level, o, 9, 1, 6, 10, 1, 6, sandstone);
        }
        if (shape == 2) {
            put(level, o, 6, 1, 11, Blocks.DEAD_BUSH.defaultBlockState());
            box(level, o, 11, 1, 10, 12, 2, 10, terracotta);
        }
        if (shape == 3) {
            box(level, o, 10, 1, 9, 12, 1, 11, granite);
        }
    }

    // ---- floor 5: the cave mouth ----

    private static BlockState pointed(SpeleothemThickness thickness) {
        return Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.DOWN)
                .setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, thickness);
    }

    /** A stalactite that keeps two blocks of headroom: dripstone at y5, a base at y4, a tip at y3. */
    private static void stalactite(ServerLevel level, BlockPos o, int x, int z) {
        put(level, o, x, 5, z, Blocks.DRIPSTONE_BLOCK.defaultBlockState());
        put(level, o, x, 4, z, pointed(SpeleothemThickness.BASE));
        put(level, o, x, 3, z, pointed(SpeleothemThickness.TIP));
    }

    private static void caveRock(ServerLevel level, BlockPos o, Set<Direction> doors) {
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState tuff = Blocks.TUFF.defaultBlockState();
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        // Rock massed against the north and south walls; the floor between stays open for the wave.
        for (int[] band : new int[][] {{1, 3}, {12, 14}}) {
            box(level, o, 1, 1, band[0], 14, 5, band[1], stone);
            box(level, o, 1, 1, band[0] == 1 ? 3 : 12, 14, 1, band[0] == 1 ? 3 : 12, cobble);
        }
        // A dark opening in the north rock: the cave mouth itself, webbed against the night.
        box(level, o, 6, 1, 1, 9, 3, 3, AIR);
        box(level, o, 5, 4, 1, 10, 5, 3, tuff);
        put(level, o, 7, 1, 1, Blocks.COBWEB.defaultBlockState());
        put(level, o, 8, 2, 1, Blocks.COBWEB.defaultBlockState());
        put(level, o, 6, 3, 2, Blocks.COBWEB.defaultBlockState());
        // Stalagmites in the south rock face and stalactites over the floor.
        put(level, o, 4, 1, 11, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.UP)
                .setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, SpeleothemThickness.TIP));
        put(level, o, 11, 1, 4, Blocks.POINTED_DRIPSTONE.defaultBlockState()
                .setValue(BlockStateProperties.VERTICAL_DIRECTION, Direction.UP)
                .setValue(BlockStateProperties.SPELEOTHEM_THICKNESS, SpeleothemThickness.TIP));
        stalactite(level, o, 7, 6);
        stalactite(level, o, 3, 9);
        stalactite(level, o, 12, 7);
    }

    /** Cave Mouth, a dead end from the west: the rock closes the east end around a deep dark niche. */
    private static RoomSpec caveMouthNook() {
        return new RoomSpec("cave_mouth_nook", EnumSet.of(Direction.WEST))
                .spawns(new BlockPos(5, 1, 5), new BlockPos(5, 1, 10), new BlockPos(10, 1, 6),
                        new BlockPos(10, 1, 10))
                .decor((level, o) -> {
                    caveRock(level, o, EnumSet.of(Direction.WEST));
                    // The east end is solid rock with a niche cut into it.
                    box(level, o, 12, 1, 4, 14, 5, 11, Blocks.STONE.defaultBlockState());
                    box(level, o, 12, 1, 6, 14, 3, 9, AIR);
                    put(level, o, 13, 1, 7, Blocks.COBWEB.defaultBlockState());
                    put(level, o, 12, 3, 8, Blocks.COBWEB.defaultBlockState());
                });
    }

    /** Cave Mouth, a straight run west to east: the rock walls it north and south. */
    private static RoomSpec caveMouthRun() {
        return new RoomSpec("cave_mouth_run", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(5, 1, 5), new BlockPos(5, 1, 10), new BlockPos(10, 1, 5),
                        new BlockPos(10, 1, 10))
                .decor((level, o) -> caveRock(level, o, EnumSet.of(Direction.WEST, Direction.EAST)));
    }
}
