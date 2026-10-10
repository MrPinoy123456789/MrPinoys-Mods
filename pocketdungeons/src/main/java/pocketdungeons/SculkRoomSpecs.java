package pocketdungeons;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Rooms built on the sculk rule (design pass 2026-10-09, Q3): sculk hears you; fill a room's meter and it
 * answers. The Hush Gallery replaces the sensor gallery (a redstone puzzle that read as a second sculk system).
 */
final class SculkRoomSpecs {

    private SculkRoomSpecs() {}

    static List<RoomSpec> list() {
        List<RoomSpec> specs = new ArrayList<>();
        specs.add(hushGallery());
        return specs;
    }

    /**
     * The Hush Gallery: quiet costs time, speed costs noise. A hall between a west and an east door with two
     * ways across. The loud way is the straight strip of dripstone down the middle under two hanging shriekers;
     * the quiet way is a ring of wool floor round the walls (walking on wool makes no vibration), which is
     * longer. Six sculk sensors stand between the two. The spawner is at the far end, so the fight that
     * follows is its own noise. Both routes always work and nothing is needed to cross.
     */
    private static RoomSpec hushGallery() {
        return new RoomSpec("hush_gallery", EnumSet.of(Direction.WEST, Direction.EAST))
                .spawns(new BlockPos(12, 1, 4), new BlockPos(12, 1, 11))
                .decor((level, o) -> {
                    BlockState wool = Blocks.WOOL.white().defaultBlockState();
                    BlockState dripstone = Blocks.DRIPSTONE_BLOCK.defaultBlockState();
                    BlockState amethyst = Blocks.AMETHYST_BLOCK.defaultBlockState();
                    BlockState sensor = Blocks.SCULK_SENSOR.defaultBlockState();
                    BlockState shrieker = Blocks.SCULK_SHRIEKER.defaultBlockState();

                    // The quiet way: wool floor along the north and south walls and across both ends.
                    for (int x = 1; x <= 14; x++) {
                        for (int z : new int[]{2, 3, 12, 13}) {
                            RoomDsl.put(level, o, x, 0, z, wool);
                        }
                    }
                    for (int z = 4; z <= 11; z++) {
                        for (int x : new int[]{1, 2, 13, 14}) {
                            RoomDsl.put(level, o, x, 0, z, wool);
                        }
                    }
                    // The loud way: dripstone down the middle, amethyst flecks in it.
                    RoomDsl.box(level, o, 3, 0, 7, 12, 0, 8, dripstone);
                    RoomDsl.put(level, o, 5, 0, 7, amethyst);
                    RoomDsl.put(level, o, 10, 0, 8, amethyst);
                    // Two shriekers hung from the ceiling over the loud way.
                    RoomDsl.put(level, o, 7, 5, 7, shrieker);
                    RoomDsl.put(level, o, 8, 5, 8, shrieker);
                    // Six sensors between the ways.
                    for (int x : new int[]{5, 8, 11}) {
                        RoomDsl.put(level, o, x, 1, 5, sensor);
                        RoomDsl.put(level, o, x, 1, 10, sensor);
                    }
                });
    }
}
