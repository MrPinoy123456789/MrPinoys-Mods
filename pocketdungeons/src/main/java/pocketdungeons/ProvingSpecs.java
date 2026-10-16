package pocketdungeons;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;

import java.util.EnumSet;
import java.util.List;

import static pocketdungeons.RoomDsl.ACCENT;
import static pocketdungeons.RoomDsl.LAMP;
import static pocketdungeons.RoomDsl.column;
import static pocketdungeons.RoomDsl.put;

/**
 * The Proving's two rooms (decision 2026-10-16): a gate the descent enters by, and the ring
 * itself, a dead-end arena of open floor, four cover pillars and a marked centre. Authored
 * at rotation 0 in room-local coordinates; door lanes stay clear. The theme's processors
 * turn the placeholders into deepslate brick, basalt and soul lanterns.
 */
final class ProvingSpecs {

    private ProvingSpecs() {}

    static List<RoomSpec> list() {
        return List.of(provingGate(), provingArena());
    }

    /** The gate: a straight run into the ring, posts flanking the walk and lamps overhead. */
    private static RoomSpec provingGate() {
        return new RoomSpec("proving_gate", EnumSet.of(Direction.WEST, Direction.EAST))
                .decor(ProvingSpecs::gateDecor);
    }

    /** The ring: one door west, four pillars for cover, lamps on the caps, a marked centre. */
    private static RoomSpec provingArena() {
        return new RoomSpec("proving_arena", EnumSet.of(Direction.WEST))
                .decor(ProvingSpecs::arenaDecor);
    }

    private static void gateDecor(ServerLevel level, net.minecraft.core.BlockPos o) {
        for (int z : new int[]{4, 11}) {
            for (int x : new int[]{4, 11}) {
                column(level, o, x, 1, 4, z, ACCENT);
                put(level, o, x, 5, z, LAMP);
            }
        }
        // The walk's edge lines, marked in the floor.
        for (int x = 1; x <= 14; x++) {
            put(level, o, x, 0, 6, ACCENT);
            put(level, o, x, 0, 9, ACCENT);
        }
    }

    private static void arenaDecor(ServerLevel level, net.minecraft.core.BlockPos o) {
        for (int z : new int[]{3, 12}) {
            for (int x : new int[]{3, 12}) {
                column(level, o, x, 1, 4, z, ACCENT);
                put(level, o, x, 5, z, LAMP);
            }
        }
        // A diamond marked in the floor's middle, so the wave's centre reads on sight.
        for (int d = 0; d <= 3; d++) {
            put(level, o, 5 + d, 0, 8 - d, ACCENT);
            put(level, o, 8 + d, 0, 5 + d, ACCENT);
            put(level, o, 11 - d, 0, 8 + d, ACCENT);
            put(level, o, 8 - d, 0, 11 - d, ACCENT);
        }
    }
}
