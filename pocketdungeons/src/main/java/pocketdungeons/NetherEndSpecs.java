package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomTemplateGenerator.HORIZONTALS;

/**
 * Dungeon structure W7b: the rooms of the Act 4 Nether capstone dungeon and the Act 5 End dungeons.
 *
 * <ul>
 *   <li>{@code warped_forest} (4 doors) and {@code crimson_forest} (2 doors): the outdoor Nether
 *       biome rooms of design D18, with warped and crimson stem trunks and wart block canopies
 *       (the resource nodes of D19), shroomlight kept sparse. Shared by {@code basalt_foundry},
 *       {@code blackstone} and {@code wither_keep}.</li>
 *   <li>{@code soul_sand_valley} (2 doors): bone ribs over soul soil, a basalt outcrop holding
 *       nether quartz. {@code wither_keep}.</li>
 *   <li>{@code wither_hall}: the Wither's boss room and terminal cell. An open nether brick floor
 *       with four lantern pillars in the corners and the whole middle clear for the fight.</li>
 *   <li>{@code end_island} (4 doors), {@code end_city_hall} (4 doors), {@code end_ship} (2 doors):
 *       the End dungeon's outer island, city and ship rooms.</li>
 *   <li>{@code fracture_hall}: Herobrine's boss room and terminal cell. A cracked obsidian dais,
 *       four obsidian pillars for cover, nothing else.</li>
 * </ul>
 *
 * <p>Every position is chosen against the same constraints as {@link CapstoneSpecs}, so each room
 * survives all four rotations: the doorway lanes (x or z 7 to 8 within three blocks of a wall) stay
 * clear, a boss room's 2x2 exit pad at 7 to 8 stays clear, and in a boss room nothing stands
 * orthogonally next to the nine points whose coordinates are 4, 8 or 12 (where the completion and
 * vault chests land) at floor level or above. Rooms that are not terminals keep their loot chest
 * and spawn points where the specs say and decorate round them.
 */
final class NetherEndSpecs {

