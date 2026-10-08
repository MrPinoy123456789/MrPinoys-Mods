package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Frostworks rooms: an ice vault dead end, a cold store tee and a snowdrift corner. Authored at
 * rotation 0 in room-local coordinates. Ore is declared as manifest nodes over the AIR pockets
 * carved here. Never plain ice (it melts); packed ice, blue ice and snow blocks only.
 */
final class FrostworksRoomSpecs {

    private FrostworksRoomSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(iceVault());
        specs.add(coldStore());
        specs.add(snowdrift());
        return specs;
    }

    /**
     * Frost Ice Vault (dead end, west door): a sealed-looking cave. The interior is solid blue ice
     * with a winding tunnel to a chest chamber at the far end; icicles hang from the roof and ore
     * pockets are cut into the walls.
     */
    private static RoomSpec iceVault() {
        return new RoomSpec("frost_ice_vault", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(13, 1, 8))
                .spawns(new BlockPos(9, 1, 4), new BlockPos(9, 1, 11), new BlockPos(12, 1, 4),
                        new BlockPos(12, 1, 11))
                .decor((level, o) -> {
                    BlockState blue = Blocks.BLUE_ICE.defaultBlockState();
                    BlockState packed = Blocks.PACKED_ICE.defaultBlockState();
                    box(level, o, 1, 1, 1, 14, 5, 14, blue);
                    // Entry tunnel from the west door, then the chest chamber.
                    box(level, o, 1, 1, 6, 8, 3, 9, AIR);
                    box(level, o, 9, 1, 3, 13, 4, 12, AIR);
                    // Icicles in the tunnel (z 6 and 9 edges) and the chamber.
                    for (int x : new int[]{4, 6}) {
                        put(level, o, x, 3, 6, packed);
                        put(level, o, x + 1, 3, 9, packed);
                    }
                    for (int[] p : new int[][]{{10, 5}, {11, 9}, {12, 6}, {10, 11}, {13, 4}}) {
                        column(level, o, p[0], 3, 4, p[1], packed);
                    }
                    // Frozen plinth around the chest.
                    put(level, o, 13, 1, 6, packed);
                    put(level, o, 13, 1, 10, packed);
                    // Ore pockets cut into the walls.
                    box(level, o, 3, 2, 11, 7, 2, 11, AIR);
                    box(level, o, 3, 2, 4, 7, 2, 4, AIR);
                    box(level, o, 11, 2, 13, 12, 2, 13, AIR);
                });
    }

    /**
     * Frost Cold Store (tee, north east south doors): lockers of packed ice with empty barrels along
     * the west and east walls, a central aisle, iron bars over the lockers, a powder snow pit in the
     * south east corner and a mineable gap between the west lockers.
     */
    private static RoomSpec coldStore() {
        return new RoomSpec("frost_cold_store", EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(5, 1, 13))
                .spawns(new BlockPos(5, 1, 5), new BlockPos(5, 1, 10), new BlockPos(10, 1, 4),
                        new BlockPos(10, 1, 11))
                .decor((level, o) -> {
                    BlockState packed = Blocks.PACKED_ICE.defaultBlockState();
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    BlockState barrelE = Blocks.BARREL.defaultBlockState()
                            .setValue(BlockStateProperties.FACING, Direction.EAST);
                    BlockState barrelW = Blocks.BARREL.defaultBlockState()
                            .setValue(BlockStateProperties.FACING, Direction.WEST);
                    // West lockers: backing x 1 to 2, barrels at x 3, bars on top. Gap at z 5 to 6 is the ore pocket.
                    for (int z = 2; z <= 13; z++) {
                        if (z == 13) {
                            continue;
                        }
                        box(level, o, 1, 1, z, 2, 2, z, packed);
                        box(level, o, 1, 3, z, 3, 3, z, bars);
                        put(level, o, 3, 1, z, barrelE);
                        put(level, o, 3, 2, z, barrelE);
                    }
                    box(level, o, 1, 1, 5, 3, 3, 5, AIR);
                    put(level, o, 3, 1, 5, AIR);
                    box(level, o, 1, 1, 5, 2, 2, 5, AIR);
                    // Keep the chest at (5,1,13) clear of the west row ends.
                    // East lockers: x 12 to 14, door lane z 7 to 8 left open.
                    for (int z : new int[]{2, 3, 4, 5, 10, 11}) {
                        box(level, o, 13, 1, z, 14, 2, z, packed);
                        box(level, o, 12, 3, z, 14, 3, z, bars);
                        put(level, o, 12, 1, z, barrelW);
                        put(level, o, 12, 2, z, barrelW);
                    }
                    // Powder snow pit, south east corner, ringed by packed ice.
                    box(level, o, 12, 1, 12, 14, 1, 14, Blocks.POWDER_SNOW.defaultBlockState());
                    box(level, o, 11, 1, 12, 11, 1, 14, packed);
                    box(level, o, 12, 1, 11, 14, 1, 11, packed);
                    // Ceiling frost.
                    for (int[] p : new int[][]{{5, 7}, {10, 7}, {4, 3}, {11, 13}}) {
                        put(level, o, p[0], 5, p[1], packed);
                        put(level, o, p[0], 4, p[1], packed);
                    }
                });
    }

    /**
     * Frost Snowdrift (corner, north and east doors): rolling snow layers of height 1 to 4, snow
     * block mounds, a powder snow pit and snow block banks that hide ore pockets at their edges.
     */
    private static RoomSpec snowdrift() {
        return new RoomSpec("frost_snowdrift", EnumSet.of(Direction.NORTH, Direction.EAST))
                .chests(new BlockPos(13, 1, 3))
                .spawns(new BlockPos(5, 1, 9), new BlockPos(9, 1, 5), new BlockPos(9, 1, 10),
                        new BlockPos(5, 1, 13))
                .decor((level, o) -> {
                    BlockState snowBlock = Blocks.SNOW_BLOCK.defaultBlockState();
                    // Rolling drift of snow layers, 1 to 4 high, skipping lanes, spawns, chest and the pit.
                    for (int x = 1; x <= 14; x++) {
                        for (int z = 1; z <= 14; z++) {
                            boolean northLane = x >= 7 && x <= 8 && z <= 3;
                            boolean eastLane = z >= 7 && z <= 8 && x >= 12;
                            boolean pit = x <= 3 && z >= 11 && z <= 13;
                            boolean bank = x <= 3 && z <= 5;
                            boolean chestYard = x >= 10 && z <= 6;
                            boolean special = (x == 5 && z == 9) || (x == 9 && z == 5) || (x == 9 && z == 10)
                                    || (x == 5 && z == 13) || (x == 13 && z == 3);
                            if (northLane || eastLane || pit || bank || chestYard || special) {
                                continue;
                            }
                            int h = 1 + (int) Math.floor(1.5 + 1.5 * Math.sin(x * 0.5) * Math.cos(z * 0.5));
                            h = Math.max(1, Math.min(4, h));
                            put(level, o, x, 1, z, Blocks.SNOW.defaultBlockState()
                                    .setValue(BlockStateProperties.LAYERS, h));
                        }
                    }
                    // Snow block mounds, topped with a thick layer.
                    BlockState layer3 = Blocks.SNOW.defaultBlockState().setValue(BlockStateProperties.LAYERS, 3);
                    for (int[] m : new int[][]{{11, 11}, {12, 11}, {11, 12}, {5, 6}, {6, 6}}) {
                        put(level, o, m[0], 1, m[1], snowBlock);
                        put(level, o, m[0], 2, m[1], layer3);
                    }
                    // Powder snow pit, rimmed with snow blocks.
                    box(level, o, 1, 1, 11, 3, 1, 13, Blocks.POWDER_SNOW.defaultBlockState());
                    box(level, o, 1, 1, 10, 4, 1, 10, snowBlock);
                    put(level, o, 4, 1, 11, snowBlock);
                    put(level, o, 4, 1, 12, snowBlock);
                    put(level, o, 4, 1, 13, snowBlock);
                    // Bank of snow blocks in the north west with ore pockets under its edge.
                    box(level, o, 1, 1, 1, 3, 3, 5, snowBlock);
                    box(level, o, 1, 1, 2, 2, 1, 3, AIR);
                    box(level, o, 1, 2, 4, 2, 2, 5, AIR);
                });
    }
}
