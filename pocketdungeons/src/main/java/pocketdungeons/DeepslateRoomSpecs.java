package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Deepslate dungeon rooms: a geode dead end, a broken mine landing tee and a fossil gallery
 * corner. Authored at rotation 0 in room-local coordinates; door lanes (x 7 to 8 for z 1 to 3
 * and 12 to 14, z 7 to 8 for x 1 to 3 and 12 to 14) are kept clear. Ore is declared as manifest
 * nodes over the air pockets carved here.
 */
final class DeepslateRoomSpecs {

    private DeepslateRoomSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(deepGeode());
        specs.add(deepShaftLanding());
        specs.add(deepFossilGallery());
        return specs;
    }

    private static BlockState cluster(Direction facing) {
        return Blocks.AMETHYST_CLUSTER.defaultBlockState().setValue(BlockStateProperties.FACING, facing);
    }

    /**
     * Deep Geode (dead end, west door): a geode of smooth basalt and calcite around an amethyst
     * lining. Clusters glow in the dark hollow; ore sits in pockets in the lining, one diamond
     * buried deep in the ceiling lining.
     */
    private static RoomSpec deepGeode() {
        return new RoomSpec("deep_geode", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(13, 1, 10))
                .spawns(new BlockPos(7, 1, 5), new BlockPos(7, 1, 10), new BlockPos(10, 1, 5),
                        new BlockPos(10, 1, 10))
                .decor((level, o) -> {
                    BlockState basalt = Blocks.SMOOTH_BASALT.defaultBlockState();
                    BlockState calcite = Blocks.CALCITE.defaultBlockState();
                    BlockState amethyst = Blocks.AMETHYST_BLOCK.defaultBlockState();
                    for (int x = 4; x <= 14; x++) {
                        for (int y = 1; y <= 5; y++) {
                            for (int z = 2; z <= 13; z++) {
                                put(level, o, x, y, z, (x + y + z) % 3 == 0 ? calcite : basalt);
                            }
                        }
                    }
                    // Round the outer corners.
                    for (int y = 1; y <= 5; y++) {
                        put(level, o, 4, y, 2, AIR);
                        put(level, o, 14, y, 2, AIR);
                        put(level, o, 4, y, 13, AIR);
                        put(level, o, 14, y, 13, AIR);
                    }
                    box(level, o, 5, 1, 3, 13, 4, 12, amethyst);
                    for (int y = 1; y <= 4; y++) {
                        put(level, o, 5, y, 3, basalt);
                        put(level, o, 13, y, 3, basalt);
                        put(level, o, 5, y, 12, basalt);
                        put(level, o, 13, y, 12, basalt);
                    }
                    box(level, o, 6, 1, 4, 12, 3, 11, AIR);
                    for (int y = 1; y <= 3; y++) {
                        put(level, o, 6, y, 4, amethyst);
                        put(level, o, 12, y, 4, amethyst);
                        put(level, o, 6, y, 11, amethyst);
                        put(level, o, 12, y, 11, amethyst);
                    }
                    // Ore pockets in the lining (lapis x3, one buried diamond).
                    put(level, o, 5, 3, 5, AIR);
                    put(level, o, 5, 2, 10, AIR);
                    put(level, o, 13, 2, 5, AIR);
                    put(level, o, 9, 4, 3, AIR);
                    // The way in from the west door, the east lane and the chest nook.
                    box(level, o, 4, 1, 7, 5, 3, 8, AIR);
                    box(level, o, 13, 1, 7, 14, 3, 8, AIR);
                    box(level, o, 13, 1, 10, 13, 2, 11, AIR);
                    // Glowing clusters: hanging from the ceiling lining and on the walls.
                    for (int[] c : new int[][]{{7, 6}, {10, 5}, {8, 10}, {11, 9}}) {
                        put(level, o, c[0], 3, c[1], cluster(Direction.DOWN));
                    }
                    put(level, o, 12, 3, 6, cluster(Direction.WEST));
                    put(level, o, 6, 3, 9, cluster(Direction.EAST));
                    put(level, o, 8, 2, 4, cluster(Direction.SOUTH));
                    put(level, o, 8, 2, 11, cluster(Direction.NORTH));
                });
    }

    /**
     * Deep Shaft Landing (tee, north east south): a broken mine landing beside a shaft. Plank
     * scaffolding and a ladder, a dead rail line, a snapped beam and iron bars hanging into a
     * black patch of floor that only looks like a drop. Ore hides in two rubble heaps.
     */
    private static RoomSpec deepShaftLanding() {
        return new RoomSpec("deep_shaft_landing", EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(5, 1, 13))
                .spawns(new BlockPos(3, 1, 2), new BlockPos(3, 1, 13), new BlockPos(9, 1, 5),
                        new BlockPos(10, 1, 8))
                .decor((level, o) -> {
                    BlockState rubble = Blocks.COBBLED_DEEPSLATE.defaultBlockState();
                    BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    // The shaft: a black patch of floor, bars hanging into the gloom, a ladder.
                    box(level, o, 2, 0, 5, 5, 0, 10, Blocks.COAL_BLOCK.defaultBlockState());
                    column(level, o, 3, 2, 5, 6, bars);
                    column(level, o, 5, 2, 5, 9, bars);
                    column(level, o, 2, 3, 5, 8, bars);
                    column(level, o, 4, 4, 5, 7, bars);
                    BlockState ladder = Blocks.LADDER.defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
                    column(level, o, 1, 1, 5, 6, ladder);
                    // Broken rim around the shaft.
                    put(level, o, 2, 1, 4, planks);
                    put(level, o, 5, 1, 4, planks);
                    put(level, o, 2, 1, 11, planks);
                    put(level, o, 3, 1, 11, planks);
                    // Scaffolding and a plank platform.
                    BlockState scaffold = Blocks.SCAFFOLDING.defaultBlockState();
                    column(level, o, 7, 1, 3, 5, scaffold);
                    column(level, o, 7, 1, 3, 6, scaffold);
                    box(level, o, 5, 4, 5, 8, 4, 6, planks);
                    put(level, o, 6, 4, 6, AIR);
                    // A beam that stops short, and its fallen end.
                    box(level, o, 6, 4, 4, 9, 4, 4, RoomDsl.log(Direction.Axis.X));
                    box(level, o, 9, 1, 12, 10, 1, 12, RoomDsl.log(Direction.Axis.X));
                    put(level, o, 10, 2, 12, planks);
                    // A dead rail line from the east, broken in two places.
                    BlockState rail = Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST);
                    for (int x : new int[]{6, 7, 8, 10, 12, 13}) {
                        put(level, o, x, 1, 10, rail);
                    }
                    // Rubble heaps with ore pockets (iron in the north east, lapis in the south east).
                    box(level, o, 10, 1, 1, 14, 4, 5, rubble);
                    box(level, o, 11, 1, 12, 14, 3, 14, rubble);
                    put(level, o, 11, 2, 2, AIR);
                    put(level, o, 12, 2, 3, AIR);
                    put(level, o, 13, 2, 4, AIR);
                    put(level, o, 11, 2, 12, AIR);
                    put(level, o, 13, 2, 13, AIR);
                });
    }

    /**
     * Deep Fossil Gallery (corner, north east): bone ribs arch over the passage between the
     * north and east doors, tuff and calcite walls hold ore pockets, skulls and lit candles
     * watch from ledges.
     */
    private static RoomSpec deepFossilGallery() {
        return new RoomSpec("deep_fossil_gallery", EnumSet.of(Direction.NORTH, Direction.EAST))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(4, 1, 9), new BlockPos(8, 1, 9), new BlockPos(12, 1, 3),
                        new BlockPos(12, 1, 12))
                .decor((level, o) -> {
                    BlockState tuff = Blocks.TUFF.defaultBlockState();
                    BlockState calcite = Blocks.CALCITE.defaultBlockState();
                    BlockState bone = Blocks.BONE_BLOCK.defaultBlockState();
                    BlockState skull = Blocks.SKELETON_SKULL.defaultBlockState();
                    // Thick west wall and south wall, lanes left open.
                    for (int y = 1; y <= 5; y++) {
                        for (int z = 1; z <= 14; z++) {
                            if (z == 7 || z == 8) {
                                continue;
                            }
                            for (int x = 1; x <= 2; x++) {
                                put(level, o, x, y, z, (x + y + z) % 2 == 0 ? tuff : calcite);
                            }
                        }
                        for (int x = 3; x <= 14; x++) {
                            if (x == 7 || x == 8) {
                                continue;
                            }
                            put(level, o, x, y, 14, (x + y) % 2 == 0 ? tuff : calcite);
                        }
                    }
                    // Ore pockets between the ribs (iron north, lapis south).
                    put(level, o, 1, 2, 2, AIR);
                    put(level, o, 1, 3, 3, AIR);
                    put(level, o, 1, 2, 4, AIR);
                    put(level, o, 1, 2, 11, AIR);
                    put(level, o, 1, 3, 13, AIR);
                    // Rib arch across the north run (z 5), then across the east run (x 11).
                    for (int x : new int[]{5, 10}) {
                        column(level, o, x, 1, 3, 5, bone);
                    }
                    put(level, o, 6, 4, 5, bone);
                    put(level, o, 9, 4, 5, bone);
                    put(level, o, 7, 5, 5, bone);
                    put(level, o, 8, 5, 5, bone);
                    for (int z : new int[]{5, 10}) {
                        column(level, o, 11, 1, 3, z, bone);
                    }
                    put(level, o, 11, 4, 6, bone);
                    put(level, o, 11, 4, 9, bone);
                    put(level, o, 11, 5, 7, bone);
                    put(level, o, 11, 5, 8, bone);
                    // Ribs growing out of the west wall.
                    for (int z : new int[]{2, 10}) {
                        column(level, o, 3, 1, 3, z, bone);
                        put(level, o, 4, 4, z, bone);
                        put(level, o, 5, 5, z, bone);
                    }
                    // Skulls and lit candles on pillar tops and ledges.
                    put(level, o, 5, 4, 5, RoomDsl.candle(1));
                    put(level, o, 10, 4, 5, skull);
                    put(level, o, 11, 4, 5, RoomDsl.candle(2));
                    put(level, o, 11, 4, 10, skull);
                    box(level, o, 4, 1, 13, 5, 2, 13, calcite);
                    box(level, o, 10, 1, 13, 11, 2, 13, calcite);
                    put(level, o, 4, 3, 13, RoomDsl.candle(3));
                    put(level, o, 5, 3, 13, skull);
                    put(level, o, 10, 3, 13, skull);
                    put(level, o, 11, 3, 13, RoomDsl.candle(2));
                });
    }
}
