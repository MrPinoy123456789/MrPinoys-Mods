package pocketdungeons;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * Cow Pits anomaly rooms. {@code cow_ward} is the Specimen Ward: a clinical quartz corridor of
 * holding cells (containment has failed) and a sealed observation chamber with one cow on a
 * pedestal. The {@code cow_ward} content handler ({@link CowPits#spawnWard}) spawns the cows.
 */
final class CowWardSpecs {

    private CowWardSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(cowWard());
        return specs;
    }

    /** Local position of the pedestal cow's spawn point (the only spawn point at y 2). */
    static final BlockPos PEDESTAL_SPAWN = new BlockPos(7, 2, 12);

    private static RoomSpec cowWard() {
        return new RoomSpec("cow_ward", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(3, 1, 3), new BlockPos(7, 1, 3), new BlockPos(12, 1, 3), PEDESTAL_SPAWN)
                .decor((level, o) -> {
                    BlockState smooth = Blocks.SMOOTH_QUARTZ.defaultBlockState();
                    BlockState quartz = Blocks.QUARTZ_BLOCK.defaultBlockState();
                    BlockState bricks = Blocks.QUARTZ_BRICKS.defaultBlockState();
                    BlockState bars = Blocks.IRON_BARS.defaultBlockState();
                    BlockState glow = Blocks.GLOWSTONE.defaultBlockState();
                    BlockState hay = Blocks.HAY_BLOCK.defaultBlockState();
                    BlockState web = Blocks.COBWEB.defaultBlockState();

                    // Clean floor: smooth quartz everywhere on the ward side, quartz strips along the aisle.
                    RoomDsl.box(level, o, 1, 0, 1, 14, 0, 9, smooth);
                    RoomDsl.box(level, o, 1, 0, 6, 14, 0, 6, quartz);
                    RoomDsl.box(level, o, 1, 0, 9, 14, 0, 9, quartz);
                    // Strip lights over the main aisle, set into the ceiling row.
                    for (int x : new int[]{3, 7, 11}) {
                        RoomDsl.box(level, o, x, 5, 7, x + 1, 5, 8, glow);
                    }

                    // Holding cells: quartz dividers, a barred front at z=5, one gap per cell.
                    for (int x : new int[]{5, 9}) {
                        RoomDsl.box(level, o, x, 1, 1, x, 4, 5, quartz);
                    }
                    RoomDsl.box(level, o, 1, 1, 5, 14, 3, 5, bars);
                    RoomDsl.box(level, o, 1, 4, 5, 14, 4, 5, quartz);
                    for (int x : new int[]{5, 9}) {
                        RoomDsl.box(level, o, x, 1, 5, x, 3, 5, quartz);
                    }
                    for (int gap : new int[]{2, 7, 12}) {
                        RoomDsl.box(level, o, gap, 1, 5, gap, 3, 5, RoomDsl.AIR);
                    }
                    // A bar snapped off at the foot in the last cell, bedding and cobwebs.
                    RoomDsl.put(level, o, 13, 1, 5, RoomDsl.AIR);
                    RoomDsl.box(level, o, 1, 1, 1, 2, 1, 2, hay);
                    RoomDsl.put(level, o, 10, 1, 1, hay);
                    RoomDsl.box(level, o, 13, 1, 1, 14, 1, 2, hay);
                    RoomDsl.put(level, o, 1, 4, 1, web);
                    RoomDsl.put(level, o, 6, 4, 1, web);
                    RoomDsl.put(level, o, 14, 4, 1, web);
                    // Hanging bars in cell two.
                    RoomDsl.box(level, o, 7, 4, 1, 7, 5, 1, bars);
                    // The tally: stone brick slabs scratched into the dividers.
                    for (int z = 1; z <= 4; z++) {
                        RoomDsl.put(level, o, 5, 2, z, z % 2 == 0
                                ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : quartz);
                        RoomDsl.put(level, o, 9, 3, z, z % 2 == 1
                                ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : quartz);
                    }
                    RoomDsl.put(level, o, 13, 1, 9, Blocks.CAULDRON.defaultBlockState());

                    // Observation side: a quartz face with a tinted window, a dark sealed chamber behind it.
                    RoomDsl.box(level, o, 1, 1, 10, 14, 5, 14, Blocks.POLISHED_BLACKSTONE.defaultBlockState());
                    RoomDsl.box(level, o, 1, 1, 10, 14, 5, 10, bricks);
                    RoomDsl.box(level, o, 3, 1, 10, 12, 3, 10, Blocks.TINTED_GLASS.defaultBlockState());
                    // One pane is gone, webbed over (the way in; also keeps the chamber connected for the checks).
                    RoomDsl.box(level, o, 3, 1, 10, 3, 2, 10, web);
                    RoomDsl.box(level, o, 3, 1, 11, 12, 4, 14, RoomDsl.AIR);
                    // Pedestal ringed by glowstone set into the floor.
                    RoomDsl.box(level, o, 6, 0, 11, 9, 0, 14, glow);
                    RoomDsl.box(level, o, 7, 0, 12, 8, 0, 13, quartz);
                    RoomDsl.box(level, o, 7, 1, 12, 8, 1, 13, quartz);
                });
    }
}
