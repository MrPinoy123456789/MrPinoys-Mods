package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Final floor rooms for the late dungeons (prismarine, drowned vault, basalt foundry,
 * blackstone, ender archive, the end). Authored at rotation 0 in room-local coordinates with
 * {@link RoomDsl}; the west and east door lanes (z 7 to 8 for x 1 to 3 and 12 to 14) stay clear.
 */
final class FinalFloorLateSpecs {

    private FinalFloorLateSpecs() {}

    private static BlockState s(Block block) {
        return block.defaultBlockState();
    }

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(monumentHeart());
        specs.add(wardensDeep());
        specs.add(greatCrucible());
        specs.add(bastionKeep());
        specs.add(lastIndex());
        specs.add(worldRim());
        return specs;
    }

    private static RoomSpec base(String name, BlockPos[] spawns) {
        return new RoomSpec(name, EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 13))
                .spawns(spawns);
    }

    /** Heart of an ocean monument: an armoured prismarine core with a sealed ore chamber. */
    private static RoomSpec monumentHeart() {
        return base("monument_heart", new BlockPos[] {new BlockPos(5, 1, 4), new BlockPos(10, 1, 11),
                new BlockPos(5, 1, 11), new BlockPos(10, 1, 4)})
                .decor((level, o) -> {
                    box(level, o, 6, 1, 6, 9, 3, 9, s(Blocks.DARK_PRISMARINE));
                    // Sealed 2x2 chamber; the nodes fill it.
                    box(level, o, 7, 1, 7, 8, 2, 8, RoomDsl.AIR);
                    int[][] pillars = {{3, 3}, {12, 3}, {3, 12}, {12, 12}};
                    for (int[] p : pillars) {
                        column(level, o, p[0], 1, 4, p[1], s(Blocks.PRISMARINE_BRICKS));
                        put(level, o, p[0], 5, p[1], RoomDsl.LAMP);
                    }
                    put(level, o, 1, 2, 4, s(Blocks.WET_SPONGE));
                    put(level, o, 14, 2, 11, s(Blocks.WET_SPONGE));
                });
    }

    /** A sealed warden statue standing on a plinth in a sunken sculk nave. */
    private static RoomSpec wardensDeep() {
        return base("wardens_deep", new BlockPos[] {new BlockPos(5, 1, 4), new BlockPos(10, 1, 4),
                new BlockPos(5, 1, 11), new BlockPos(10, 1, 11)})
                .decor((level, o) -> {
                    BlockState tiles = s(Blocks.DEEPSLATE_TILES);
                    // Sculk patch on the floor row, round the statue.
                    for (int x = 3; x <= 12; x++) {
                        for (int z = 3; z <= 12; z++) {
                            int dx = x - 7, dz = z - 7;
                            if ((x * 3 + z * 5) % 7 != 0 && dx * dx + dz * dz <= 29) {
                                put(level, o, x, 0, z, s(Blocks.SCULK));
                            }
                        }
                    }
                    // Plinth.
                    box(level, o, 6, 1, 6, 9, 1, 9, s(Blocks.POLISHED_DEEPSLATE));
                    // Legs, torso, T-pose arms, catalyst head.
                    column(level, o, 7, 2, 3, 7, tiles);
                    column(level, o, 7, 2, 3, 8, tiles);
                    box(level, o, 7, 4, 7, 7, 4, 8, tiles);
                    box(level, o, 7, 4, 5, 7, 4, 6, tiles);
                    box(level, o, 7, 4, 9, 7, 4, 10, tiles);
                    put(level, o, 7, 5, 7, s(Blocks.SCULK_CATALYST));
                    put(level, o, 7, 5, 8, s(Blocks.SCULK_CATALYST));
                    // Nave pillars.
                    int[][] pillars = {{3, 5}, {12, 5}, {3, 10}, {12, 10}};
                    for (int[] p : pillars) {
                        column(level, o, p[0], 1, 5, p[1], tiles);
                    }
                });
    }

    /** A giant crucible: a blackstone ring round a magma basin, a cauldron hung above it. */
    private static RoomSpec greatCrucible() {
        BlockState ring = s(Blocks.POLISHED_BLACKSTONE_BRICKS);
        return new RoomSpec("great_crucible", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 12))
                .spawns(new BlockPos(4, 1, 4), new BlockPos(11, 1, 4), new BlockPos(4, 1, 11),
                        new BlockPos(11, 1, 11))
                .decor((level, o) -> {
                    box(level, o, 5, 1, 5, 10, 2, 10, ring);
                    // The basin is sunk into the floor row and filled with magma.
                    box(level, o, 6, 0, 6, 9, 0, 9, s(Blocks.MAGMA_BLOCK));
                    box(level, o, 6, 1, 6, 9, 2, 9, RoomDsl.AIR);
                    // Cauldron (2x2) hung from iron bars above the basin.
                    box(level, o, 7, 3, 7, 8, 3, 8, s(Blocks.CAULDRON));
                    box(level, o, 7, 4, 7, 8, 5, 8, s(Blocks.IRON_BARS));
                    put(level, o, 5, 3, 5, RoomDsl.LAMP);
                    put(level, o, 10, 3, 5, RoomDsl.LAMP);
                    put(level, o, 5, 3, 10, RoomDsl.LAMP);
                    put(level, o, 10, 3, 10, RoomDsl.LAMP);
                    BlockState fire = s(Blocks.CAMPFIRE).setValue(BlockStateProperties.LIT, true);
                    put(level, o, 2, 1, 2, fire);
                    put(level, o, 13, 1, 2, fire);
                });
    }

    /** A bastion keep court: a crenellated wall with a gate, a dais and a throne. */
    private static RoomSpec bastionKeep() {
        return base("bastion_keep", new BlockPos[] {new BlockPos(3, 1, 4), new BlockPos(3, 1, 11),
                new BlockPos(12, 1, 4), new BlockPos(12, 1, 11)})
                .decor((level, o) -> {
                    for (int z = 1; z <= 14; z++) {
                        if (z == 7 || z == 8) {
                            continue;
                        }
                        box(level, o, 6, 1, z, 6, 3, z, RoomDsl.WALL);
                        if (z % 2 == 0) {
                            put(level, o, 6, 4, z, RoomDsl.WALL);
                        }
                    }
                    box(level, o, 8, 1, 6, 11, 1, 9, RoomDsl.WALL);
                    box(level, o, 9, 2, 7, 10, 3, 8, s(Blocks.CHISELED_POLISHED_BLACKSTONE));
                    BlockState lantern = s(Blocks.SOUL_LANTERN).setValue(BlockStateProperties.HANGING, true);
                    put(level, o, 3, 5, 3, lantern);
                    put(level, o, 3, 5, 12, lantern);
                    put(level, o, 12, 5, 3, lantern);
                    put(level, o, 12, 5, 12, lantern);
                    put(level, o, 9, 5, 5, lantern);
                    put(level, o, 9, 5, 10, lantern);
                    // Ring cells round the throne stay air for the manifest nodes.
                    box(level, o, 8, 2, 6, 11, 2, 9, RoomDsl.AIR);
                    box(level, o, 9, 2, 7, 10, 3, 8, s(Blocks.CHISELED_POLISHED_BLACKSTONE));
                });
    }

    /** The last archive: two shelf rows with a central aisle, only six real bookshelves. */
    private static RoomSpec lastIndex() {
        return base("last_index", new BlockPos[] {new BlockPos(4, 1, 6), new BlockPos(6, 1, 9),
                new BlockPos(9, 1, 6), new BlockPos(11, 1, 9)})
                .decor((level, o) -> {
                    BlockState chiseled = s(Blocks.CHISELED_BOOKSHELF);
                    BlockState real = s(Blocks.BOOKSHELF);
                    int[] rows = {4, 11};
                    for (int z : rows) {
                        for (int x = 2; x <= 13; x++) {
                            if (x == 7 || x == 8) {
                                continue;
                            }
                            box(level, o, x, 1, z, x, 3, z, chiseled);
                        }
                        box(level, o, 1, 1, z, 1, 3, z, RoomDsl.WALL);
                        box(level, o, 14, 1, z, 14, 3, z, RoomDsl.WALL);
                        for (int x = 3; x <= 12; x += 3) {
                            if (x != 6 && x != 9) {
                                put(level, o, x, 4, z, s(Blocks.END_ROD));
                            }
                        }
                    }
                    put(level, o, 3, 2, 4, real);
                    put(level, o, 5, 2, 4, real);
                    put(level, o, 10, 2, 4, real);
                    put(level, o, 4, 2, 11, real);
                    put(level, o, 9, 2, 11, real);
                    put(level, o, 12, 2, 11, real);
                    put(level, o, 6, 4, 4, s(Blocks.END_ROD));
                    put(level, o, 9, 4, 4, s(Blocks.END_ROD));
                    put(level, o, 6, 4, 11, s(Blocks.END_ROD));
                    put(level, o, 10, 4, 11, s(Blocks.END_ROD));
                    put(level, o, 8, 1, 8, s(Blocks.LECTERN));
                });
    }

    /** The edge of the world: a black pit ringed by obsidian, end rod stars overhead. */
    private static RoomSpec worldRim() {
        return base("world_rim", new BlockPos[] {new BlockPos(2, 1, 5), new BlockPos(13, 1, 5),
                new BlockPos(2, 1, 10), new BlockPos(13, 1, 10)})
                .decor((level, o) -> {
                    box(level, o, 4, 0, 4, 11, 0, 11, s(Blocks.OBSIDIAN));
                    box(level, o, 5, 0, 5, 10, 0, 10, s(Blocks.CONCRETE.black()));
                    int[][] stars = {{5, 5}, {8, 6}, {10, 5}, {6, 8}, {9, 9}, {5, 10}, {10, 10}, {7, 4}, {8, 11}};
                    for (int[] p : stars) {
                        put(level, o, p[0], 5, p[1], s(Blocks.END_ROD));
                    }
                    int[][] posts = {{4, 4}, {11, 4}, {4, 11}, {11, 11}};
                    for (int[] p : posts) {
                        column(level, o, p[0], 1, 2, p[1], s(Blocks.PURPUR_PILLAR));
                    }
                    int[][] ledges = {{2, 2}, {2, 3}, {13, 2}, {13, 3}, {2, 13}};
                    for (int[] p : ledges) {
                        put(level, o, p[0], 1, p[1], s(Blocks.END_STONE_BRICKS));
                    }
                });
    }
}