    private NetherEndSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(warpedForest());
        specs.add(crimsonForest());
        specs.add(soulSandValley());
        specs.add(witherHall());
        specs.add(endIsland());
        specs.add(endCityHall());
        specs.add(endShip());
        specs.add(fractureHall());
        return specs;
    }

    // ---- helpers ---------------------------------------------------------------------

    private static void put(ServerLevel level, BlockPos o, int x, int y, int z, BlockState state) {
        RoomBuilder.set(level, o.offset(x, y, z), state);
    }

    private static void fill(ServerLevel level, BlockPos o, int x1, int y1, int z1, int x2, int y2, int z2,
                             BlockState state) {
        ResourceBiomeSpecs.fill(level, o, x1, y1, z1, x2, y2, z2, state);
    }

    /**
     * A huge fungus: a four high stem trunk at (x, z) crowned with a 3x3 wart block cap at y 4 and 5.
     * {@code light} swaps one cap corner at y 4 for a shroomlight (kept sparse by the callers).
     */
    private static void tree(ServerLevel level, BlockPos o, int x, int z, BlockState stem, BlockState wart,
                             boolean light) {
        for (int y = 1; y <= 4; y++) {
            put(level, o, x, y, z, stem);
        }
        fill(level, o, x - 1, 4, z - 1, x + 1, 4, z + 1, wart);
        put(level, o, x, 4, z, stem);
        fill(level, o, x - 1, 5, z - 1, x + 1, 5, z + 1, wart);
        if (light) {
            put(level, o, x + 1, 4, z + 1, Blocks.SHROOMLIGHT.defaultBlockState());
        }
    }

    /** A stone pillar of {@code height} blocks of {@code body}, topped by {@code top} one block above. */
    private static void pillar(ServerLevel level, BlockPos o, int x, int z, int height, BlockState body,
                               BlockState top) {
        for (int y = 1; y <= height; y++) {
            put(level, o, x, y, z, body);
        }
        put(level, o, x, height + 1, z, top);
    }

    // ---- the Nether biome rooms ----------------------------------------------------------

    /** A warped forest: nylium floor, five warped fungus trunks and two roots, a few shroomlights. Four doors, dim. */
    private static RoomSpec warpedForest() {
        return new RoomSpec("warped_forest", HORIZONTALS)
                .chests(new BlockPos(13, 1, 13))
                .spawns(RoomTemplateGenerator.QUAD_SPAWNS)
                .decor((level, o) -> {
                    fill(level, o, 1, 0, 1, 14, 0, 14, Blocks.WARPED_NYLIUM.defaultBlockState());
                    BlockState stem = Blocks.WARPED_STEM.defaultBlockState();
                    BlockState wart = Blocks.WARPED_WART_BLOCK.defaultBlockState();
                    tree(level, o, 2, 2, stem, wart, true);
                    tree(level, o, 13, 2, stem, wart, false);
                    tree(level, o, 2, 13, stem, wart, false);
                    tree(level, o, 5, 9, stem, wart, true);
                    tree(level, o, 10, 5, stem, wart, true);
                    for (int x = 2; x <= 13; x++) {
                        for (int z = 2; z <= 13; z++) {
                            boolean lane = x == 7 || x == 8 || z == 7 || z == 8;
                            boolean spawn = (x == 4 || x == 11) && (z == 4 || z == 11);
                            boolean trunk = (x == 2 && (z == 2 || z == 13)) || (x == 13 && (z == 2 || z == 13))
                                    || (x == 5 && z == 9) || (x == 10 && z == 5);
                            boolean chest = x == 13 && z == 13;
                            if (lane || spawn || trunk || chest || (x * 7 + z * 13) % 5 != 0) {
                                continue;
                            }
                            put(level, o, x, 1, z, ((x + z) % 2 == 0 ? Blocks.WARPED_ROOTS : Blocks.WARPED_FUNGUS)
                                    .defaultBlockState());
                        }
                    }
                });
    }

    /** A crimson forest: crimson nylium, wart canopies on crimson stems. Two doors (west and east), dim. */
    private static RoomSpec crimsonForest() {
        return new RoomSpec("crimson_forest", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 4))
                .spawns(new BlockPos(5, 1, 5), new BlockPos(10, 1, 10), new BlockPos(8, 1, 4), new BlockPos(7, 1, 12))
                .decor((level, o) -> {
                    fill(level, o, 1, 0, 1, 14, 0, 14, Blocks.CRIMSON_NYLIUM.defaultBlockState());
                    BlockState stem = Blocks.CRIMSON_STEM.defaultBlockState();
                    BlockState wart = Blocks.NETHER_WART_BLOCK.defaultBlockState();
                    tree(level, o, 2, 2, stem, wart, true);
                    tree(level, o, 13, 13, stem, wart, true);
                    tree(level, o, 2, 13, stem, wart, false);
                    tree(level, o, 11, 2, stem, wart, false);
                    for (int x = 2; x <= 13; x++) {
                        for (int z = 2; z <= 13; z++) {
                            boolean path = z >= 6 && z <= 9;
                            if (path || (x * 11 + z * 3) % 6 != 0) {
                                continue;
                            }
                            boolean treeCell = (x <= 3 && (z <= 3 || z >= 12)) || (x >= 10 && x <= 12 && z <= 3)
                                    || (x >= 12 && z >= 12);
                            boolean claimed = (x == 13 && z == 4) || (x == 5 && z == 5) || (x == 10 && z == 10)
                                    || (x == 8 && z == 4) || (x == 7 && z == 12);
                            if (treeCell || claimed) {
                                continue;
                            }
                            put(level, o, x, 1, z, ((x + z) % 2 == 0 ? Blocks.CRIMSON_ROOTS : Blocks.CRIMSON_FUNGUS)
                                    .defaultBlockState());
                        }
                    }
                });
    }

    /** Soul sand valley: soul soil, soul sand patches off the path, bone ribs, a basalt outcrop with quartz. West and east. */
    private static RoomSpec soulSandValley() {
        return new RoomSpec("soul_sand_valley", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(2, 1, 13))
                .spawns(new BlockPos(4, 1, 5), new BlockPos(11, 1, 4), new BlockPos(5, 1, 11), new BlockPos(11, 1, 11))
                .decor((level, o) -> {
                    fill(level, o, 1, 0, 1, 14, 0, 14, Blocks.SOUL_SOIL.defaultBlockState());
                    fill(level, o, 2, 0, 3, 13, 0, 4, Blocks.SOUL_SAND.defaultBlockState());
                    fill(level, o, 2, 0, 11, 13, 0, 12, Blocks.SOUL_SAND.defaultBlockState());
                    BlockState bone = Blocks.BONE_BLOCK.defaultBlockState();
                    for (int x : new int[]{5, 10}) {
                        for (int y = 1; y <= 3; y++) {
                            put(level, o, x, y, 2, bone);
                            put(level, o, x, y, 13, bone);
                        }
                        fill(level, o, x, 4, 2, x, 4, 13, bone);
                    }
                    // A basalt outcrop in the north east corner, quartz in its face (resource nodes).
                    fill(level, o, 11, 1, 1, 14, 2, 3, Blocks.BASALT.defaultBlockState());
                    put(level, o, 11, 1, 3, Blocks.NETHER_QUARTZ_ORE.defaultBlockState());
                    put(level, o, 12, 2, 3, Blocks.NETHER_QUARTZ_ORE.defaultBlockState());
                    put(level, o, 13, 1, 3, Blocks.NETHER_QUARTZ_ORE.defaultBlockState());
                    put(level, o, 3, 1, 3, Blocks.SOUL_LANTERN.defaultBlockState());
                    put(level, o, 12, 1, 12, Blocks.SOUL_LANTERN.defaultBlockState());
                });
    }

    /**
     * The Wither's boss room: nether brick and soul soil underfoot, a lantern pillar in each far
     * corner, the middle and the whole east end clear. West door. The generator lays the pad after
     * this decor.
     */
    private static RoomSpec witherHall() {
        return new RoomSpec("wither_hall", EnumSet.of(Direction.WEST))
                .exitPad()
                .decor((level, o) -> {
                    fill(level, o, 2, 0, 2, 13, 0, 13, Blocks.NETHER_BRICKS.defaultBlockState());
                    for (int x = 2; x <= 13; x++) {
                        for (int z = 2; z <= 13; z++) {
                            boolean pad = x >= 6 && x <= 9 && z >= 6 && z <= 9;
                            if (pad) {
                                continue;
                            }
                            int h = (x * 5 + z * 3) % 7;
                            if (h == 0) {
                                put(level, o, x, 0, z, Blocks.SOUL_SOIL.defaultBlockState());
                            } else if (h == 3) {
                                put(level, o, x, 0, z, Blocks.CRACKED_NETHER_BRICKS.defaultBlockState());
                            }
                        }
                    }
                    BlockState brick = Blocks.NETHER_BRICKS.defaultBlockState();
                    BlockState lantern = Blocks.SOUL_LANTERN.defaultBlockState();
                    pillar(level, o, 3, 3, 3, brick, lantern);
                    pillar(level, o, 3, 13, 3, brick, lantern);
                    pillar(level, o, 13, 3, 3, brick, lantern);
                    pillar(level, o, 13, 13, 3, brick, lantern);
                });
    }

    // ---- the End rooms ------------------------------------------------------------------

    /** An outer island: end stone with mounds, chorus plants, an obsidian spire and ore in the stone. Four doors, dim. */
    private static RoomSpec endIsland() {
        return new RoomSpec("end_island", HORIZONTALS)
                .chests(new BlockPos(13, 1, 13))
                .spawns(RoomTemplateGenerator.QUAD_SPAWNS)
                .decor((level, o) -> {
                    fill(level, o, 1, 0, 1, 14, 0, 14, Blocks.END_STONE.defaultBlockState());
                    BlockState stone = Blocks.END_STONE.defaultBlockState();
                    // Mounds off the lanes (each is clear of x and z 7 to 8).
                    fill(level, o, 2, 1, 2, 3, 2, 3, stone);
                    fill(level, o, 12, 1, 2, 13, 1, 3, stone);
                    fill(level, o, 2, 1, 12, 3, 1, 13, stone);
                    put(level, o, 12, 2, 2, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
                    put(level, o, 3, 2, 2, Blocks.ANCIENT_DEBRIS.defaultBlockState());
                    put(level, o, 2, 1, 13, Blocks.DEEPSLATE_DIAMOND_ORE.defaultBlockState());
                    // An obsidian spire in the middle of the north west quarter, crowned with an end rod.
                    pillar(level, o, 5, 5, 4, Blocks.OBSIDIAN.defaultBlockState(), Blocks.END_ROD.defaultBlockState());
                    // Chorus plants.
                    for (int[] at : new int[][]{{10, 10}, {10, 13}, {5, 10}}) {
                        for (int y = 1; y <= 3; y++) {
                            put(level, o, at[0], y, at[1], Blocks.CHORUS_PLANT.defaultBlockState());
                        }
                        put(level, o, at[0], 4, at[1], Blocks.CHORUS_FLOWER.defaultBlockState());
                    }
                });
    }

    /** A city hall: a purpur floor ring, four purpur pillars with end rods, four doors. */
    private static RoomSpec endCityHall() {
        return new RoomSpec("end_city_hall", HORIZONTALS)
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(5, 1, 5), new BlockPos(10, 1, 5), new BlockPos(5, 1, 10), new BlockPos(10, 1, 10))
                .decor((level, o) -> {
                    fill(level, o, 1, 0, 1, 14, 0, 14, Blocks.END_STONE_BRICKS.defaultBlockState());
                    fill(level, o, 2, 0, 2, 13, 0, 3, Blocks.PURPUR_BLOCK.defaultBlockState());
                    fill(level, o, 2, 0, 12, 13, 0, 13, Blocks.PURPUR_BLOCK.defaultBlockState());
                    fill(level, o, 2, 0, 4, 3, 0, 11, Blocks.PURPUR_BLOCK.defaultBlockState());
                    fill(level, o, 12, 0, 4, 13, 0, 11, Blocks.PURPUR_BLOCK.defaultBlockState());
                    BlockState pillar = Blocks.PURPUR_PILLAR.defaultBlockState();
                    BlockState rod = Blocks.END_ROD.defaultBlockState();
                    pillar(level, o, 3, 3, 3, pillar, rod);
                    pillar(level, o, 12, 3, 3, pillar, rod);
                    pillar(level, o, 3, 12, 3, pillar, rod);
                    pillar(level, o, 12, 12, 3, pillar, rod);
                    // A low purpur bench along the north and south walls, clear of the lanes.
                    for (int x = 5; x <= 10; x++) {
                        if (x == 7 || x == 8) {
                            continue;
                        }
                        put(level, o, x, 1, 1, Blocks.PURPUR_SLAB.defaultBlockState());
                        put(level, o, x, 1, 14, Blocks.PURPUR_SLAB.defaultBlockState());
                    }
                });
    }

    /** An end ship: a purpur deck with a hull either side of a clear path, a brewing stand, the dragon head on the prow. West to east. */
    private static RoomSpec endShip() {
        return new RoomSpec("end_ship", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(11, 1, 12))
                .spawns(new BlockPos(4, 1, 4), new BlockPos(11, 1, 4), new BlockPos(4, 1, 11), new BlockPos(7, 1, 12))
                .decor((level, o) -> {
                    fill(level, o, 1, 0, 1, 14, 0, 14, Blocks.PURPUR_BLOCK.defaultBlockState());
                    BlockState purpur = Blocks.PURPUR_BLOCK.defaultBlockState();
                    // Hull walls two high along z 2 and z 13, broken for the lanes at each end.
                    for (int x = 4; x <= 11; x++) {
                        for (int y = 1; y <= 2; y++) {
                            put(level, o, x, y, 2, purpur);
                            put(level, o, x, y, 13, purpur);
                        }
                    }
                    put(level, o, 7, 1, 11, Blocks.BREWING_STAND.defaultBlockState());
                    put(level, o, 13, 1, 5, Blocks.END_ROD.defaultBlockState());
                    put(level, o, 13, 2, 5, Blocks.DRAGON_HEAD.defaultBlockState());
                    put(level, o, 2, 1, 5, Blocks.END_ROD.defaultBlockState());
                    pillar(level, o, 7, 4, 3, Blocks.PURPUR_PILLAR.defaultBlockState(), Blocks.END_ROD.defaultBlockState());
                });
    }

    /**
     * Herobrine's boss room: a dais of obsidian cracked with crying obsidian, an obsidian pillar at each
     * corner of the pad for cover, end rods on top. West door. The generator lays the pad after this decor.
     */
    private static RoomSpec fractureHall() {
        return new RoomSpec("fracture_hall", EnumSet.of(Direction.WEST))
                .exitPad()
                .decor((level, o) -> {
                    fill(level, o, 2, 0, 2, 13, 0, 13, Blocks.OBSIDIAN.defaultBlockState());
                    BlockState crying = Blocks.CRYING_OBSIDIAN.defaultBlockState();
                    // The fracture: two diagonal cracks crossing under the pad, and one split along the east end.
                    for (int i = 2; i <= 13; i++) {
                        put(level, o, i, 0, i, crying);
                        put(level, o, i, 0, 15 - i, crying);
                    }
                    for (int z = 3; z <= 12; z += 2) {
                        put(level, o, 12, 0, z, crying);
                    }
                    BlockState obsidian = Blocks.OBSIDIAN.defaultBlockState();
                    BlockState rod = Blocks.END_ROD.defaultBlockState();
                    pillar(level, o, 6, 6, 3, obsidian, rod);
                    pillar(level, o, 9, 9, 3, obsidian, rod);
                    pillar(level, o, 6, 9, 3, obsidian, rod);
                    pillar(level, o, 9, 6, 3, obsidian, rod);
                });
    }
}
