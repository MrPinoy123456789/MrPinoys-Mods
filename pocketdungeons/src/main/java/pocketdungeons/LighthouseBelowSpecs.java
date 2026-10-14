package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Lighthouse Below rooms: a lamp stair climbing past the burning shaft, the
 * cramped keeper's quarters and the top-of-tower lantern room for the final
 * floor. Authored at rotation 0 in room-local coordinates; the west and east
 * door lanes (z 7 to 8 for x 1 to 3 and 12 to 14) are kept clear.
 */
final class LighthouseBelowSpecs {

    private LighthouseBelowSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(lampStair());
        specs.add(keepersQuarters());
        specs.add(lanternRoom());
        return specs;
    }

    /**
     * Lamp Stair (straight, west east): a deepslate flight climbs the north wall
     * to a barred landing, turns south along the east wall and steps back down,
     * all of it winding round the lamp shaft that rises glowing through the
     * middle of the room.
     */
    private static RoomSpec lampStair() {
        return new RoomSpec("lamp_stair", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 10))
                .spawns(new BlockPos(3, 1, 4), new BlockPos(10, 1, 4), new BlockPos(3, 1, 11),
                        new BlockPos(10, 1, 11))
                .decor((level, o) -> {
                    BlockState polished = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                    BlockState tiles = Blocks.DEEPSLATE_TILES.defaultBlockState();
                    BlockState dark = Blocks.DARK_PRISMARINE.defaultBlockState();
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    BlockState lamp = Blocks.SEA_LANTERN.defaultBlockState();
                    // The lamp shaft: a dark prismarine core topped by the burning lens.
                    box(level, o, 7, 1, 7, 8, 4, 8, dark);
                    box(level, o, 7, 5, 7, 8, 5, 8, lamp);
                    // The stair: a rising flight along the north wall, a landing that
                    // turns south down the east wall, one step back to the floor.
                    put(level, o, 4, 1, 2, polished);
                    put(level, o, 5, 2, 2, polished);
                    box(level, o, 6, 3, 2, 12, 3, 2, polished);
                    box(level, o, 12, 3, 3, 12, 3, 5, polished);
                    put(level, o, 12, 2, 6, polished);
                    // Handrail of bars round the landing's inner edge.
                    for (int x = 6; x <= 11; x++) {
                        put(level, o, x, 4, 3, bars);
                    }
                    for (int z = 3; z <= 5; z++) {
                        put(level, o, 11, 4, z, bars);
                    }
                    // Two lamps flush in the floor; the rest of the light is the shaft's.
                    put(level, o, 3, 0, 10, lamp);
                    put(level, o, 11, 0, 12, lamp);
                    // Tile inlays where the stair meets the shaft.
                    put(level, o, 6, 0, 6, tiles);
                    put(level, o, 9, 0, 9, tiles);
                });
    }

    /**
     * Keeper's Quarters (dead end, west door): a low lobby screened from the
     * cramped quarters by a deepslate partition. A bunk, a stove, the keeper's
     * table and the lamp niche live behind it; the floor ahead of the partition
     * is kept open for whatever the haunt places there.
     */
    private static RoomSpec keepersQuarters() {
        return new RoomSpec("keepers_quarters", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(13, 1, 4))
                .spawns(new BlockPos(3, 1, 4), new BlockPos(4, 1, 11), new BlockPos(9, 1, 5),
                        new BlockPos(12, 1, 10))
                .decor((level, o) -> {
                    BlockState bricks = Blocks.DEEPSLATE_BRICKS.defaultBlockState();
                    BlockState dark = Blocks.DARK_PRISMARINE.defaultBlockState();
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    BlockState barrel = Blocks.BARREL.defaultBlockState();
                    BlockState lamp = Blocks.SEA_LANTERN.defaultBlockState();
                    // The partition between the lobby and the quarters, its gap at the
                    // south side with a brick lintel over it.
                    box(level, o, 7, 1, 1, 7, 3, 11, bricks);
                    box(level, o, 7, 1, 14, 7, 3, 14, bricks);
                    put(level, o, 7, 4, 12, bricks);
                    put(level, o, 7, 4, 13, bricks);
                    // A drying rack of bars in the lobby.
                    box(level, o, 2, 4, 4, 4, 4, 4, bars);
                    // The bunk: pillow to the north wall, a trunk at its foot.
                    BlockState bedHead = Blocks.BED.red().defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                            .setValue(BlockStateProperties.BED_PART, BedPart.HEAD);
                    BlockState bedFoot = Blocks.BED.red().defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH)
                            .setValue(BlockStateProperties.BED_PART, BedPart.FOOT);
                    put(level, o, 10, 1, 1, bedHead);
                    put(level, o, 10, 1, 2, bedFoot);
                    put(level, o, 12, 1, 1, barrel);
                    put(level, o, 12, 2, 1, barrel);
                    // The stove and its flue of bars.
                    put(level, o, 8, 1, 10, Blocks.SMOKER.defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.NORTH));
                    column(level, o, 8, 2, 4, 10, bars);
                    // The keeper's table with its candle, and the log book.
                    put(level, o, 11, 1, 9, dark);
                    put(level, o, 11, 2, 9, RoomDsl.candle(2));
                    put(level, o, 13, 1, 8, Blocks.LECTERN.defaultBlockState()
                            .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.WEST));
                    // The lamp niche on the south wall: pilasters, a lintel, the lamp.
                    column(level, o, 10, 1, 3, 14, bricks);
                    column(level, o, 13, 1, 3, 14, bricks);
                    box(level, o, 10, 4, 14, 13, 4, 14, bricks);
                    put(level, o, 11, 2, 14, lamp);
                    put(level, o, 12, 2, 14, lamp);
                    // A lobby lamp by the door.
                    put(level, o, 4, 5, 7, lamp);
                });
    }

    /**
     * Lantern Room (straight, west east): the top of the tower for the final
     * floor. A glass walk leads from both doors to the lens itself, a sea
     * lantern core burning inside its glass housing on a deepslate pedestal,
     * with lantern posts keeping the corners lit.
     */
    private static RoomSpec lanternRoom() {
        return new RoomSpec("lantern_room", EnumSet.of(Direction.WEST, Direction.EAST))
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(3, 1, 4), new BlockPos(12, 1, 4), new BlockPos(3, 1, 11),
                        new BlockPos(12, 1, 11))
                .decor((level, o) -> {
                    BlockState polished = Blocks.POLISHED_DEEPSLATE.defaultBlockState();
                    BlockState glass = Blocks.GLASS.defaultBlockState();
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    BlockState lamp = Blocks.SEA_LANTERN.defaultBlockState();
                    // The pad approach: a glass inlaid walk from each door to the lens.
                    box(level, o, 1, 0, 7, 4, 0, 8, glass);
                    box(level, o, 11, 0, 7, 14, 0, 8, glass);
                    // The lens: pedestal, glass housing, the burning core inside.
                    box(level, o, 6, 1, 6, 9, 1, 9, polished);
                    box(level, o, 6, 2, 6, 9, 4, 9, glass);
                    box(level, o, 7, 2, 7, 8, 3, 8, lamp);
                    // Corner lantern posts: barred columns crowned with light.
                    for (int[] p : new int[][]{{4, 4}, {11, 4}, {4, 11}, {11, 11}}) {
                        column(level, o, p[0], 1, 3, p[1], bars);
                        put(level, o, p[0], 4, p[1], lamp);
                    }
                    // Gallery lamps under the ceiling.
                    put(level, o, 6, 5, 3, lamp);
                    put(level, o, 9, 5, 3, lamp);
                    put(level, o, 6, 5, 12, lamp);
                    put(level, o, 9, 5, 12, lamp);
                });
    }
}
