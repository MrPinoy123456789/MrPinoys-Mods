package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.ACCENT;
import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.FLOOR;
import static pocketdungeons.RoomDsl.WALL;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Copper Works rooms: a boiler room (dead end), a pipe hall (tee) and an ore chute (corner).
 * Authored at rotation 0 in room-local coordinates; door lanes are left clear. Ore is declared
 * as manifest nodes over the enclosed air pockets left here.
 */
final class CopperWorksRoomSpecs {

    private CopperWorksRoomSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(copperBoiler());
        specs.add(copperPipeHall());
        specs.add(copperOreChute());
        return specs;
    }

    private static BlockState bulb() {
        return Blocks.COPPER_BULB.waxed().unaffected().defaultBlockState().setValue(CopperBulbBlock.LIT, true);
    }

    private static BlockState bars() {
        return Blocks.IRON_BARS.defaultBlockState();
    }

    /**
     * Copper Boiler (dead end, west door): a fat tank with barred gauge windows and lit bulbs,
     * a coal bunker against the east wall, a stoker's alcove with the chest and a furnace, and
     * pipes of bars running floor to ceiling.
     */
    private static RoomSpec copperBoiler() {
        return new RoomSpec("copper_boiler", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(2, 1, 13))
                .spawns(new BlockPos(6, 1, 11), new BlockPos(9, 1, 12), new BlockPos(11, 1, 6),
                        new BlockPos(3, 1, 6))
                .decor((level, o) -> {
                    // The tank.
                    box(level, o, 5, 1, 2, 10, 5, 5, WALL);
                    for (int x : new int[]{6, 9}) {
                        box(level, o, x, 2, 5, x, 3, 5, bars());
                    }
                    put(level, o, 7, 3, 5, bulb());
                    put(level, o, 8, 3, 5, bulb());
                    // Pipes from the floor to the ceiling.
                    column(level, o, 4, 1, 5, 3, bars());
                    column(level, o, 11, 1, 5, 3, bars());
                    box(level, o, 11, 5, 3, 14, 5, 3, bars());
                    // Coal bunker: a walled bin on the far wall with an air pocket for the coal.
                    box(level, o, 13, 1, 11, 14, 2, 14, WALL);
                    box(level, o, 14, 1, 12, 14, 2, 13, AIR);
                    // Stoker's alcove: partition wall, roof and a working furnace beside the chest.
                    box(level, o, 4, 1, 10, 4, 2, 14, WALL);
                    box(level, o, 1, 4, 10, 4, 4, 14, WALL);
                    put(level, o, 4, 3, 12, bulb());
                    put(level, o, 3, 1, 13, Blocks.FURNACE.defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.WEST));
                });
    }

    /**
     * Copper Pipe Hall (tee: north, east, south): bars pipes overhead at y5 and down the walls
     * with lit bulb valve wheels, a grated walkway of floor blocks, and ore bins at the wall
     * bases.
     */
    private static RoomSpec copperPipeHall() {
        return new RoomSpec("copper_pipe_hall", EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(13, 1, 12))
                .spawns(new BlockPos(4, 1, 8), new BlockPos(10, 1, 4), new BlockPos(10, 1, 10),
                        new BlockPos(5, 1, 13))
                .decor((level, o) -> {
                    // Grated walkway in the floor row.
                    box(level, o, 6, 0, 1, 9, 0, 14, FLOOR);
                    box(level, o, 9, 0, 6, 14, 0, 9, FLOOR);
                    // Overhead pipes.
                    box(level, o, 4, 5, 1, 4, 5, 14, bars());
                    box(level, o, 11, 5, 1, 11, 5, 14, bars());
                    box(level, o, 4, 5, 5, 11, 5, 5, bars());
                    box(level, o, 4, 5, 10, 11, 5, 10, bars());
                    // Down pipes with valve wheels.
                    for (int z : new int[]{8, 12}) {
                        column(level, o, 1, 1, 2, z, bars());
                        put(level, o, 1, 3, z, bulb());
                        column(level, o, 1, 4, 5, z, bars());
                    }
                    column(level, o, 14, 1, 2, 10, bars());
                    put(level, o, 14, 3, 10, bulb());
                    column(level, o, 14, 4, 5, 10, bars());
                    // Ore bins at the wall bases, each with an enclosed pocket.
                    box(level, o, 1, 1, 2, 2, 2, 5, ACCENT);
                    box(level, o, 1, 1, 3, 1, 2, 4, AIR);
                    box(level, o, 13, 1, 2, 14, 2, 5, ACCENT);
                    box(level, o, 14, 1, 3, 14, 1, 4, AIR);
                });
    }

    /**
     * Copper Ore Chute (corner: north, east): a rail line enters the north door and bends east to
     * the east door, a loading bay of hoppers stands on a block mass, and a pile of ore bins fills
     * the south-west corner.
     */
    private static RoomSpec copperOreChute() {
        return new RoomSpec("copper_ore_chute", EnumSet.of(Direction.NORTH, Direction.EAST))
                .chests(new BlockPos(13, 1, 12))
                .spawns(new BlockPos(4, 1, 4), new BlockPos(5, 1, 8), new BlockPos(11, 1, 11),
                        new BlockPos(11, 1, 5))
                .decor((level, o) -> {
                    // Rail line: north door south along x 7, then east along z 7.
                    BlockState ns = Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_SOUTH);
                    BlockState ew = Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.EAST_WEST);
                    for (int z = 1; z <= 6; z++) {
                        put(level, o, 7, 1, z, ns);
                    }
                    put(level, o, 7, 1, 7, Blocks.RAIL.defaultBlockState()
                            .setValue(BlockStateProperties.RAIL_SHAPE, RailShape.NORTH_EAST));
                    for (int x = 8; x <= 14; x++) {
                        put(level, o, x, 1, 7, ew);
                    }
                    // Loading bay.
                    box(level, o, 10, 1, 2, 13, 2, 4, WALL);
                    box(level, o, 10, 3, 3, 13, 3, 3, Blocks.HOPPER.defaultBlockState());
                    put(level, o, 11, 2, 4, bulb());
                    put(level, o, 12, 2, 4, bulb());
                    // Pile of ore bins.
                    box(level, o, 1, 1, 10, 4, 2, 13, ACCENT);
                    box(level, o, 1, 1, 11, 3, 2, 12, AIR);
                });
    }
}
