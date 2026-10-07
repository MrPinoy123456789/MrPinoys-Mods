package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomTemplateGenerator.HORIZONTALS;

/**
 * Dungeon structure W6: the biome rooms of the Mineshaft (act 1), Lush Caves (act 1) and Cow
 * Pits (act 2) dungeons, plus the Cow Pits situation handlers.
 *
 * <p>Every room is authored at rotation 0 with interior x and z 1 to 14 and y 1 to 5. The
 * narrow rooms fill the walkable cell with solid blocks (an unbreakable rock mass, since only
 * nodes break) and leave a path three wide (z 6 to 8) between the doorway lanes (z 7 and 8), so
 * every declared door stays connected. Rock is {@link Blocks#STONE}, which no node palette
 * holds, so it never becomes a node. Ore for the seam gallery is declared in the room metadata
 * ({@code nodes}), not baked here: the pockets are left as air and the stamper fills a seeded
 * share of each with the declared ore.
 */
final class ResourceBiomeSpecs {

    private ResourceBiomeSpecs() {}

    private static final BlockState STONE = Blocks.STONE.defaultBlockState();
    private static final BlockState AIR = Blocks.AIR.defaultBlockState();
    private static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
    private static final BlockState FENCE = Blocks.OAK_FENCE.defaultBlockState();
    private static final BlockState WEB = Blocks.COBWEB.defaultBlockState();
    private static final BlockState HAY = Blocks.HAY_BLOCK.defaultBlockState();

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(mineshaftTunnel());
        specs.add(mineshaftCrossing());
        specs.add(mineshaftSeam());
        specs.add(mineshaftCollapse());
        specs.add(lushHollow());
        specs.add(lushRootGallery());
        specs.add(lushClayPool());
        specs.add(cowPens());
        specs.add(hayLoft());
        specs.add(cowYard());
        specs.add(burrowTunnel());
        specs.add(ossuaryPassage());
        return specs;
    }

    // ---- helpers ---------------------------------------------------------------------

    /** Fills the inclusive box with {@code state}. Shared with the narrow pass in the other spec files. */
    static void fill(ServerLevel level, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2,
                     BlockState state) {
        for (int x = x1; x <= x2; x++) {
            for (int y = y1; y <= y2; y++) {
                for (int z = z1; z <= z2; z++) {
                    RoomBuilder.set(level, o.offset(x, y, z), state);
                }
            }
        }
    }

    private static void put(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        RoomBuilder.set(level, o.offset(x, y, z), state);
    }

    /** Rock on both sides of a west to east path z 6 to 8. */
    private static void tunnelRock(ServerLevel level, BlockPos o, BlockState rock) {
        fill(level, o, 1, 1, 1, 14, 5, 5, rock);
        fill(level, o, 1, 1, 9, 14, 5, 14, rock);
    }

    private static void railLine(ServerLevel level, BlockPos o, int x1, int x2, int z) {
        BlockState rail = Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST);
        for (int x = x1; x <= x2; x++) {
            put(level, o, x, 1, z, rail);
        }
    }

    /** A pair of fence posts with a plank beam across the path, a mineshaft support. */
    private static void supportFrame(ServerLevel level, BlockPos o, int x) {
        for (int z : new int[]{6, 8}) {
            put(level, o, x, 1, z, FENCE);
            put(level, o, x, 2, z, FENCE);
        }
        fill(level, o, x, 3, 6, x, 3, 8, PLANKS);
    }

    private static BlockState fence(boolean east, boolean west, boolean north, boolean south) {
        return FENCE.setValue(FenceBlock.EAST, east).setValue(FenceBlock.WEST, west)
                .setValue(FenceBlock.NORTH, north).setValue(FenceBlock.SOUTH, south);
    }

    private static BlockState gate(Direction facing) {
        return Blocks.OAK_FENCE_GATE.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, facing);
    }

    private static void vines(ServerLevel level, BlockPos o, int x, int z) {
        put(level, o, x, 5, z, Blocks.CAVE_VINES_PLANT.defaultBlockState().setValue(BlockStateProperties.BERRIES, true));
        put(level, o, x, 4, z, Blocks.CAVE_VINES.defaultBlockState().setValue(BlockStateProperties.BERRIES, true));
    }

    // ---- Mineshaft -------------------------------------------------------------------

    /** Narrow rail tunnel, west to east, path z 6 to 8, supports every seven blocks. */
    private static RoomSpec mineshaftTunnel() {
        return new RoomSpec("mineshaft_tunnel", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 6))
                .spawns(new BlockPos(3, 1, 8), new BlockPos(6, 1, 6), new BlockPos(9, 1, 8), new BlockPos(12, 1, 6))
                .decor((level, o) -> {
                    tunnelRock(level, o, STONE);
                    railLine(level, o, 1, 14, 7);
                    supportFrame(level, o, 4);
                    supportFrame(level, o, 11);
                    put(level, o, 2, 4, 6, WEB);
                    put(level, o, 13, 4, 8, WEB);
                });
    }

    /** Four way crossing: a plus shaped path three wide, a beam ring over the centre. */
    private static RoomSpec mineshaftCrossing() {
        return new RoomSpec("mineshaft_crossing", HORIZONTALS)
                .chests(new BlockPos(2, 1, 6))
                .spawns(new BlockPos(3, 1, 8), new BlockPos(12, 1, 6), new BlockPos(7, 1, 3), new BlockPos(8, 1, 12))
                .decor((level, o) -> {
                    fill(level, o, 1, 1, 1, 5, 5, 5, STONE);
                    fill(level, o, 9, 1, 1, 14, 5, 5, STONE);
                    fill(level, o, 1, 1, 9, 5, 5, 14, STONE);
                    fill(level, o, 9, 1, 9, 14, 5, 14, STONE);
                    railLine(level, o, 1, 14, 7);
                    for (int x : new int[]{6, 8}) {
                        for (int z : new int[]{6, 8}) {
                            put(level, o, x, 1, z, FENCE);
                            put(level, o, x, 2, z, FENCE);
                        }
                    }
                    // The beam ring: the perimeter of the centre 3 x 3 at y 3.
                    fill(level, o, 6, 3, 6, 8, 3, 6, PLANKS);
                    fill(level, o, 6, 3, 8, 8, 3, 8, PLANKS);
                    put(level, o, 6, 3, 7, PLANKS);
                    put(level, o, 8, 3, 7, PLANKS);
                    put(level, o, 6, 4, 6, WEB);
                });
    }

    /** Ore seam gallery: one deep notches in the rock either side of the path, filled by the metadata nodes. */
    private static RoomSpec mineshaftSeam() {
        return new RoomSpec("mineshaft_seam", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 6))
                .spawns(new BlockPos(4, 1, 6), new BlockPos(11, 1, 8), new BlockPos(8, 1, 6), new BlockPos(2, 1, 8))
                .decor((level, o) -> {
                    tunnelRock(level, o, STONE);
                    // Pockets one deep, facing the path; the stamper places the declared ore in them.
                    fill(level, o, 3, 1, 5, 5, 3, 5, AIR);
                    fill(level, o, 9, 1, 5, 11, 3, 5, AIR);
                    fill(level, o, 3, 1, 9, 5, 3, 9, AIR);
                    fill(level, o, 9, 1, 9, 11, 3, 9, AIR);
                    railLine(level, o, 1, 14, 7);
                    supportFrame(level, o, 7);
                });
    }

    /** Collapsed shaft: a rubble heap fills most of the path; one block high gravel and a slab lead over it. */
    private static RoomSpec mineshaftCollapse() {
        return new RoomSpec("mineshaft_collapse", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 6))
                .spawns(new BlockPos(3, 1, 8), new BlockPos(12, 1, 6), new BlockPos(10, 1, 8), new BlockPos(5, 1, 6))
                .decor((level, o) -> {
                    tunnelRock(level, o, STONE);
                    railLine(level, o, 1, 5, 7);
                    railLine(level, o, 10, 14, 7);
                    supportFrame(level, o, 4);
                    // The broken frame: one post left, the beam on the floor.
                    put(level, o, 12, 1, 8, FENCE);
                    put(level, o, 12, 2, 8, FENCE);
                    put(level, o, 11, 1, 6, PLANKS);
                    // Rubble: tall at the sides, one block high down the middle (a jump, never a wall).
                    BlockState gravel = Blocks.GRAVEL.defaultBlockState();
                    BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
                    fill(level, o, 6, 1, 6, 9, 3, 6, cobble);
                    fill(level, o, 6, 1, 8, 9, 2, 8, gravel);
                    fill(level, o, 6, 1, 7, 9, 1, 7, gravel);
                    put(level, o, 8, 1, 7, Blocks.OAK_SLAB.defaultBlockState());
                    put(level, o, 7, 3, 7, WEB);
                    put(level, o, 3, 4, 7, WEB);
                });
    }

    // ---- Lush Caves ------------------------------------------------------------------

    /** A mossy cavern with berry vines and a fallen log. Wide, dim, all four doors. */
    private static RoomSpec lushHollow() {
        return new RoomSpec("lush_hollow", HORIZONTALS)
                .chests(new BlockPos(2, 1, 13))
                .spawns(RoomTemplateGenerator.QUAD_SPAWNS)
                .decor((level, o) -> {
                    BlockState moss = Blocks.MOSS_CARPET.defaultBlockState();
                    for (int x = 3; x <= 12; x++) {
                        for (int z = 3; z <= 12; z++) {
                            if ((x + z) % 3 != 0 && !(x >= 7 && x <= 8) && !(z >= 7 && z <= 8)) {
                                put(level, o, x, 1, z, moss);
                            }
                        }
                    }
                    put(level, o, 5, 1, 5, Blocks.AZALEA.defaultBlockState());
                    put(level, o, 10, 1, 10, Blocks.FLOWERING_AZALEA.defaultBlockState());
                    put(level, o, 10, 1, 5, Blocks.BIG_DRIPLEAF.defaultBlockState());
                    vines(level, o, 3, 3);
                    vines(level, o, 12, 3);
                    vines(level, o, 3, 12);
                    vines(level, o, 12, 12);
                    vines(level, o, 5, 10);
                    // A fallen log, lying along x, against the south wall (a node: oak_log is in the palette).
                    BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS,
                            Direction.Axis.X);
                    for (int x = 4; x <= 6; x++) {
                        put(level, o, x, 1, 13, log);
                    }
                });
    }

    /** A narrow gallery of roots: dirt and moss rock, oak log root pillars against the path, hanging roots. */
    private static RoomSpec lushRootGallery() {
        return new RoomSpec("lush_root_gallery", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 8))
                .spawns(new BlockPos(3, 1, 8), new BlockPos(6, 1, 6), new BlockPos(9, 1, 8), new BlockPos(12, 1, 6))
                .decor((level, o) -> {
                    tunnelRock(level, o, Blocks.ROOTED_DIRT.defaultBlockState());
                    fill(level, o, 1, 1, 4, 14, 1, 5, Blocks.MOSS_BLOCK.defaultBlockState());
                    fill(level, o, 1, 1, 9, 14, 1, 10, Blocks.MOSS_BLOCK.defaultBlockState());
                    BlockState log = Blocks.OAK_LOG.defaultBlockState();
                    for (int y = 1; y <= 4; y++) {
                        put(level, o, 5, y, 6, log);
                        put(level, o, 10, y, 8, log);
                    }
                    BlockState roots = Blocks.HANGING_ROOTS.defaultBlockState();
                    for (int x : new int[]{2, 7, 12}) {
                        put(level, o, x, 5, 7, roots);
                    }
                    vines(level, o, 8, 6);
                    vines(level, o, 3, 8);
                });
    }

    /** A clay shallows: clay patches (declared nodes), dripleaf and azalea on moss. North to south. */
    private static RoomSpec lushClayPool() {
        return new RoomSpec("lush_clay_pool", EnumSet.of(Direction.NORTH, Direction.SOUTH))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(4, 1, 4), new BlockPos(11, 1, 4), new BlockPos(4, 1, 11), new BlockPos(11, 1, 11))
                .decor((level, o) -> {
                    for (int x = 4; x <= 11; x++) {
                        for (int z = 4; z <= 11; z++) {
                            if (!(x >= 7 && x <= 8)) {
                                put(level, o, x, 0, z, Blocks.CLAY.defaultBlockState());
                            }
                        }
                    }
                    put(level, o, 3, 1, 6, Blocks.BIG_DRIPLEAF.defaultBlockState());
                    put(level, o, 12, 1, 9, Blocks.BIG_DRIPLEAF.defaultBlockState());
                    put(level, o, 4, 1, 12, Blocks.AZALEA.defaultBlockState());
                    vines(level, o, 3, 3);
                    vines(level, o, 12, 12);
                });
    }

    // ---- Cow Pits --------------------------------------------------------------------

    /** Two fenced pens either side of a path, a gate each; cows are spawned by the {@code cow_pens} handler. */
    private static RoomSpec cowPens() {
        return new RoomSpec("cow_pens", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(4, 1, 3), new BlockPos(11, 1, 3), new BlockPos(4, 1, 12), new BlockPos(11, 1, 12))
                .decor((level, o) -> {
                    for (int z : new int[]{5, 10}) {
                        for (int x = 1; x <= 14; x++) {
                            put(level, o, x, 1, z, fence(x < 14, x > 1, false, false));
                        }
                        // Two gates side by side make a two wide pen entrance.
                        put(level, o, 7, 1, z, gate(Direction.NORTH));
                        put(level, o, 8, 1, z, gate(Direction.NORTH));
                        BlockState post = Blocks.OAK_LOG.defaultBlockState();
                        put(level, o, 1, 2, z, post);
                        put(level, o, 14, 2, z, post);
                    }
                    put(level, o, 2, 1, 2, HAY);
                    put(level, o, 13, 1, 2, HAY);
                    put(level, o, 2, 1, 13, HAY);
                    put(level, o, 13, 1, 13, HAY);
                    put(level, o, 3, 4, 7, WEB);
                });
    }

    /** A hay stacked loft. West, east and south doors. */
    private static RoomSpec hayLoft() {
        return new RoomSpec("hay_loft", EnumSet.of(Direction.WEST, Direction.EAST, Direction.SOUTH))
                .spawns(new BlockPos(4, 1, 11), new BlockPos(11, 1, 4), new BlockPos(4, 1, 4), new BlockPos(11, 1, 11))
                .decor((level, o) -> {
                    fill(level, o, 2, 1, 2, 3, 2, 3, HAY);
                    fill(level, o, 12, 1, 2, 13, 3, 3, HAY);
                    fill(level, o, 2, 1, 12, 3, 1, 13, HAY);
                    fill(level, o, 5, 1, 1, 6, 1, 2, HAY);
                    put(level, o, 8, 5, 12, Blocks.LANTERN.defaultBlockState().setValue(
                            BlockStateProperties.HANGING, true));
                });
    }

    /** A corner yard with a small fenced paddock in the middle. North and east doors. */
    private static RoomSpec cowYard() {
        return new RoomSpec("cow_yard", EnumSet.of(Direction.NORTH, Direction.EAST))
                .spawns(new BlockPos(6, 1, 6), new BlockPos(7, 1, 7))
                .decor((level, o) -> {
                    for (int i = 4; i <= 9; i++) {
                        put(level, o, i, 1, 4, fence(i < 9, i > 4, false, false));
                        put(level, o, i, 1, 9, fence(i < 9, i > 4, false, false));
                        put(level, o, 4, 1, i, fence(false, false, i > 4, i < 9));
                        put(level, o, 9, 1, i, fence(false, false, i > 4, i < 9));
                    }
                    put(level, o, 9, 1, 6, gate(Direction.EAST));
                    put(level, o, 5, 1, 5, HAY);
                    put(level, o, 12, 1, 12, HAY);
                    put(level, o, 13, 1, 12, HAY);
                });
    }


    // ---- Act 1 narrow rooms for the story dungeons (D17) -----------------------------

    /** Infestation: a chewed, narrow burrow, path z 6 to 8, silverfish stone and webs. */
    private static RoomSpec burrowTunnel() {
        return new RoomSpec("burrow_tunnel", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 6))
                .spawns(new BlockPos(3, 1, 8), new BlockPos(6, 1, 6), new BlockPos(9, 1, 8), new BlockPos(12, 1, 6))
                .decor((level, o) -> {
                    tunnelRock(level, o, Blocks.COBBLESTONE.defaultBlockState());
                    fill(level, o, 5, 1, 5, 6, 2, 5, AIR);
                    fill(level, o, 10, 1, 9, 11, 2, 9, AIR);
                    put(level, o, 4, 3, 7, WEB);
                    put(level, o, 9, 2, 6, WEB);
                    put(level, o, 12, 3, 8, WEB);
                });
    }

    /** Ossuary: a narrow passage lined with bone, a pillar pair mid way. */
    private static RoomSpec ossuaryPassage() {
        return new RoomSpec("ossuary_passage", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 8))
                .spawns(new BlockPos(3, 1, 6), new BlockPos(6, 1, 8), new BlockPos(9, 1, 6), new BlockPos(12, 1, 8))
                .decor((level, o) -> {
                    tunnelRock(level, o, Blocks.STONE_BRICKS.defaultBlockState());
                    BlockState bone = Blocks.BONE_BLOCK.defaultBlockState();
                    for (int y = 1; y <= 3; y++) {
                        put(level, o, 7, y, 6, bone);
                        put(level, o, 8, y, 8, bone);
                    }
                    fill(level, o, 3, 1, 5, 4, 1, 5, bone);
                    fill(level, o, 11, 1, 9, 12, 1, 9, bone);
                });
    }

    // ---- handlers --------------------------------------------------------------------

    private static boolean handlersRegistered;

    /** Registers the cow room situations. Called once from {@link TrialContent#warmUp()}. */
    static void registerHandlers() {
        if (handlersRegistered) {
            return;
        }
        handlersRegistered = true;
        for (String room : CowPits.ROOM_COWS.keySet()) {
            Situations.register(room, (level, o, role, depth, profile, spawns, seed, affixes, lootSuffix, theme,
                                       voidedFloor, content) -> {
                CowPits.spawnCows(level, o, CowPits.ROOM_COWS.get(content), spawns, seed);
                return null;
            });
        }
    }
}
