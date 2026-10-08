package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.AIR;
import static pocketdungeons.RoomDsl.FLOOR;
import static pocketdungeons.RoomDsl.LAMP;
import static pocketdungeons.RoomDsl.WALL;
import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * Final-floor rooms for the early dungeons (infestation, ossuary, copper works, deepslate,
 * frostworks). Authored at rotation 0 in room-local coordinates; the west and east door lanes
 * (z 7 to 8 for x 1 to 3 and 12 to 14) are left clear. Ore is declared as manifest nodes over
 * the air pockets left here.
 */
final class FinalFloorEarlySpecs {

    private FinalFloorEarlySpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(queensNest());
        specs.add(lastRest());
        specs.add(masterFurnace());
        specs.add(silentDeep());
        specs.add(bigFreeze());
        return specs;
    }

    private static EnumSet<Direction> doors() {
        return EnumSet.of(Direction.WEST, Direction.EAST);
    }

    /**
     * The Queen's Nest (Infestation): a brood chamber. Infested cobblestone nodules fill the
     * corners, a two tier mound of infested cobblestone is crowned with turtle eggs standing in
     * for egg clutches, and cobwebs hang through the air around it.
     */
    private static RoomSpec queensNest() {
        return new RoomSpec("queens_nest", doors())
                .chests(new BlockPos(13, 1, 10))
                .spawns(new BlockPos(5, 1, 4), new BlockPos(10, 1, 4), new BlockPos(5, 1, 11),
                        new BlockPos(10, 1, 11))
                .decor((level, o) -> {
                    BlockState infested = Blocks.INFESTED_COBBLESTONE.defaultBlockState();
                    box(level, o, 1, 1, 1, 3, 3, 3, infested);
                    box(level, o, 12, 1, 1, 14, 3, 3, infested);
                    box(level, o, 1, 1, 12, 3, 3, 14, infested);
                    box(level, o, 12, 1, 12, 14, 3, 14, infested);
                    // The mound.
                    box(level, o, 6, 1, 6, 9, 1, 9, infested);
                    box(level, o, 7, 2, 7, 8, 2, 8, Blocks.COBBLESTONE.defaultBlockState());
                    BlockState eggs = Blocks.TURTLE_EGG.defaultBlockState().setValue(BlockStateProperties.EGGS, 4);
                    box(level, o, 7, 3, 7, 8, 3, 8, eggs);
                    put(level, o, 6, 2, 6, eggs);
                    put(level, o, 9, 2, 9, eggs);
                    put(level, o, 6, 2, 9, eggs.setValue(BlockStateProperties.EGGS, 2));
                    // Webs: thick over and around the mound, a few out in the room.
                    BlockState web = Blocks.COBWEB.defaultBlockState();
                    box(level, o, 5, 4, 5, 10, 4, 10, web);
                    for (int[] p : new int[][]{{4, 3, 6}, {11, 3, 9}, {5, 3, 8}, {10, 3, 7}, {7, 3, 5}, {8, 3, 10},
                            {4, 4, 9}, {11, 4, 6}, {3, 4, 5}, {12, 4, 10}, {7, 5, 3}, {8, 5, 12}}) {
                        put(level, o, p[0], p[1], p[2], web);
                    }
                });
    }

    /**
     * The Last Rest (Ossuary): a burial hall. A raised sarcophagus with a skull at its head,
     * bone pillars and candles around it, and burial niches along the north and south walls.
     */
    private static RoomSpec lastRest() {
        return new RoomSpec("last_rest", doors())
                .chests(new BlockPos(12, 1, 6))
                .spawns(new BlockPos(5, 1, 3), new BlockPos(10, 1, 3), new BlockPos(5, 1, 12),
                        new BlockPos(10, 1, 12))
                .decor((level, o) -> {
                    box(level, o, 5, 1, 7, 10, 1, 8, WALL);
                    box(level, o, 5, 2, 7, 10, 2, 8, FLOOR);
                    put(level, o, 5, 3, 7, Blocks.SKELETON_SKULL.defaultBlockState());
                    put(level, o, 7, 3, 8, RoomDsl.candle(3));
                    put(level, o, 9, 3, 7, RoomDsl.candle(2));
                    put(level, o, 10, 3, 8, RoomDsl.candle(1));
                    int[][] pillars = {{4, 5}, {11, 5}, {4, 10}, {11, 10}};
                    for (int[] p : pillars) {
                        column(level, o, p[0], 1, 5, p[1], WALL);
                    }
                    // Candle stubs flanking the long sides.
                    int[][] stubs = {{6, 5}, {9, 5}, {6, 10}, {9, 10}};
                    for (int[] s : stubs) {
                        put(level, o, s[0], 1, s[1], WALL);
                        put(level, o, s[0], 2, s[1], RoomDsl.candle(4));
                    }
                    // Burial niches: a solid wall row with lintel-capped 2-high pockets.
                    box(level, o, 2, 1, 1, 13, 3, 1, WALL);
                    box(level, o, 2, 1, 14, 13, 3, 14, WALL);
                    box(level, o, 4, 1, 1, 5, 2, 1, AIR);
                    box(level, o, 10, 1, 1, 11, 2, 1, AIR);
                    box(level, o, 4, 1, 14, 5, 2, 14, AIR);
                    box(level, o, 10, 1, 14, 11, 2, 14, AIR);
                });
    }

    private static BlockState bulb() {
        return Blocks.COPPER_BULB.waxed().unaffected().defaultBlockState().setValue(CopperBulbBlock.LIT, true);
    }

    /**
     * The Master Furnace (Copper Works): the great forge. A plinth carries a walled body with
     * an iron bar chimney to the ceiling, lit bulbs as windows and three working furnaces
     * facing the room. Ore bins stand along the west and east walls.
     */
    private static RoomSpec masterFurnace() {
        return new RoomSpec("master_furnace", doors())
                .chests(new BlockPos(13, 1, 13))
                .spawns(new BlockPos(5, 1, 9), new BlockPos(10, 1, 9), new BlockPos(5, 1, 12),
                        new BlockPos(10, 1, 12))
                .decor((level, o) -> {
                    box(level, o, 5, 1, 1, 10, 1, 4, FLOOR);
                    box(level, o, 5, 2, 1, 10, 3, 2, WALL);
                    BlockState furnace = Blocks.FURNACE.defaultBlockState()
                            .setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH);
                    for (int x = 6; x <= 8; x++) {
                        put(level, o, x, 2, 3, furnace);
                    }
                    put(level, o, 9, 2, 3, WALL);
                    put(level, o, 5, 2, 3, WALL);
                    put(level, o, 6, 3, 3, bulb());
                    put(level, o, 8, 3, 3, bulb());
                    put(level, o, 7, 3, 3, WALL);
                    put(level, o, 5, 3, 3, WALL);
                    put(level, o, 9, 3, 3, WALL);
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    box(level, o, 7, 4, 1, 8, 5, 2, bars);
                    put(level, o, 6, 3, 1, bulb());
                    put(level, o, 9, 3, 1, bulb());
                    // Ore bins: lintels over the pockets on both side walls.
                    for (int[] p : new int[][]{{1, 3}, {1, 10}, {14, 3}, {14, 10}}) {
                        box(level, o, p[0], 3, p[1], p[0], 3, p[1] + 2, RoomDsl.ACCENT);
                    }
                    for (int[] p : new int[][]{{3, 3}, {12, 3}, {3, 12}, {12, 12}}) {
                        put(level, o, p[0], 5, p[1], LAMP);
                    }
                });
    }

    /**
     * The Silent Deep (Deepslate): a hush. Two colonnades flank a central obelisk standing on
     * a plinth; the ring of cells around the obelisk holds the ore.
     */
    private static RoomSpec silentDeep() {
        return new RoomSpec("silent_deep", doors())
                .chests(new BlockPos(13, 1, 10))
                .spawns(new BlockPos(4, 1, 5), new BlockPos(11, 1, 5), new BlockPos(4, 1, 10),
                        new BlockPos(11, 1, 10))
                .decor((level, o) -> {
                    for (int x = 3; x <= 13; x += 2) {
                        column(level, o, x, 1, 5, 2, WALL);
                        column(level, o, x, 1, 5, 13, WALL);
                    }
                    box(level, o, 5, 1, 5, 10, 1, 10, FLOOR);
                    box(level, o, 7, 2, 7, 8, 4, 8, WALL);
                    // Ring cells on the plinth top are left as air for the manifest nodes.
                });
    }

    /**
     * The Big Freeze (Frostworks): a frozen cascade. A wall of blue ice spans the north part
     * from floor to ceiling with ore pockets cut into its south face, icicles hang from the
     * ceiling, a slick patch of blue ice sits mid floor and powder snow pits lurk in the far
     * southern corners.
     */
    private static RoomSpec bigFreeze() {
        return new RoomSpec("big_freeze", doors())
                .chests(new BlockPos(13, 1, 10))
                .spawns(new BlockPos(5, 1, 5), new BlockPos(10, 1, 5), new BlockPos(5, 1, 11),
                        new BlockPos(10, 1, 11))
                .decor((level, o) -> {
                    BlockState blue = Blocks.BLUE_ICE.defaultBlockState();
                    BlockState packed = Blocks.PACKED_ICE.defaultBlockState();
                    box(level, o, 1, 1, 1, 14, 5, 3, blue);
                    box(level, o, 3, 1, 2, 4, 2, 3, AIR);
                    box(level, o, 7, 1, 2, 8, 2, 3, AIR);
                    box(level, o, 11, 1, 2, 12, 2, 3, AIR);
                    // Icicle columns hanging from the ceiling.
                    int[][] icicles = {{2, 5}, {6, 6}, {9, 5}, {13, 6}, {5, 12}, {10, 11}, {2, 10}, {13, 12}};
                    for (int i = 0; i < icicles.length; i++) {
                        int x = icicles[i][0];
                        int z = icicles[i][1];
                        column(level, o, x, 4, 5, z, packed);
                        if (i % 2 == 0) {
                            put(level, o, x, 3, z, packed);
                        }
                    }
                    // Slick patch, powder snow pits and snow layers.
                    box(level, o, 6, 1, 6, 9, 1, 9, blue);
                    BlockState powder = Blocks.POWDER_SNOW.defaultBlockState();
                    box(level, o, 1, 1, 13, 2, 1, 14, powder);
                    box(level, o, 13, 1, 13, 14, 1, 14, powder);
                    BlockState snow = Blocks.SNOW.defaultBlockState();
                    for (int[] s : new int[][]{{3, 6}, {12, 9}, {7, 12}, {9, 4}, {4, 11}, {12, 5}}) {
                        put(level, o, s[0], 1, s[1], snow);
                    }
                    for (int[] s : new int[][]{{3, 12}, {12, 12}, {6, 9}, {9, 6}}) {
                        put(level, o, s[0], 5, s[1], LAMP);
                    }
                });
    }
}
