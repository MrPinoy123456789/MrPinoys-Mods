package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.box;
import static pocketdungeons.RoomDsl.put;

/**
 * Ender Archive themed rooms: a reading nook, the stacks and the card catalog. Authored at
 * rotation 0 in room-local coordinates. Real bookshelves drop books, so they are capped at four
 * per room and the rest of the shelving is chiseled bookshelf.
 */
final class EnderArchiveRoomSpecs {

    private EnderArchiveRoomSpecs() {}

    private static BlockState s(Block block) {
        return block.defaultBlockState();
    }

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(readingNook());
        specs.add(stacks());
        specs.add(catalog());
        return specs;
    }

    /** Dead end: a lectern in a three sided shelf alcove, a rug, and ore hidden behind the back shelves. */
    private static RoomSpec readingNook() {
        return new RoomSpec("archive_reading_nook", EnumSet.of(Direction.WEST))
                .chests(new BlockPos(12, 1, 6))
                .spawns(new BlockPos(5, 1, 4), new BlockPos(5, 1, 11), new BlockPos(8, 1, 3), new BlockPos(8, 1, 12))
                .decor((level, o) -> {
                    BlockState shelf = s(Blocks.CHISELED_BOOKSHELF);
                    // Three shelf sides: north, south and the back wall.
                    box(level, o, 9, 1, 5, 13, 3, 5, shelf);
                    box(level, o, 9, 1, 10, 13, 3, 10, shelf);
                    box(level, o, 13, 1, 5, 14, 3, 10, shelf);
                    // Hidden pocket behind the back shelves (nodes fill it).
                    box(level, o, 14, 1, 7, 14, 2, 8, RoomDsl.AIR);
                    // Two real bookshelves.
                    put(level, o, 13, 2, 5, s(Blocks.BOOKSHELF));
                    put(level, o, 13, 2, 10, s(Blocks.BOOKSHELF));
                    // Rug and lectern.
                    // Carpet has a thin collision box and fails the walkable check, so the rug is a floor row.
                    box(level, o, 9, 0, 6, 12, 0, 9, s(Blocks.MOSS_BLOCK));
                    put(level, o, 11, 1, 8, s(Blocks.LECTERN));
                    // End rods on the shelf tops and hung from the ceiling.
                    put(level, o, 9, 4, 5, s(Blocks.END_ROD));
                    put(level, o, 13, 4, 5, s(Blocks.END_ROD));
                    put(level, o, 9, 4, 10, s(Blocks.END_ROD));
                    put(level, o, 13, 4, 10, s(Blocks.END_ROD));
                    put(level, o, 11, 5, 8, s(Blocks.END_ROD));
                    put(level, o, 5, 5, 6, s(Blocks.END_ROD));
                    put(level, o, 5, 5, 9, s(Blocks.END_ROD));
                });
    }

    /** Tee: staggered chiseled rows form aisles; four real shelves; ore in a closed pocket. */
    private static RoomSpec stacks() {
        return new RoomSpec("archive_stacks", EnumSet.of(Direction.NORTH, Direction.EAST, Direction.SOUTH))
                .chests(new BlockPos(13, 1, 3))
                .spawns(new BlockPos(3, 1, 6), new BlockPos(6, 1, 11), new BlockPos(12, 1, 12), new BlockPos(10, 1, 4))
                .decor((level, o) -> {
                    BlockState shelf = s(Blocks.CHISELED_BOOKSHELF);
                    // Staggered rows (x, z from, z to).
                    int[][] rows = {
                            {2, 3, 7}, {2, 9, 13}, {4, 1, 5}, {4, 8, 12},
                            {11, 1, 5}, {13, 9, 13}, {11, 9, 13}
                    };
                    for (int[] r : rows) {
                        box(level, o, r[0], 1, r[1], r[0], 3, r[2], shelf);
                    }
                    // Closed pocket behind the south-west rows.
                    box(level, o, 1, 1, 9, 1, 3, 11, shelf);
                    box(level, o, 1, 1, 10, 1, 2, 10, RoomDsl.AIR);
                    // Four real bookshelves.
                    put(level, o, 2, 2, 5, s(Blocks.BOOKSHELF));
                    put(level, o, 4, 2, 3, s(Blocks.BOOKSHELF));
                    put(level, o, 11, 2, 3, s(Blocks.BOOKSHELF));
                    put(level, o, 13, 2, 11, s(Blocks.BOOKSHELF));
                    // End rods hung from the ceiling over the aisles.
                    put(level, o, 3, 5, 5, s(Blocks.END_ROD));
                    put(level, o, 3, 5, 10, s(Blocks.END_ROD));
                    put(level, o, 12, 5, 5, s(Blocks.END_ROD));
                    put(level, o, 12, 5, 11, s(Blocks.END_ROD));
                    put(level, o, 6, 5, 8, s(Blocks.END_ROD));
                    put(level, o, 9, 5, 8, s(Blocks.END_ROD));
                });
    }

    /** Corner: a long chiseled shelf wall, drawers of barrels and lecterns, ore pockets in the wall. */
    private static RoomSpec catalog() {
        return new RoomSpec("archive_catalog", EnumSet.of(Direction.NORTH, Direction.EAST))
                .chests(new BlockPos(12, 1, 11))
                .spawns(new BlockPos(4, 1, 4), new BlockPos(11, 1, 4), new BlockPos(4, 1, 8), new BlockPos(10, 1, 8))
                .decor((level, o) -> {
                    BlockState shelf = s(Blocks.CHISELED_BOOKSHELF);
                    // The long shelf wall, two blocks thick.
                    box(level, o, 1, 1, 13, 14, 3, 14, shelf);
                    // Two sealed pockets in the wall (nodes fill them).
                    box(level, o, 4, 1, 14, 4, 2, 14, RoomDsl.AIR);
                    box(level, o, 11, 1, 14, 11, 2, 14, RoomDsl.AIR);
                    // First drawer row: alternating barrels and reading stands, aisle gap at x 7 to 8.
                    for (int x = 2; x <= 13; x++) {
                        if (x == 7 || x == 8) {
                            continue;
                        }
                        put(level, o, x, 1, 12, s(x % 2 == 1 ? Blocks.BARREL : Blocks.LECTERN));
                        if (x % 2 == 1) {
                            put(level, o, x, 2, 12, s(Blocks.BARREL));
                        }
                    }
                    // Second drawer row, short, on either side.
                    for (int x : new int[] {2, 3, 4, 11, 12, 13}) {
                        if (x != 12) {
                            put(level, o, x, 1, 10, s(Blocks.BARREL));
                        }
                    }
                    // End rods on the wall top and hung over the floor.
                    put(level, o, 2, 4, 13, s(Blocks.END_ROD));
                    put(level, o, 6, 4, 13, s(Blocks.END_ROD));
                    put(level, o, 9, 4, 13, s(Blocks.END_ROD));
                    put(level, o, 13, 4, 13, s(Blocks.END_ROD));
                    put(level, o, 5, 5, 6, s(Blocks.END_ROD));
                    put(level, o, 10, 5, 6, s(Blocks.END_ROD));
                });
    }
}
